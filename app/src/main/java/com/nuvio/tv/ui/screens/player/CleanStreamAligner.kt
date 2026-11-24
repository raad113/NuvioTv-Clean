package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.cleanstream.TimingRef
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncRetimedCue
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncTimelineRetimer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * NuvioTv-Clean: moves skip times from the copy of a film they were marked on
 * to the copy being watched.
 *
 * Different releases of a film (streaming, Blu-ray, PAL, extended cuts) put
 * the same scene at different times. Both copies have subtitles that follow
 * the same speech, so the pattern of subtitle line times is a fingerprint of
 * each copy. This uses Reshaped's AutoSync matcher, which compares those
 * patterns without reading the words, to pair the lines of the two copies.
 * Each skip is then moved by the shift measured around its own scene, which
 * also handles cut or added scenes and frame-rate differences.
 *
 * Kept free of Android and player state so it can be unit-tested.
 */
internal object CleanStreamAligner {

    /** How many paired lines on each side of a skip decide its local shift. */
    private const val NEIGHBOURS_EACH_SIDE = 6

    /** Only lines this close to the skip count as its neighbours. */
    private const val NEIGHBOUR_WINDOW_MS = 120_000L

    /**
     * Speed ratios between common frame rates. A copy at 25 fps made from a
     * 23.976 fps film plays about 4% faster, so every time shrinks by that ratio.
     */
    private val FRAME_RATE_RATIOS = listOf(
        23.976 / 25.0, 25.0 / 23.976,
        24.0 / 25.0, 25.0 / 24.0,
        23.976 / 24.0, 24.0 / 23.976,
    )
    private const val FRAME_RATE_TOLERANCE = 0.0025

    /**
     * Build a function that maps times on the [ref] copy to the [local] copy,
     * or null when the two fingerprints cannot be matched with confidence.
     */
    fun mapperFor(local: TimingRef, ref: TimingRef): ((Long) -> Long)? {
        if (local.cues == ref.cues) return { it } // the very same copy
        val result = runCatching {
            AutoSyncTimelineRetimer.retime(
                reference = local.toSyncCues(),
                target = ref.toSyncCues(),
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
            )
        }.getOrNull() ?: return null
        if (!result.confident || result.cues.isEmpty()) return null
        val pairs = result.cues.sortedBy { it.originalStartTimeMs }
        return { time -> mapWithNeighbours(pairs, time, result.alignmentScale, result.alignmentInterceptMs) }
    }

    /**
     * Map [time] using the median shift of the paired lines nearest to it.
     * Falls back to the whole-film transform when no line is close.
     */
    internal fun mapWithNeighbours(
        pairs: List<AutoSyncRetimedCue>,
        time: Long,
        scale: Double,
        interceptMs: Double,
    ): Long {
        // First pair at or after [time]
        var low = 0
        var high = pairs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pairs[mid].originalStartTimeMs < time) low = mid + 1 else high = mid
        }
        val shifts = mutableListOf<Long>()
        for (i in (low - NEIGHBOURS_EACH_SIDE).coerceAtLeast(0) until (low + NEIGHBOURS_EACH_SIDE).coerceAtMost(pairs.size)) {
            val pair = pairs[i]
            if (abs(pair.originalStartTimeMs - time) <= NEIGHBOUR_WINDOW_MS) {
                shifts += pair.startTimeMs - pair.originalStartTimeMs
            }
        }
        if (shifts.size < 3) {
            return (time * scale + interceptMs).roundToLong().coerceAtLeast(0L)
        }
        shifts.sort()
        return (time + shifts[shifts.size / 2]).coerceAtLeast(0L)
    }

    /**
     * When no fingerprint can be compared, a frame-rate difference can still be
     * recognised from the two copies' lengths. Returns the mapping, or null when
     * the lengths match (nothing to do) or differ for another reason (unknown).
     */
    fun frameRateMapper(localDurationMs: Long?, refDurationMs: Long?): ((Long) -> Long)? {
        if (localDurationMs == null || refDurationMs == null || localDurationMs <= 0 || refDurationMs <= 0) return null
        val ratio = localDurationMs.toDouble() / refDurationMs.toDouble()
        val known = FRAME_RATE_RATIOS.any { abs(ratio - it) / it < FRAME_RATE_TOLERANCE }
        if (!known) return null
        return { time -> (time * ratio).roundToLong() }
    }

    private fun TimingRef.toSyncCues(): List<SubtitleSyncCue> =
        cues.map { (start, end) -> SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = "") }
}
