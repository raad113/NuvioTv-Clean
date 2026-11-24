package com.nuvio.tv.core.cleanstream

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * NuvioTv-Clean: family-friendly scene skipping powered by a CleanStream server.
 *
 * The player asks [CleanStreamClient] for the skip list of the title being
 * watched, then jumps over flagged scenes (or mutes them, for language) while
 * it plays. Everything here is self-contained so the rest of the app does not
 * need new dependencies.
 */

/** Filter categories shown in settings, with the labels used on screen. */
enum class CleanStreamCategory(val key: String, val label: String, val defaultLevel: CleanStreamLevel) {
    Nudity("nudity", "Nudity", CleanStreamLevel.High),
    Sex("sex", "Sexual content / kissing", CleanStreamLevel.High),
    Violence("violence", "Violence", CleanStreamLevel.Medium),
    Language("language", "Language (muted)", CleanStreamLevel.Off),
    Drugs("drugs", "Drugs / alcohol", CleanStreamLevel.Off),
    Fear("fear", "Frightening scenes", CleanStreamLevel.Off),
}

/**
 * How strict a filter is. High skips every flagged scene in the category,
 * Medium skips medium and strong scenes, Low skips only the strongest.
 */
enum class CleanStreamLevel(val key: String, val label: String, val strictness: Int) {
    Off("off", "Off", 0),
    Low("low", "Low (strongest only)", 1),
    Medium("medium", "Medium", 2),
    High("high", "High (skip all)", 3);

    fun next(): CleanStreamLevel = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(key: String?): CleanStreamLevel? = entries.firstOrNull { it.key == key }
    }
}

data class CleanStreamConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val levels: Map<CleanStreamCategory, CleanStreamLevel>,
    /** Extra time skipped before and after every scene, to cover small timing differences. */
    val marginMs: Long = 1_000L,
    /** Moves every skip later (positive) or earlier (negative), for a differently timed copy. */
    val offsetMs: Long = 0L,
    /** Severity given to scenes marked with the ♥ button: "low", "medium" or "high". */
    val quickSeverity: String = "medium",
    /** Per profile: mute strong language found in the subtitles. */
    val subtitleMuteEnabled: Boolean = false,
    /** Per profile: extra words to mute, separated by commas. */
    val customMuteWords: String = "",
)

/** Choices offered in settings, cycled by pressing the row. */
object CleanStreamTiming {
    val MARGINS_MS = listOf(0L, 1_000L, 2_000L, 3_000L, 5_000L)
    val OFFSETS_MS = listOf(0L, 500L, 1_000L, 2_000L, 3_000L, 5_000L, -5_000L, -3_000L, -2_000L, -1_000L, -500L)

    fun nextAfter(options: List<Long>, current: Long): Long =
        options[(options.indexOf(current).coerceAtLeast(0) + 1) % options.size]

    fun label(ms: Long): String {
        val seconds = ms / 1000.0
        val text = if (seconds % 1.0 == 0.0) seconds.toLong().toString() else seconds.toString()
        return if (ms > 0) "+$text s" else "$text s"
    }
}

/** One stretch of video to skip (or, for channel "audio", to mute). */
data class CleanSkip(
    val startMs: Long,
    val endMs: Long,
    val channel: String,
    val label: String,
    /** Server segment ids behind this skip, used for confirming or rejecting it. */
    val ids: List<String> = emptyList(),
) {
    val key: String get() = "$startMs-$endMs"
}

/**
 * Skips for one title: [skips] apply automatically (confirmed, or this
 * device's own); [pending] are other viewers' unconfirmed marks, offered as a
 * "Skip" prompt instead of being skipped silently.
 */
data class CleanStreamSkips(
    val skips: List<CleanSkip> = emptyList(),
    val pending: List<CleanSkip> = emptyList(),
)

/**
 * Settings stored in plain SharedPreferences and exposed as a StateFlow.
 */
object CleanStreamSettings {
    private const val PREFS = "clean_stream_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server_url"
    private const val KEY_MARGIN = "margin_ms"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_QUICK_SEVERITY = "quick_severity"
    private const val KEY_SUB_MUTE_PREFIX = "subtitle_mute_enabled_"
    private const val KEY_SUB_WORDS_PREFIX = "subtitle_mute_words_"

    /** The Nuvio profile whose subtitle-muting settings are in use. */
    private var activeProfileId: Int = 1

    /** Used until the viewer enters their own server in settings. */
    const val DEFAULT_SERVER_URL = "https://familyskips.duckdns.org"

    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(defaultConfig())

    private fun defaultConfig() = CleanStreamConfig(
        enabled = true,
        serverUrl = DEFAULT_SERVER_URL,
        levels = CleanStreamCategory.entries.associateWith { it.defaultLevel },
    )

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        state.value = CleanStreamConfig(
            enabled = p.getBoolean(KEY_ENABLED, true),
            serverUrl = p.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL,
            levels = CleanStreamCategory.entries.associateWith { cat ->
                CleanStreamLevel.from(p.getString("level_${cat.key}", null)) ?: cat.defaultLevel
            },
            marginMs = p.getLong(KEY_MARGIN, 1_000L),
            offsetMs = p.getLong(KEY_OFFSET, 0L),
            quickSeverity = p.getString(KEY_QUICK_SEVERITY, null) ?: "medium",
            subtitleMuteEnabled = p.getBoolean(KEY_SUB_MUTE_PREFIX + activeProfileId, false),
            customMuteWords = p.getString(KEY_SUB_WORDS_PREFIX + activeProfileId, null) ?: "",
        )
    }

    fun config(context: Context): StateFlow<CleanStreamConfig> {
        init(context)
        return state.asStateFlow()
    }

    val current: CleanStreamConfig get() = state.value

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        state.value = state.value.copy(enabled = enabled)
    }

    fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/').ifBlank { DEFAULT_SERVER_URL }
        prefs?.edit()?.putString(KEY_SERVER, clean)?.apply()
        state.value = state.value.copy(serverUrl = clean)
    }

    fun setMargin(ms: Long) {
        prefs?.edit()?.putLong(KEY_MARGIN, ms)?.apply()
        state.value = state.value.copy(marginMs = ms)
    }

    fun setOffset(ms: Long) {
        prefs?.edit()?.putLong(KEY_OFFSET, ms)?.apply()
        state.value = state.value.copy(offsetMs = ms)
    }

    /** Called when the active Nuvio profile changes, to load that profile's settings. */
    fun setActiveProfile(context: Context, profileId: Int) {
        init(context)
        activeProfileId = profileId
        val p = prefs ?: return
        state.value = state.value.copy(
            subtitleMuteEnabled = p.getBoolean(KEY_SUB_MUTE_PREFIX + profileId, false),
            customMuteWords = p.getString(KEY_SUB_WORDS_PREFIX + profileId, null) ?: "",
        )
    }

    fun setSubtitleMuteEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SUB_MUTE_PREFIX + activeProfileId, enabled)?.apply()
        state.value = state.value.copy(subtitleMuteEnabled = enabled)
    }

    fun setCustomMuteWords(words: String) {
        val clean = words.trim()
        prefs?.edit()?.putString(KEY_SUB_WORDS_PREFIX + activeProfileId, clean)?.apply()
        state.value = state.value.copy(customMuteWords = clean)
    }

    fun setQuickSeverity(severity: String) {
        prefs?.edit()?.putString(KEY_QUICK_SEVERITY, severity)?.apply()
        state.value = state.value.copy(quickSeverity = severity)
    }

    fun setLevel(category: CleanStreamCategory, level: CleanStreamLevel) {
        prefs?.edit()?.putString("level_${category.key}", level.key)?.apply()
        state.value = state.value.copy(levels = state.value.levels + (category to level))
    }
}

/**
 * Downloads skip data from the CleanStream server and turns it into [CleanSkip]s
 * according to the viewer's filter levels.
 */
object CleanStreamClient {
    private const val TAG = "CleanStream"
    private const val TIMEOUT_MS = 10_000

    private val severityRank = mapOf("low" to 1, "medium" to 2, "high" to 3)

    /**
     * Download the skips for a title from the CleanStream server. The server
     * applies the filter levels and the community trust rules.
     * @param contentId "tt1234567" for a movie or "tt1234567:season:episode" for an episode
     */
    suspend fun fetchSkips(contentId: String, config: CleanStreamConfig): CleanStreamSkips = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext CleanStreamSkips()
        if (!Regex("^tt\\d+(:\\d+:\\d+)?$").matches(contentId)) return@withContext CleanStreamSkips()
        try {
            val id = URLEncoder.encode(contentId, "UTF-8")
            val levels = JSONObject().apply { config.levels.forEach { (cat, level) -> put(cat.key, level.key) } }
            val query = URLEncoder.encode(levels.toString(), "UTF-8")
            val body = request("GET", "${config.serverUrl.trimEnd('/')}/api/skips/$id?pending=1&config=$query")
                ?: return@withContext CleanStreamSkips()
            val json = JSONObject(body)
            CleanStreamSkips(
                skips = parseList(json.optJSONArray("skips")),
                pending = parseList(json.optJSONArray("pending")),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not load skips for $contentId: ${e.message}")
            CleanStreamSkips()
        }
    }

    /** Confirm ("up") or reject ("down") a pending skip. */
    suspend fun vote(serverUrl: String, contentId: String, segmentId: String, up: Boolean): Boolean = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(contentId, "UTF-8")
        val seg = URLEncoder.encode(segmentId, "UTF-8")
        val payload = JSONObject().put("vote", if (up) "up" else "down").toString()
        request("POST", "${serverUrl.trimEnd('/')}/api/vote/$id/$seg", payload) != null
    }

    /**
     * Share a scene marked with the remote. Returns the server's status
     * ("approved" or "pending"), or null on failure.
     */
    suspend fun contribute(
        serverUrl: String,
        contentId: String,
        startMs: Long,
        endMs: Long,
        category: String,
        severity: String,
        comment: String,
    ): String? = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(contentId, "UTF-8")
        val payload = JSONObject()
            .put("startMs", startMs)
            .put("endMs", endMs)
            .put("category", category)
            .put("severity", severity)
            .put("channel", "both")
            .put("comment", comment)
            .put("contributor", "nuviotv-clean")
            .toString()
        val body = request("POST", "${serverUrl.trimEnd('/')}/api/contribute/$id", payload) ?: return@withContext null
        runCatching { JSONObject(body).optString("status", "pending") }.getOrDefault("pending")
    }

    /** Small HTTP helper: returns the body for 2xx responses, otherwise null. */
    private fun request(method: String, url: String, jsonBody: String? = null): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "NuvioTv-Clean")
            if (jsonBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "$method $url failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseList(array: org.json.JSONArray?): List<CleanSkip> {
        if (array == null) return emptyList()
        val out = mutableListOf<CleanSkip>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val start = o.optLong("startMs", -1)
            val end = o.optLong("endMs", -1)
            if (start < 0 || end <= start) continue
            val ids = o.optJSONArray("sourceIds")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOfNotNull(o.optString("id").takeIf { it.isNotBlank() })
            out += CleanSkip(
                startMs = start,
                endMs = end,
                channel = o.optString("channel", "both").ifBlank { "both" },
                label = o.optString("description").ifBlank { o.optString("category", "scene") },
                ids = ids,
            )
        }
        return out.sortedBy { it.startMs }
    }

    /** High strictness includes every severity; Low only "high" severity scenes. */
    internal fun meetsLevel(severity: String, level: CleanStreamLevel): Boolean {
        if (level.strictness == 0) return false
        val minimum = 4 - level.strictness
        return (severityRank[severity] ?: 0) >= minimum
    }

    /** Join overlapping or touching segments (within half a second). */
    internal fun merge(sorted: List<CleanSkip>): List<CleanSkip> {
        val out = mutableListOf<CleanSkip>()
        for (skip in sorted) {
            val last = out.lastOrNull()
            if (last != null && skip.startMs <= last.endMs + 500) {
                out[out.size - 1] = last.copy(
                    endMs = maxOf(last.endMs, skip.endMs),
                    channel = if (last.channel == skip.channel) last.channel else "both",
                    label = if (last.label == skip.label) last.label else "${last.label}, ${skip.label}",
                )
            } else {
                out += skip
            }
        }
        return out
    }
}
