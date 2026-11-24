package com.nuvio.tv.core.cleanstream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * NuvioTv-Clean: poster tags for every row, not only the CleanStream add-on's.
 *
 * Posters ask for their title with [request]; requests are collected for a
 * moment and sent to the server together (GET /api/badges?ids=...), and the
 * answers are kept for the rest of the session.
 */
object CleanStreamBadges {

    data class Badge(
        /** The title has confirmed skips. */
        val clean: Boolean,
        /** The title has unconfirmed skips (marked by one viewer, or imported). */
        val hasUnconfirmed: Boolean = false,
        /** "digital", "upcoming", "cinema" or "unknown"; null for shows. */
        val digitalStatus: String?,
        /** yyyy-MM-dd of the digital release when known. */
        val digitalDate: String?,
        /**
         * What the title is still waiting for: "release", "digital", "cinema"
         * (movies), "premiere", "episode" (shows); null when it is already out.
         */
        val upcomingKind: String? = null,
        /** yyyy-MM-dd of that event (null for "cinema"). */
        val upcomingDate: String? = null,
        /** Episode code such as "S2E5" for "episode". */
        val upcomingLabel: String? = null,
    )

    private const val TAG = "CleanStreamBadges"
    private const val BATCH_DELAY_MS = 400L
    private const val MAX_PER_REQUEST = 40
    private const val TIMEOUT_MS = 10_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _badges = MutableStateFlow<Map<String, Badge>>(emptyMap())
    val badges: StateFlow<Map<String, Badge>> = _badges.asStateFlow()

    private val requested = ConcurrentHashMap.newKeySet<String>()
    /** IMDb ids known to be shows, so the server looks up episodes instead of releases. */
    private val showIds = ConcurrentHashMap.newKeySet<String>()
    private val queue = ConcurrentHashMap.newKeySet<String>()
    private var batchJob: Job? = null

    /**
     * Ask for the tags of a title: [imdbId] is tt1234567, or tmdb:movie:123 /
     * tmdb:tv:123 for catalogs that use TMDB numbers. Each is fetched once per session.
     */
    fun request(imdbId: String, isShow: Boolean = false) {
        if (!Regex("^(tt\\d{1,10}|tmdb:(movie|tv):\\d{1,10})$").matches(imdbId)) return
        if (isShow && imdbId.startsWith("tt")) showIds.add(imdbId)
        if (!CleanStreamSettings.current.enabled) return
        if (!requested.add(imdbId)) return
        synchronized(this) {
            queue.add(imdbId)
            if (batchJob != null) return // the running batch will pick it up
            batchJob = scope.launch {
                delay(BATCH_DELAY_MS)
                while (true) {
                    // Taking the next batch and ending the job happen under the same lock
                    // as adding to the queue, so no request can slip in between
                    val batch = synchronized(this@CleanStreamBadges) {
                        val next = queue.take(MAX_PER_REQUEST)
                        if (next.isEmpty()) batchJob = null else queue.removeAll(next.toSet())
                        next
                    }
                    if (batch.isEmpty()) break
                    fetch(batch)
                }
            }
        }
    }

    private fun fetch(ids: List<String>) {
        val server = CleanStreamSettings.current.serverUrl.trimEnd('/')
        val shows = ids.filter { it in showIds }
        val url = "$server/api/badges?ids=${ids.joinToString(",")}" +
            if (shows.isNotEmpty()) "&shows=${shows.joinToString(",")}" else ""
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "Bad server address: ${e.message}")
            requested.removeAll(ids.toSet())
            return
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "NuvioTv-Clean")
            if (connection.responseCode !in 200..299) {
                requested.removeAll(ids.toSet()) // try again later
                return
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val found = ids.mapNotNull { id ->
                val o = json.optJSONObject(id) ?: return@mapNotNull null
                val digital = o.optJSONObject("digital")
                val upcoming = o.optJSONObject("upcoming")
                id to Badge(
                    clean = o.optBoolean("clean", false),
                    hasUnconfirmed = o.optInt("pending", 0) > 0,
                    digitalStatus = digital?.optString("status")?.takeIf { it.isNotBlank() },
                    digitalDate = digital?.optString("date")?.takeIf { it.isNotBlank() && it != "null" },
                    upcomingKind = upcoming?.optString("kind")?.takeIf { it.isNotBlank() && it != "null" },
                    upcomingDate = upcoming?.optString("date")?.takeIf { it.isNotBlank() && it != "null" },
                    upcomingLabel = upcoming?.optString("label")?.takeIf { it.isNotBlank() && it != "null" },
                )
            }
            _badges.update { it + found }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load poster tags: ${e.message}")
            requested.removeAll(ids.toSet())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Short text for the bottom tab, only for titles not out yet:
     * "Digital in 5 days", "Out tomorrow", "Premieres on 12 Mar",
     * "S2E5 today", "In cinemas"; null when the title is already out.
     */
    fun upcomingText(badge: Badge, today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        val kind = badge.upcomingKind ?: return null
        if (kind == "cinema") return "In cinemas"
        val date = badge.upcomingDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        if (days < 0) return null // already out; the answer was older than today
        val whenText = when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days <= 60L -> "in $days days"
            else -> {
                val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                if (date.year == today.year) "on ${date.dayOfMonth} $month" else "on ${date.dayOfMonth} $month ${date.year}"
            }
        }
        return when (kind) {
            "digital" -> "Digital $whenText"
            "release" -> "Out $whenText"
            "premiere" -> "Premieres $whenText"
            "episode" -> "${badge.upcomingLabel ?: "New episode"} $whenText"
            else -> null
        }
    }
}
