package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.cleanstream.TimingRef
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * NuvioTv-Clean: moves skip times from the copy of a film they were marked on
 * to the copy being watched.
 *
 * Different releases of a film (streaming, Blu-ray, PAL, extended cuts) put
 * the same scene at different times. Both copies have subtitles that follow
 * the same speech, so the pattern of subtitle line times is a fingerprint of
 * each copy. This is the idea behind Reshaped's AutoSync, applied locally:
 * for each skip, the lines from a few minutes around it in the marked copy
 * are compared with all lines of the watched copy, and the shift (and
 * frame-rate factor) at which most of them line up is used. Working around
 * each scene separately means added or cut scenes elsewhere do not matter.
 * Only the line times are used, never the words, so the two subtitle files
 * may be in different languages.
 *
 * Kept free of Android and player state so it can be unit-tested.
 */
internal object CleanStreamAligner {

    /** A way to move times from the marked copy to the watched copy. */
    data class Transform(val scale: Double, val offsetMs: Long) {
        fun map(timeMs: Long): Long = (timeMs * scale + offsetMs).roundToLong().coerceAtLeast(0L)
    }

    /**
     * Lines within this distance of a skip describe its surroundings. The
     * narrowest window with enough lines and a clear answer is used, so a cut
     * or added scene close by disturbs as little as possible.
     */
    private val WINDOWS_MS = listOf(90_000L, 4 * 60_000L, 8 * 60_000L)
    private const val MIN_WINDOW_LINES = 15

    /** Two line starts closer than this count as the same line. */
    private const val MATCH_TOLERANCE_MS = 400L
    private const val VOTE_BIN_MS = 200L

    /** Largest shift considered between two copies (added logos, intros, cut scenes). */
    private const val MAX_SHIFT_MS = 20 * 60_000L

    /** At least this share of the surrounding lines must line up... */
    private const val MIN_MATCH_SHARE = 0.5
    private const val MIN_MATCHES = 10

    /** ...and clearly more than with any other shift (counting only matches above chance). */
    private const val MIN_LEAD_OVER_RUNNER_UP = 1.5

    /**
     * Speed ratios between common frame rates. A copy at 25 fps made from a
     * 23.976 fps film plays about 4% faster, so every time shrinks by that ratio.
     */
    private val SCALES = listOf(
        1.0,
        23.976 / 25.0, 25.0 / 23.976,
        24.0 / 25.0, 25.0 / 24.0,
    )
    private const val FRAME_RATE_TOLERANCE = 0.003

    /**
     * Where a scene at [timeMs] in the [ref] copy is in the [local] copy, as a
     * transform for that scene, or null when it cannot be told with confidence.
     */
    fun transformAt(local: TimingRef, ref: TimingRef, timeMs: Long): Transform? {
        if (local.cues == ref.cues) return Transform(1.0, 0L) // the very same copy
        val localStarts = local.cues.map { it.first }.sorted().toLongArray()
        if (localStarts.size < MIN_WINDOW_LINES) return null
        val refStarts = ref.cues.map { it.first }
        for (windowMs in WINDOWS_MS) {
            val window = refStarts.filter { abs(it - timeMs) <= windowMs }
            if (window.size < MIN_WINDOW_LINES) continue
            transformInWindow(window, localStarts, timeMs)?.let { return it }
        }
        return null
    }

    private fun transformInWindow(window: List<Long>, localStarts: LongArray, timeMs: Long): Transform? {
        // For each frame-rate factor, every pairing of a nearby line with a
        // line of the watched copy votes for a shift; the true shift collects
        // most votes because most lines agree on it.
        val candidates = mutableListOf<Candidate>()
        for (scale in SCALES) {
            // Every pairing of a nearby line with a line of the watched copy (within
            // MAX_SHIFT_MS) votes for a shift; the true shift collects most votes
            val bins = ArrayList<Long>(window.size * 64)
            for (r in window) {
                val scaled = r * scale
                val from = lowerBound(localStarts, (scaled - MAX_SHIFT_MS).roundToLong())
                val to = lowerBound(localStarts, (scaled + MAX_SHIFT_MS).roundToLong())
                for (i in from until to) bins += ((localStarts[i] - scaled) / VOTE_BIN_MS).roundToLong()
            }
            if (bins.isEmpty()) continue
            val sorted = bins.toLongArray().also { it.sort() }
            // Count each bin, then score it together with its neighbours
            val keys = ArrayList<Long>()
            val counts = ArrayList<Int>()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j < sorted.size && sorted[j] == sorted[i]) j++
                keys += sorted[i]; counts += j - i
                i = j
            }
            fun countAt(k: Int) = if (k in keys.indices) counts[k] else 0
            val scores = keys.indices.map { k ->
                var score = counts[k]
                if (k > 0 && keys[k - 1] == keys[k] - 1) score += countAt(k - 1)
                if (k + 1 < keys.size && keys[k + 1] == keys[k] + 1) score += countAt(k + 1)
                keys[k] to score
            }.sortedByDescending { it.second }
            val top = scores.first()
            // The best shift, and the best one clearly apart from it (to judge how clear the winner is)
            listOfNotNull(top, scores.firstOrNull { abs(it.first - top.first) * VOTE_BIN_MS > 2_000L }).forEach { (bin, _) ->
                val offset = bin * VOTE_BIN_MS
                candidates += Candidate(scale, offset, countMatches(window, localStarts, scale, offset))
            }
        }
        val winner = candidates.maxByOrNull { it.matches } ?: return null
        // Candidates that put the scene somewhere else compete with the winner;
        // nearly equal frame rates that put it in the same place agree with it
        val winnerAt = winner.scale * timeMs + winner.offsetMs
        val runnerUp = candidates
            .filter { abs(it.scale * timeMs + it.offsetMs - winnerAt) > 2_000.0 }
            .maxOfOrNull { it.matches } ?: 0
        val needed = maxOf(MIN_MATCHES, (window.size * MIN_MATCH_SHARE).toInt())
        if (winner.matches < needed) return null
        // Some lines line up by chance at any shift; the lead must stand out above that
        val spanMs = (localStarts.last() - localStarts.first()).coerceAtLeast(1L)
        val chancePerLine = (2.0 * MATCH_TOLERANCE_MS * localStarts.size / spanMs).coerceAtMost(1.0)
        val chance = window.size * chancePerLine
        if (winner.matches - chance < (runnerUp - chance).coerceAtLeast(0.0) * MIN_LEAD_OVER_RUNNER_UP) return null

        // Refine the shift with the median of the matched pairs
        val residuals = window.mapNotNull { r ->
            val expected = r * winner.scale + winner.offsetMs
            val nearest = nearest(localStarts, expected.roundToLong())
            (nearest - expected).takeIf { abs(it) <= MATCH_TOLERANCE_MS }
        }.sorted()
        val refined = winner.offsetMs + (residuals.getOrNull(residuals.size / 2) ?: 0.0).roundToLong()
        return Transform(winner.scale, refined)
    }

    private data class Candidate(val scale: Double, val offsetMs: Long, val matches: Int)

    private fun countMatches(window: List<Long>, localStarts: LongArray, scale: Double, offsetMs: Long): Int =
        window.count { r ->
            val expected = (r * scale + offsetMs).roundToLong()
            abs(nearest(localStarts, expected) - expected) <= MATCH_TOLERANCE_MS
        }

    /** Index of the first value in sorted [values] that is not below [target]. */
    private fun lowerBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /** The value in sorted [values] closest to [target]. */
    private fun nearest(values: LongArray, target: Long): Long {
        var low = 0
        var high = values.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        val after = values[low]
        val before = if (low > 0) values[low - 1] else after
        return if (abs(after - target) < abs(target - before)) after else before
    }

    /**
     * When no fingerprint can be compared, a frame-rate difference can still be
     * recognised from the two copies' lengths. Returns the transform, or null
     * when the lengths match (nothing to do) or differ for another reason.
     */
    fun frameRateTransform(localDurationMs: Long?, refDurationMs: Long?): Transform? {
        if (localDurationMs == null || refDurationMs == null || localDurationMs <= 0 || refDurationMs <= 0) return null
        val ratio = localDurationMs.toDouble() / refDurationMs.toDouble()
        val scale = SCALES.drop(1).firstOrNull { abs(ratio - it) / it < FRAME_RATE_TOLERANCE } ?: return null
        return Transform(scale, 0L)
    }
}
