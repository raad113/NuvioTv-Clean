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
        /** "digital", "upcoming", "cinema" or "unknown"; null for shows. */
        val digitalStatus: String?,
        /** yyyy-MM-dd of the digital release when known. */
        val digitalDate: String?,
    )

    private const val TAG = "CleanStreamBadges"
    private const val BATCH_DELAY_MS = 400L
    private const val MAX_PER_REQUEST = 40
    private const val TIMEOUT_MS = 10_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _badges = MutableStateFlow<Map<String, Badge>>(emptyMap())
    val badges: StateFlow<Map<String, Badge>> = _badges.asStateFlow()

    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val queue = ConcurrentHashMap.newKeySet<String>()
    private var batchJob: Job? = null

    /**
     * Ask for the tags of a title: [imdbId] is tt1234567, or tmdb:movie:123 /
     * tmdb:tv:123 for catalogs that use TMDB numbers. Each is fetched once per session.
     */
    fun request(imdbId: String) {
        if (!Regex("^(tt\\d{1,10}|tmdb:(movie|tv):\\d{1,10})$").matches(imdbId)) return
        if (!CleanStreamSettings.current.enabled) return
        if (!requested.add(imdbId)) return
        queue.add(imdbId)
        synchronized(this) {
            if (batchJob?.isActive == true) return
            batchJob = scope.launch {
                delay(BATCH_DELAY_MS)
                while (queue.isNotEmpty()) {
                    val batch = queue.take(MAX_PER_REQUEST)
                    queue.removeAll(batch.toSet())
                    fetch(batch)
                }
            }
        }
    }

    private fun fetch(ids: List<String>) {
        val server = CleanStreamSettings.current.serverUrl.trimEnd('/')
        val connection = URL("$server/api/badges?ids=${ids.joinToString(",")}").openConnection() as HttpURLConnection
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
                id to Badge(
                    clean = o.optBoolean("clean", false),
                    digitalStatus = digital?.optString("status")?.takeIf { it.isNotBlank() },
                    digitalDate = digital?.optString("date")?.takeIf { it.isNotBlank() && it != "null" },
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
     * Short text for the release tab: "Digital · Mar 2024" (released),
     * "Released" (released, date unknown), "Digital 12 Dec" (coming),
     * "In Cinemas", or null when nothing is known.
     */
    fun releaseText(badge: Badge, today: java.time.LocalDate = java.time.LocalDate.now()): String? =
        when (badge.digitalStatus) {
            "digital" -> badge.digitalDate?.let { raw ->
                runCatching {
                    val date = java.time.LocalDate.parse(raw)
                    val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                    "Digital · $month ${date.year}"
                }.getOrNull()
            } ?: "Released"
            "cinema" -> "In Cinemas"
            "upcoming" -> badge.digitalDate?.let { raw ->
                runCatching {
                    val date = java.time.LocalDate.parse(raw)
                    val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                    if (date.year == today.year) "Digital ${date.dayOfMonth} $month"
                    else "Digital ${date.dayOfMonth} $month ${date.year}"
                }.getOrNull()
            }
            else -> null
        }
}
