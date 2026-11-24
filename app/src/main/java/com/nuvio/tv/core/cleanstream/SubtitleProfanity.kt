package com.nuvio.tv.core.cleanstream

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * NuvioTv-Clean: finds strong language in a subtitle file so the player can
 * mute it. Works on SRT and WebVTT files from subtitle add-ons, with a
 * built-in English and Arabic word list plus the viewer's own words.
 */
object SubtitleProfanity {
    private const val TAG = "SubtitleProfanity"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 3 * 1024 * 1024

    /**
     * Built-in words. English entries are stems: "fuck" also matches "fucking".
     * Mild words are left out on purpose; viewers can add their own in settings.
     */
    private val ENGLISH_STEMS = listOf(
        "fuck", "motherfuck", "shit", "bullshit", "bitch", "bastard", "asshole",
        "cunt", "goddamn", "whore", "slut", "pussy", "nigger", "nigga", "faggot", "wanker",
    )
    private val ARABIC_WORDS = listOf(
        "شرموط", "شرموطة", "شرموطه", "قحبة", "قحبه", "منيوك", "منيك", "عرص", "خول",
        "كس امك", "كس أمك", "يلعن", "زب", "طيز",
    )

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Download a subtitle file and return the stretches to mute.
     * @param extraWords the viewer's own words, separated by commas
     */
    suspend fun loadMutes(url: String, headers: Map<String, String>?, extraWords: String): List<CleanSkip> =
        withContext(Dispatchers.IO) {
            try {
                val text = download(url, headers) ?: return@withContext emptyList()
                findMutes(parseCues(text), extraWords)
            } catch (e: Exception) {
                Log.w(TAG, "Could not scan subtitles: ${e.message}")
                emptyList()
            }
        }

    /** Download a subtitle file and return all its lines (empty on failure). */
    suspend fun loadCues(url: String, headers: Map<String, String>?): List<Cue> =
        withContext(Dispatchers.IO) {
            try {
                download(url, headers)?.let(::parseCues) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read subtitles: ${e.message}")
                emptyList()
            }
        }

    /** Cues that contain strong language, as audio-only skips. */
    fun findMutes(cues: List<Cue>, extraWords: String): List<CleanSkip> {
        val pattern = buildPattern(extraWords)
        return cues
            .filter { pattern.matcher(clean(it.text)).find() }
            .map { CleanSkip(startMs = it.startMs, endMs = it.endMs, channel = "audio", label = "Strong language") }
    }

    internal fun buildPattern(extraWords: String): Pattern {
        val extra = extraWords.split(',', '،', '\n').map { it.trim() }.filter { it.length >= 2 }
        val english = ENGLISH_STEMS.joinToString("|") { Pattern.quote(it) + "\\w*" }
        val exact = (ARABIC_WORDS + extra).joinToString("|") { Pattern.quote(it) }
        // Word boundaries that understand Arabic letters too
        return Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:$english|$exact)(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS
        )
    }

    /** Remove formatting such as <i>, {\an8} and Arabic diacritics before matching. */
    private fun clean(text: String): String =
        text.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\{[^}]*\\}"), " ")
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace(Regex("[أإآ]"), "ا")

    /** Parse SRT or WebVTT into cues. */
    fun parseCues(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val time = Regex("""(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.trim().lines()
            val timeIndex = lines.indexOfFirst { time.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val m = time.find(lines[timeIndex]) ?: continue
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            if (end <= start) continue
            cues += Cue(start, end, lines.drop(timeIndex + 1).joinToString(" "))
        }
        return cues
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        val hours = h.trimEnd(':').toLongOrNull() ?: 0L
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
    }

    private fun download(url: String, headers: Map<String, String>?): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NuvioTv-Clean")
            headers?.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            decode(gunzipIfNeeded(bytes))
        } finally {
            connection.disconnect()
        }
    }

    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else bytes

    /** UTF-8 first; Arabic subtitles are often Windows-1256, older English ones Windows-1252. */
    private fun decode(bytes: ByteArray): String {
        for (name in listOf("UTF-8", "windows-1256", "windows-1252")) {
            try {
                val decoder = Charset.forName(name).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
            } catch (_: CharacterCodingException) {
                // try the next encoding
            } catch (_: Exception) {
                // charset not available on this device
            }
        }
        return String(bytes, Charsets.UTF_8)
    }
}
