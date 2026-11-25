package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.cleanstream.DialogueAnchor
import com.nuvio.tv.core.cleanstream.SubtitleProfanity
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * NuvioTv-Clean: lines up skips that have no timing fingerprint (for example
 * imported VideoSkip data) by their dialogue.
 *
 * Imported strong-language entries name the word spoken (e.g. "damn"), and
 * the same word is in the English subtitle. Each line containing it says
 * "maybe, at the skip data's time t, this line was on screen", which allows a
 * range of shifts. One word fits many lines, but a run of words at their
 * known gaps fits the subtitle at only one timing: the shift allowed by the
 * most anchors at once is the difference between the skip data's copy and
 * the subtitle. As elsewhere, this is worked out around each scene from the
 * nearest matches, so cut scenes and frame-rate changes are handled; with too
 * few clear matches nothing is moved.
 *
 * Kept free of Android and player state so it can be unit-tested.
 */
internal object CleanStreamAnchors {

    /** Matches within this distance (in the skip data's time) describe a scene's surroundings. */
    private val WINDOWS_MS = listOf(15 * 60_000L, 40 * 60_000L)
    private const val MIN_AGREEING = 4

    /** ...and at least this share of the matched anchors nearby (real data agrees almost entirely). */
    private const val MIN_AGREEING_SHARE = 0.5
    private const val LEAD_OVER_RUNNER_UP = 1.5
    private const val SAME_SHIFT_MS = 3_000L

    /** Slack around a subtitle line, for slightly different line timing. */
    private const val LINE_SLACK_MS = 400L

    /**
     * Only subtitle lines this close to an anchor's own time are considered
     * (the largest shift between two releases, plus frame-rate drift).
     */
    private const val MAX_SHIFT_MS = 25 * 60_000L

    private val SCALES = listOf(1.0, 23.976 / 25.0, 25.0 / 23.976, 24.0 / 25.0, 25.0 / 24.0)

    /** A matched pair: anchor time in the skip data, and the subtitle line it was found in. */
    private data class Pair3(val anchor: Int, val timeMs: Long, val lineStart: Long, val lineEnd: Long)

    /**
     * Prepare the matches between [anchors] and the subtitle [cues] once per
     * title; [transformAt] then answers for each scene.
     */
    class Matches internal constructor(internal val pairs: List<Any>)

    fun match(anchors: List<DialogueAnchor>, cues: List<SubtitleProfanity.Cue>): Matches {
        val sorted = cues.sortedBy { it.startMs }
        val starts = sorted.map { it.startMs }
        val cueWords = sorted.map { words(it.text) }
        val pairs = mutableListOf<Pair3>()
        anchors.forEachIndexed { index, anchor ->
            val pattern = anchorPattern(anchor.text) ?: return@forEachIndexed
            // One word (the usual case, e.g. "damn") can be in many lines; each is a
            // possible place, and only the true timing lines most anchors up at once
            var c = lowerBound(starts, anchor.timeMs - MAX_SHIFT_MS)
            while (c < sorted.size && sorted[c].startMs <= anchor.timeMs + MAX_SHIFT_MS) {
                if (containsSequence(cueWords[c], pattern)) pairs += Pair3(index, anchor.timeMs, sorted[c].startMs, sorted[c].endMs)
                c++
            }
        }
        return Matches(pairs)
    }

    /**
     * Where a scene at [timeMs] in the skip data's copy is in the subtitle's
     * timing, or null when the dialogue does not tell clearly.
     */
    fun transformAt(matches: Matches, timeMs: Long): CleanStreamAligner.Transform? {
        @Suppress("UNCHECKED_CAST")
        val all = matches.pairs as List<Pair3>
        if (all.isEmpty()) return null
        for (window in WINDOWS_MS) {
            val near = all.filter { abs(it.timeMs - timeMs) <= window }
            bestShift(near, timeMs)?.let { return it }
        }
        return null
    }

    /** The scale and shift allowed by the most anchors at once, if clearly ahead of any other. */
    private fun bestShift(pairs: List<Pair3>, timeMs: Long): CleanStreamAligner.Transform? {
        val anchorCount = pairs.map { it.anchor }.distinct().size
        if (anchorCount < MIN_AGREEING) return null
        val candidates = mutableListOf<Candidate>()
        for (scale in SCALES) {
            // Each pair allows shifts in [lineStart - t*scale, lineEnd - t*scale] (with slack)
            val events = mutableListOf<Triple<Long, Int, Int>>() // position, +1/-1, anchor
            for (p in pairs) {
                val scaled = (p.timeMs * scale).roundToLong()
                events += Triple(p.lineStart - LINE_SLACK_MS - scaled, +1, p.anchor)
                events += Triple(p.lineEnd + LINE_SLACK_MS - scaled, -1, p.anchor)
            }
            events.sortWith(compareBy<Triple<Long, Int, Int>> { it.first }.thenBy { -it.second })
            // Sweep: how many different anchors allow each stretch of shifts
            val open = HashMap<Int, Int>()
            val stretches = mutableListOf<Triple<Long, Long, Int>>() // from, to, anchors
            for ((i, e) in events.withIndex()) {
                if (e.second > 0) open[e.third] = (open[e.third] ?: 0) + 1
                else open[e.third]?.let { if (it <= 1) open.remove(e.third) else open[e.third] = it - 1 }
                val next = events.getOrNull(i + 1)?.first ?: continue
                if (next > e.first && open.isNotEmpty()) stretches += Triple(e.first, next, open.size)
            }
            val top = stretches.maxByOrNull { it.third } ?: continue
            // The middle of the best stretch (joined with neighbouring stretches of the same count)
            val same = stretches.filter { it.third == top.third && it.second >= top.first - SAME_SHIFT_MS && it.first <= top.second + SAME_SHIFT_MS }
            val shift = (same.minOf { it.first } + same.maxOf { it.second }) / 2
            candidates += Candidate(scale, shift, top.third)
            // The best shift clearly apart from it, to judge how clear the winner is
            stretches.filter { it.second < shift - SAME_SHIFT_MS || it.first > shift + SAME_SHIFT_MS }
                .maxByOrNull { it.third }
                ?.let { candidates += Candidate(scale, (it.first + it.second) / 2, it.third) }
        }
        val winner = candidates.maxByOrNull { it.anchors } ?: return null
        if (winner.anchors < maxOf(MIN_AGREEING, (anchorCount * MIN_AGREEING_SHARE).toInt())) return null
        // Nearly equal frame rates that put the scene in the same place agree with the winner
        val winnerAt = winner.scale * timeMs + winner.shift
        val runnerUp = candidates
            .filter { abs(it.scale * timeMs + it.shift - winnerAt) > 2_000.0 }
            .maxOfOrNull { it.anchors } ?: 0
        if (winner.anchors < runnerUp * LEAD_OVER_RUNNER_UP) return null
        return CleanStreamAligner.Transform(winner.scale, winner.shift)
    }

    private data class Candidate(val scale: Double, val shift: Long, val anchors: Int)

    /** Lower-case words, letters and digits only ("Don't!" becomes "dont"). */
    private fun words(text: String): List<String> =
        text.lowercase()
            .replace(Regex("<[^>]*>|\\{[^}]*\\}"), " ")
            .replace("'", "")
            .split(Regex("[^\\p{L}\\p{N}*]+"))
            .filter { it.isNotBlank() }

    /**
     * Word patterns for an anchor's text. Censored letters ("h*ll") match any
     * one letter. A single word also matches longer forms ("fuck" in "fucking",
     * "damn" in "goddamn"). Too little text to be telling gives null.
     */
    private fun anchorPattern(text: String): List<Regex>? {
        val list = words(text.replace(Regex("\\([^)]*\\)"), " ")).filter { w -> w.any { it.isLetter() } }
        val letters = list.sumOf { w -> w.count { it.isLetter() } }
        if (list.isEmpty() || letters < 4) return null
        val parts = list.map { w -> w.split('*').joinToString("\\p{L}") { Regex.escape(it) } }
        // "fucking", "damned", "shitty", "goddamn", "bullshit", but not "hello" or "shell"
        return if (parts.size == 1) listOf(Regex("(?:$PREFIXES)?" + parts[0] + "(?:$SUFFIXES)?")) else parts.map { Regex(it) }
    }

    private const val PREFIXES = "god|mother|motha|bull|horse|chicken|dip|holy|ape|dumb"
    private const val SUFFIXES = "s|es|ed|er|ers|ing|in|y|ty|tiest|ting|head|heads|hole|holes|face|it|ass|bag"

    private fun lowerBound(values: List<Long>, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /** True when [pattern] appears in [words] as consecutive words. */
    private fun containsSequence(words: List<String>, pattern: List<Regex>): Boolean {
        if (pattern.size > words.size) return false
        for (start in 0..words.size - pattern.size) {
            if (pattern.indices.all { pattern[it].matches(words[start + it]) }) return true
        }
        return false
    }
}
