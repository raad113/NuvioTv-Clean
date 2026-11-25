package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.cleanstream.CleanSkip
import com.nuvio.tv.core.cleanstream.CleanStreamClient
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.core.cleanstream.SubtitleProfanity
import com.nuvio.tv.core.cleanstream.TimingRef
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import kotlinx.coroutines.flow.update
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

/** Re-line-up skips at most this often when only the subtitle sync changed. */
private const val CLEAN_REALIGN_MIN_INTERVAL_MS = 60_000L

/** Wait this long after a film starts before reading its fingerprint, so the video buffers first. */
private const val CLEAN_ALIGN_START_DELAY_MS = 15_000L

/** ...unless a flagged scene starts within this time of the beginning. */
private const val CLEAN_ALIGN_EARLY_SCENE_MS = 90_000L

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
    val sync = selected?.let { cleanSubtitleSync(it.url) }
    val key = "$currentStreamUrl|${selected?.url}|${state.subtitleDelayMs}|${sync?.first}"
    if (key == cleanLocalRefKey) return cleanLocalRef

    val duration = currentPlaybackDurationMs().takeIf { it > 0 }
    val ref = embeddedTimeline(duration)
        ?: selected?.let { addonTimeline(it, state.subtitleDelayMs.toLong(), sync?.second, duration) }
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
private suspend fun PlayerRuntimeController.addonTimeline(
    subtitle: com.nuvio.tv.domain.model.Subtitle,
    delayMs: Long,
    syncedTime: ((Long) -> Long?)?,
    durationMs: Long?,
): TimingRef? {
    // The subtitle file is downloaded once per playback
    if (cleanRawCuesKey != subtitle.url) {
        cleanRawCues = SubtitleProfanity.loadCues(subtitle.url, subtitle.headers).map { it.startMs to it.endMs }
        cleanRawCuesKey = subtitle.url
    }
    // A synced subtitle (AutoSync or speech sync) gives this copy's real timing;
    // otherwise the file's own times plus the viewer's subtitle delay
    val cues = if (syncedTime != null) {
        cleanRawCues.mapNotNull { (start, end) ->
            val s = syncedTime(start) ?: return@mapNotNull null
            val e = syncedTime(end) ?: (s + (end - start))
            (s + delayMs) to (e + delayMs)
        }
    } else {
        cleanRawCues.map { (start, end) -> (start + delayMs) to (end + delayMs) }
    }
    return toTimingRef("subtitle", cues, durationMs)
}

/**
 * How a subtitle's own times map onto this copy of the film when Reshaped has
 * synced that subtitle: by AutoSync (against the film's built-in subtitles)
 * or by the speech-based sync. Returns an id of the current sync (so callers
 * notice when it changes) and the mapping, or null when the subtitle is not synced.
 */
internal fun PlayerRuntimeController.cleanSubtitleSync(subtitleUrl: String): Pair<String, (Long) -> Long?>? {
    cleanAutoSyncTimeline
        ?.takeIf { (stream, url, _) -> stream == currentStreamUrl && url == subtitleUrl }
        ?.third
        ?.let { timeline ->
            // Built only when something is actually mapped (this runs on every playback tick)
            val mapper by lazy { com.nuvio.tv.ui.screens.player.autosync.AutoSyncTimelineTimeMapper(timeline) }
            return "autosync@${System.identityHashCode(timeline)}" to { ms: Long -> mapper.mapStartMs(ms) }
        }
    cleanAudioSync
        ?.takeIf { (stream, url, _) -> stream == currentStreamUrl && url == subtitleUrl }
        ?.third
        ?.let { model ->
            return "speech@${System.identityHashCode(model)}" to { ms: Long -> model.mediaTimeUs(ms * 1_000L)?.div(1_000L) }
        }
    return null
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
    val manualOffset = cleanContentKey?.let { CleanStreamSettings.titleOffset(it) }
    // An English subtitle, to line up skips without a fingerprint by the dialogue they quote
    val english = if (cleanAnchors.isEmpty()) null
        else state.selectedAddonSubtitle?.takeIf { pickEnglishSubtitle(listOf(it)) != null } ?: pickEnglishSubtitle(state.addonSubtitles)
    val baseKey = "$cleanSkipsKey|$currentStreamUrl|${state.selectedAddonSubtitle?.url}|${state.subtitleDelayMs}|$hasDuration|$manualOffset|${english?.url}"
    val syncId = state.selectedAddonSubtitle?.url?.let { cleanSubtitleSync(it)?.first }
    val key = "$baseKey|$syncId"
    if (key == cleanAlignKey) return
    // Only the sync changed: the speech sync refines its model often, so re-align at most once a minute
    val now = android.os.SystemClock.elapsedRealtime()
    if (baseKey == cleanAlignBaseKey && now - cleanLastAlignAt < CLEAN_REALIGN_MIN_INTERVAL_MS) return
    cleanAlignBaseKey = baseKey
    cleanLastAlignAt = now
    cleanAlignKey = key
    cleanAlignJob?.cancel()
    val requestKey = cleanSkipsKey
    cleanAlignJob = launchCleanStream("cleanstream align") {
        val raw = cleanSkipsRaw
        val pendingRaw = cleanPendingRaw
        val serverUrl = CleanStreamSettings.current.serverUrl
        val duration = currentPlaybackDurationMs().takeIf { it > 0 }

        // Reading this copy's fingerprint downloads parts of the video file, so only do it
        // when something can use it: skips with a fingerprint, or shared timing corrections
        val all = raw + pendingRaw
        val anchors = cleanAnchors
        val selected = state.selectedAddonSubtitle
        // Lining up by dialogue needs this copy's timing only when the English subtitle is not the one shown
        val anchorsNeedLocal = english != null && english.url != selected?.url
        val needsLocal = manualOffset == null &&
            (all.any { it.refId != null } || (all.any { it.refId == null } && (cleanCorrections.isNotEmpty() || anchorsNeedLocal)))
        val local = if (!needsLocal) null else {
            // Let the video buffer first, unless a scene comes very early in the film
            val earliest = all.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
            if (earliest > CLEAN_ALIGN_EARLY_SCENE_MS && cleanLocalRef == null) {
                kotlinx.coroutines.delay(CLEAN_ALIGN_START_DELAY_MS)
            }
            cleanStreamLocalTimeline()
        }

        // The fingerprint of each copy the skips were marked on
        val refIds = (raw + pendingRaw).mapNotNull { it.refId }.distinct()
        val refs = refIds.mapNotNull { id -> CleanStreamClient.fetchRef(serverUrl, id)?.let { id to it } }.toMap()

        // Corrections viewers shared for skips without a fingerprint, with their copies' fingerprints
        val correctionRefs = cleanCorrections.mapNotNull { c ->
            CleanStreamClient.fetchRef(serverUrl, c.refId)?.let { c to it }
        }
        var matchedCorrection: Long? = null

        // Skips without a fingerprint quote their dialogue: find those lines in the English
        // subtitle, then follow the subtitle to this copy (as the swear-word muting does)
        val toViewer: ((Long) -> Long?)? = if (english == null || manualOffset != null || all.none { it.refId == null }) null else {
            if (cleanAnchorCuesKey != english.url) {
                cleanAnchorCues = SubtitleProfanity.loadCues(english.url, english.headers)
                cleanAnchorCuesKey = english.url
            }
            val cues = cleanAnchorCues
            val matches = withContext(AutomaticSubtitleSync.autoSyncDispatcher) { CleanStreamAnchors.match(anchors, cues) }
            cleanDiag("Dialogue: English subtitle ${english.lang}, ${cues.size} lines, ${anchors.size} words in the data, ${matches.pairs.size} possible matches" +
                if (english.url == selected?.url) " (it is the shown subtitle)" else "")
            val englishRef = cues.map { it.startMs to it.endMs }.sortedBy { it.first }.distinct()
                .takeIf { it.size >= TimingRef.MIN_CUES }
                ?.let { TimingRef("subtitle", null, it) }
            val shownSync = if (english.url == selected?.url) cleanSubtitleSync(english.url)?.second else null
            val delay = state.subtitleDelayMs.toLong()
            fun mapper(t: Long): Long? {
                val inSubtitle = CleanStreamAnchors.transformAt(matches, t)?.map(t) ?: return null
                return when {
                    // The English subtitle is the one shown: its timing on screen is this copy's
                    english.url == selected?.url -> (shownSync?.invoke(inSubtitle) ?: inSubtitle) + delay
                    local != null && englishRef != null ->
                        CleanStreamAligner.transformAt(local, englishRef, inSubtitle)?.map(inSubtitle)
                    else -> null
                }
            }
            ::mapper
        }

        /** A skip without a fingerprint placed by its dialogue, or null when that is not clear. */
        fun byDialogue(skip: CleanSkip): CleanSkip? {
            val map = toViewer ?: return null
            val start = map(skip.startMs) ?: return null
            val end = (map(skip.endMs) ?: (start + (skip.endMs - skip.startMs))).coerceAtLeast(start + 500)
            return skip.copy(startMs = start, endMs = end)
        }

        /**
         * Where a skip without a fingerprint goes on this copy, using a correction
         * shared by someone with the same copy (or one that lines up with it).
         */
        fun corrected(skip: CleanSkip): CleanSkip? {
            if (local == null) return null
            for ((correction, copy) in correctionRefs) {
                val shiftedStart = skip.startMs + correction.offsetMs
                val transform = if (copy.cues == local.cues) CleanStreamAligner.Transform(1.0, 0L)
                else CleanStreamAligner.transformAt(local, copy, shiftedStart) ?: continue
                matchedCorrection = correction.offsetMs
                val start = transform.map(shiftedStart)
                val end = transform.map(skip.endMs + correction.offsetMs).coerceAtLeast(start + 500)
                return skip.copy(startMs = start, endMs = end)
            }
            return null
        }

        // Each skip is placed by matching the lines around its own scene
        suspend fun line(skip: CleanSkip): CleanSkip {
            val ref = skip.refId?.let { refs[it] }
            val placed = withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
                if (skip.refId == null) byDialogue(skip)?.let { return@withContext it }
                val transform = (if (local != null && ref != null) CleanStreamAligner.transformAt(local, ref, skip.startMs) else null)
                    ?: CleanStreamAligner.frameRateTransform(duration, ref?.durationMs ?: skip.durationMs)
                when {
                    transform != null -> skip.copy(
                        startMs = transform.map(skip.startMs),
                        endMs = transform.map(skip.endMs).coerceAtLeast(transform.map(skip.startMs) + 500),
                    )
                    // This TV's own adjustment wins over corrections shared by others
                    skip.refId == null && manualOffset == null -> corrected(skip) ?: skip
                    else -> skip
                }
            }
            // This TV's own adjustment for the film moves everything
            val manual = manualOffset ?: return placed
            return placed.copy(
                startMs = (placed.startMs + manual).coerceAtLeast(0L),
                endMs = (placed.endMs + manual).coerceAtLeast(500L),
            )
        }

        val aligned = raw.map { line(it) }
        val alignedPending = pendingRaw.map { line(it) }
        cleanDiag("Align: this copy ${local?.let { "${it.kind}, ${it.cues.size} lines" } ?: "no fingerprint"}, ${refs.size} marked-copy fingerprints, manual adjustment ${manualOffset ?: "none"}")
        (raw + pendingRaw).zip(aligned + alignedPending).take(40).forEach { (r, a2) ->
            val how = when {
                r.refId != null && refs[r.refId] == null -> "fingerprint not downloaded"
                r.refId != null -> "by fingerprint"
                else -> "no fingerprint"
            }
            cleanDiag("  ${r.label}: ${formatDiagTime(r.startMs)} -> ${formatDiagTime(a2.startMs)} (moved ${(a2.startMs - r.startMs) / 100 / 10.0} s, $how)")
        }
        // Ignore the result if the title (or its skip list) changed meanwhile
        if (cleanSkipsKey != requestKey || cleanSkipsRaw !== raw || cleanPendingRaw !== pendingRaw) return@launchCleanStream
        cleanSkips = aligned
        cleanPending = alignedPending
        cleanMatchedCorrectionMs = matchedCorrection
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
    cleanUndoJob = launchCleanStream("cleanstream align") {
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
        launchCleanStream("cleanstream align") {
            skip.ids.forEach { id ->
                CleanStreamClient.vote(serverUrl, contentKey, id, up = false, reason = "timing")
            }
        }
    }
    showCleanStreamNotice("Reported. If the scene comes, mark it with the shield button at its start and end.")
    return true
}

/** True when the viewer turned this skip off for this playback. */
internal fun PlayerRuntimeController.isCleanSkipDisabled(skip: CleanSkip): Boolean =
    skip.ids.ifEmpty { listOf(skip.key) }.any { it in cleanDisabledIds }

/* ------------------------------------------------------------------ */
/* "Skip timing for this film" (timer button, or long press on the shield)                       */
/* ------------------------------------------------------------------ */

/** Largest adjustment offered in the panel. */
private const val CLEAN_TIMING_MAX_MS = 5 * 60_000L

/** Open the panel, starting from this TV's adjustment or a shared one that matched this copy. */
internal fun PlayerRuntimeController.openCleanStreamTiming() {
    val contentKey = cleanContentKey
    if (contentKey == null || !CleanStreamSettings.current.enabled) {
        showCleanStreamNotice("Skip timing is not available for this video")
        return
    }
    val start = CleanStreamSettings.titleOffset(contentKey) ?: cleanMatchedCorrectionMs ?: 0L
    cleanTimingAtOpen = start
    _uiState.update { it.copy(cleanTimingPanelVisible = true, cleanTimingOffsetMs = start) }
}

/** Move this film's skips by [deltaMs] (negative: earlier); applies straight away. */
internal fun PlayerRuntimeController.adjustCleanStreamTiming(deltaMs: Long) {
    val contentKey = cleanContentKey ?: return
    val value = (_uiState.value.cleanTimingOffsetMs + deltaMs).coerceIn(-CLEAN_TIMING_MAX_MS, CLEAN_TIMING_MAX_MS)
    CleanStreamSettings.setTitleOffset(contentKey, value)
    _uiState.update { it.copy(cleanTimingOffsetMs = value) }
    maybeAlignCleanStreamSkips()
}

/** Forget this TV's adjustment for the film. */
internal fun PlayerRuntimeController.resetCleanStreamTiming() {
    val contentKey = cleanContentKey ?: return
    CleanStreamSettings.setTitleOffset(contentKey, null)
    _uiState.update { it.copy(cleanTimingOffsetMs = cleanMatchedCorrectionMs ?: 0L) }
    maybeAlignCleanStreamSkips()
}

/**
 * Close the panel. A changed adjustment is shared with this copy's
 * fingerprint, so others with the same version of the film get it too.
 */
internal fun PlayerRuntimeController.closeCleanStreamTiming() {
    _uiState.update { it.copy(cleanTimingPanelVisible = false) }
    val contentKey = cleanContentKey ?: return
    val value = CleanStreamSettings.titleOffset(contentKey)
    if (value == null || value == cleanTimingAtOpen) return
    val serverUrl = CleanStreamSettings.current.serverUrl
    launchCleanStream("cleanstream align") {
        val ref = withTimeoutOrNull(15_000L) { cleanStreamLocalTimeline() }
        val shared = ref != null && CleanStreamClient.sendTiming(serverUrl, contentKey, value, ref)
        showCleanStreamNotice(
            if (shared) "Saved. Others with this version of the film get this timing too."
            else "Saved for this TV. Turn on subtitles to share the timing with others."
        )
    }
}
