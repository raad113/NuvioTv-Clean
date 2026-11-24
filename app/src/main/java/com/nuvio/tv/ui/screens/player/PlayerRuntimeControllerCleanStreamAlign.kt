package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.cleanstream.CleanSkip
import com.nuvio.tv.core.cleanstream.CleanStreamClient
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.core.cleanstream.SubtitleProfanity
import com.nuvio.tv.core.cleanstream.TimingRef
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * NuvioTv-Clean: lining skips up with the copy of the film being watched.
 *
 * Each skip may carry the timing fingerprint of the copy it was marked on.
 * This file reads the same kind of fingerprint from the current copy and moves
 * every skip to where its scene is in this copy (see CleanStreamAligner).
 */

/** Show "lined up" only when a skip moved at least this much. */
private const val ALIGN_NOTICE_MIN_SHIFT_MS = 1_000L

/**
 * The fingerprint of the copy being watched: subtitle line times from the
 * subtitles inside the video file, or else from the add-on subtitle the viewer
 * selected (as shown, including the subtitle delay). Null when neither exists.
 */
internal suspend fun PlayerRuntimeController.cleanStreamLocalTimeline(): TimingRef? {
    val state = _uiState.value
    val selected = state.selectedAddonSubtitle
    val key = "$currentStreamUrl|${selected?.url}|${state.subtitleDelayMs}"
    if (key == cleanLocalRefKey) return cleanLocalRef

    val duration = currentPlaybackDurationMs().takeIf { it > 0 }
    val ref = embeddedTimeline(duration) ?: selected?.let { addonTimeline(it, state.subtitleDelayMs.toLong(), duration) }
    // Only remember a result once the video length is known, so it is complete
    if (duration != null) {
        cleanLocalRefKey = key
        cleanLocalRef = ref
    }
    return ref
}

/** Line times of the main embedded subtitle track (the one with the most lines, not forced). */
private suspend fun PlayerRuntimeController.embeddedTimeline(durationMs: Long?): TimingRef? {
    val timeline = withTimeoutOrNull(25_000L) {
        EmbeddedSubtitleTimelineLoader.load(currentStreamUrl, currentHeaders)
    } ?: return null
    val track = timeline.tracks
        .filter { !it.sampled && (it.selectionFlags and C.SELECTION_FLAG_FORCED) == 0 }
        .filterNot { AutomaticSubtitleSync.isSdhReferenceTrack(it) && timeline.tracks.size > 1 }
        .maxByOrNull { it.cues.size }
        ?: return null
    return toTimingRef("embedded", track.cues.map { it.startTimeMs to it.endTimeMs }, durationMs)
}

/** Line times of the add-on subtitle the viewer chose, shifted by their subtitle delay. */
private suspend fun addonTimeline(
    subtitle: com.nuvio.tv.domain.model.Subtitle,
    delayMs: Long,
    durationMs: Long?,
): TimingRef? {
    val cues = SubtitleProfanity.loadCues(subtitle.url, subtitle.headers)
    return toTimingRef("subtitle", cues.map { (it.startMs + delayMs) to (it.endMs + delayMs) }, durationMs)
}

private fun toTimingRef(kind: String, raw: List<Pair<Long, Long>>, durationMs: Long?): TimingRef? {
    val cues = raw
        .filter { (start, end) -> start >= 0 && end > start }
        .sortedBy { it.first }
        .distinct()
        .take(TimingRef.MAX_CUES)
    if (cues.size < TimingRef.MIN_CUES) return null
    return TimingRef(kind = kind, durationMs = durationMs, cues = cues)
}

/**
 * Called on every progress tick. Starts lining the skips up again when what
 * it depends on changes: the stream, the selected subtitle, the subtitle
 * delay, or the video length becoming known.
 */
internal fun PlayerRuntimeController.maybeAlignCleanStreamSkips() {
    if (cleanSkipsRaw.isEmpty() && cleanPendingRaw.isEmpty()) return
    val state = _uiState.value
    val hasDuration = currentPlaybackDurationMs() > 0
    val key = "$cleanSkipsKey|$currentStreamUrl|${state.selectedAddonSubtitle?.url}|${state.subtitleDelayMs}|$hasDuration"
    if (key == cleanAlignKey) return
    cleanAlignKey = key
    cleanAlignJob?.cancel()
    cleanAlignJob = scope.launch {
        val raw = cleanSkipsRaw
        val pendingRaw = cleanPendingRaw
        val serverUrl = CleanStreamSettings.current.serverUrl
        val duration = currentPlaybackDurationMs().takeIf { it > 0 }
        val local = cleanStreamLocalTimeline()

        // The fingerprint of each copy the skips were marked on
        val refIds = (raw + pendingRaw).mapNotNull { it.refId }.distinct()
        val refs = refIds.mapNotNull { id -> CleanStreamClient.fetchRef(serverUrl, id)?.let { id to it } }.toMap()

        // Each skip is placed by matching the lines around its own scene
        suspend fun line(skip: CleanSkip): CleanSkip {
            val ref = skip.refId?.let { refs[it] }
            val transform = withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
                (if (local != null && ref != null) CleanStreamAligner.transformAt(local, ref, skip.startMs) else null)
                    ?: CleanStreamAligner.frameRateTransform(duration, ref?.durationMs ?: skip.durationMs)
            } ?: return skip
            val start = transform.map(skip.startMs)
            val end = transform.map(skip.endMs).coerceAtLeast(start + 500)
            return skip.copy(startMs = start, endMs = end)
        }

        val aligned = raw.map { line(it) }
        val alignedPending = pendingRaw.map { line(it) }
        // Ignore the result if the title changed meanwhile
        if (cleanSkipsRaw !== raw) return@launch
        cleanSkips = aligned
        cleanPending = alignedPending
        recomputeMergedSkipIntervals()
        val moved = aligned.zip(raw).maxOfOrNull { (a, r) -> abs(a.startMs - r.startMs) } ?: 0L
        if (moved >= ALIGN_NOTICE_MIN_SHIFT_MS) {
            showCleanStreamNotice("Skip times lined up with this version of the film")
        }
    }
}

/* ------------------------------------------------------------------ */
/* "Wrong skip? Go back"                                               */
/* ------------------------------------------------------------------ */

/** Shown for a few seconds after an automatic skip, as Nuvio's skip button. */
internal data class CleanStreamUndo(val skip: CleanSkip, val interval: com.nuvio.tv.data.repository.SkipInterval)

internal const val CLEANSTREAM_UNDO_TYPE = "cleanstream-undo"
private const val CLEANSTREAM_UNDO_PROVIDER = "cleanstream-undo"
private const val CLEANSTREAM_UNDO_SHOW_MS = 8_000L
private const val CLEANSTREAM_UNDO_REWIND_MS = 3_000L

/** Offer "Wrong skip? Go back" right after [skip] was skipped and playback landed at [landingMs]. */
internal fun PlayerRuntimeController.offerCleanStreamUndo(skip: CleanSkip, landingMs: Long) {
    val interval = com.nuvio.tv.data.repository.SkipInterval(
        startTime = (landingMs - 500).coerceAtLeast(0L) / 1000.0,
        endTime = (landingMs + CLEANSTREAM_UNDO_SHOW_MS) / 1000.0,
        type = CLEANSTREAM_UNDO_TYPE,
        provider = CLEANSTREAM_UNDO_PROVIDER,
    )
    cleanUndo = CleanStreamUndo(skip, interval)
    recomputeMergedSkipIntervals()
    cleanUndoJob?.cancel()
    cleanUndoJob = scope.launch {
        kotlinx.coroutines.delay(CLEANSTREAM_UNDO_SHOW_MS + 1_000L)
        if (cleanUndo?.interval === interval) {
            cleanUndo = null
            recomputeMergedSkipIntervals()
        }
    }
}

/** The undo offer as a skip interval, if one is showing. */
internal fun PlayerRuntimeController.cleanStreamUndoIntervals(): List<com.nuvio.tv.data.repository.SkipInterval> =
    listOfNotNull(cleanUndo?.interval)

/**
 * The viewer pressed "Wrong skip? Go back": go back to just before the scene,
 * stop skipping it for this playback and report it. Returns true when handled.
 */
internal fun PlayerRuntimeController.onCleanStreamUndoPressed(interval: com.nuvio.tv.data.repository.SkipInterval): Boolean {
    if (interval.type != CLEANSTREAM_UNDO_TYPE) return false
    val undo = cleanUndo ?: return true
    cleanUndo = null
    cleanUndoJob?.cancel()
    val skip = undo.skip
    cleanDisabledIds += skip.ids.ifEmpty { listOf(skip.key) }
    recomputeMergedSkipIntervals()

    val target = (skip.startMs - CLEANSTREAM_UNDO_REWIND_MS).coerceAtLeast(0L)
    seekPlaybackTo(target, androidx.media3.exoplayer.SeekParameters.EXACT, exact = true)
    scheduleProgressSyncAfterSeek()

    val contentKey = cleanContentKey
    val serverUrl = CleanStreamSettings.current.serverUrl
    if (contentKey != null) {
        scope.launch {
            skip.ids.forEach { id ->
                CleanStreamClient.vote(serverUrl, contentKey, id, up = false, reason = "timing")
            }
        }
    }
    showCleanStreamNotice("Reported. If the scene comes, mark it with ♥ at its start and end.")
    return true
}

/** True when the viewer turned this skip off for this playback. */
internal fun PlayerRuntimeController.isCleanSkipDisabled(skip: CleanSkip): Boolean =
    skip.ids.ifEmpty { listOf(skip.key) }.any { it in cleanDisabledIds }
