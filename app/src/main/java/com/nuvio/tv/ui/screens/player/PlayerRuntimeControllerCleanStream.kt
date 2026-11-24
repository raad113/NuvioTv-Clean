package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.exoplayer.SeekParameters
import com.nuvio.tv.core.cleanstream.CleanSkip
import com.nuvio.tv.core.cleanstream.CleanStreamClient
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.core.cleanstream.SubtitleProfanity
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * NuvioTv-Clean: automatic skipping of flagged scenes.
 *
 * fetchCleanStreamSkips() runs whenever the player loads skip data for a title.
 * applyCleanStreamSkips() runs on every progress tick (about twice a second)
 * and either seeks past the current flagged scene or mutes it when the scene
 * is marked "audio" (e.g. strong language).
 */

/** Ignore a second jump this soon after the first, in case the player refuses the seek. */
private const val CLEAN_SEEK_GUARD_MS = 1_500L

/** Stop this long before the end of a scene: we land just after its end anyway. */
private const val CLEAN_END_MARGIN_MS = 300L

private const val CLEAN_NOTICE_MS = 3_000L

/** Extra quiet time around a subtitle line that contains strong language. */
private const val SUBTITLE_MUTE_PAD_MS = 250L

/** The "Skip" button appears this long before a flagged scene starts. */
private const val CLEAN_BUTTON_LEAD_MS = 10_000L

/** Time to cancel a ♥ mark before it is shared. */
private const val CLEAN_SHARE_DELAY_MS = 5_000L

/** Skip-interval type prefix for unconfirmed scenes; the rest of the type is the scene label. */
internal const val CLEANSTREAM_PROMPT_TYPE_PREFIX = "cleanstream:"
private const val CLEANSTREAM_PROVIDER_PREFIX = "cleanstream:"

/** Skip-interval type prefix for confirmed scenes shown as a button; the rest is the scene label. */
internal const val CLEANSTREAM_BUTTON_TYPE_PREFIX = "cleanstream-skip:"
private const val CLEANSTREAM_BUTTON_PROVIDER_PREFIX = "cleanstream-skip:"

/**
 * Load the CleanStream skip list for the current movie or episode.
 * Safe to call repeatedly: it only downloads again when the title changes.
 */
internal fun PlayerRuntimeController.fetchCleanStreamSkips(id: String?, season: Int?, episode: Int?) {
    val config = CleanStreamSettings.config(context).value
    val contentKey = cleanStreamContentId(id, season, episode)
    if (!config.enabled || contentKey == null) {
        cleanSkips = emptyList()
        cleanPending = emptyList()
        cleanSkipsRaw = emptyList()
        cleanPendingRaw = emptyList()
        cleanSkipsKey = null
        cleanContentKey = null
        recomputeMergedSkipIntervals()
        return
    }
    val requestKey = "$contentKey|${config.serverUrl}|${config.levels.values.joinToString { it.key }}"
    if (requestKey == cleanSkipsKey) return
    cleanSkipsKey = requestKey
    cleanContentKey = contentKey
    cleanSkips = emptyList()
    cleanPending = emptyList()
    cleanSkipsRaw = emptyList()
    cleanPendingRaw = emptyList()
    cleanAlignKey = null
    cleanDisabledIds.clear()
    cleanUndo = null
    cleanSubtitleSourceKey = null
    cleanSubtitleMutes = emptyList()
    releaseCleanStreamMute()

    scope.launch {
        val result = withTimeoutOrNull(20_000L) { CleanStreamClient.fetchSkips(contentKey, config) }
            ?: com.nuvio.tv.core.cleanstream.CleanStreamSkips()
        // The viewer may have moved to another title while this was loading
        if (cleanSkipsKey == requestKey) {
            val skips = result.skips
            // Used as they are until they are lined up with this copy (maybeAlignCleanStreamSkips)
            cleanSkipsRaw = skips
            cleanPendingRaw = result.pending
            cleanSkips = skips
            cleanPending = result.pending
            cleanAlignKey = null
            recomputeMergedSkipIntervals()
            if (skips.isNotEmpty()) {
                showCleanStreamNotice("Nuvio Clean: ${skips.size} scene${if (skips.size == 1) "" else "s"} will be skipped")
            }
        }
    }
}

/**
 * The id the CleanStream server uses: tt1234567 for movies and
 * tt1234567:season:episode for episodes.
 */
private fun PlayerRuntimeController.cleanStreamContentId(id: String?, season: Int?, episode: Int?): String? {
    val videoId = currentVideoId?.takeIf { it.isNotBlank() }
    val isMovie = contentType.equals("movie", ignoreCase = true)

    val imdbId = listOfNotNull(videoId, id)
        .firstNotNullOfOrNull { candidate -> candidate.substringBefore(':').takeIf { it.matches(Regex("tt\\d+")) } }
        ?: id?.let { metaId ->
            contentType?.let { type -> metaRepository.getCachedMeta(type, metaId)?.imdbId }
        }?.takeIf { it.matches(Regex("tt\\d+")) }
        ?: return null

    if (isMovie) return imdbId
    if (season == null || episode == null) return null
    return "$imdbId:$season:$episode"
}

/**
 * Called on each progress tick with the current position in milliseconds.
 */
internal fun PlayerRuntimeController.applyCleanStreamSkips(positionMs: Long) {
    val config = CleanStreamSettings.current
    refreshCleanStreamSubtitleMutes(config)
    maybeAlignCleanStreamSkips()
    if (!config.enabled) {
        releaseCleanStreamMute()
        return
    }
    // In a watch party only the host controls playback
    if (isPartyControlLocked) return

    // Timing adjustment and safety margin from settings
    val startShift = config.offsetMs - config.marginMs
    val endShift = config.offsetMs + config.marginMs
    val active: CleanSkip? = cleanSkips.firstOrNull {
        positionMs >= it.startMs + startShift && positionMs < it.endMs + endShift - CLEAN_END_MARGIN_MS &&
            !isCleanSkipDisabled(it)
    }

    // Strong language found in the subtitles (follows the subtitle delay setting)
    val subtitleDelay = _uiState.value.subtitleDelayMs.toLong()
    val subtitleMute: CleanSkip? = if (config.subtitleMuteEnabled) {
        cleanSubtitleMutes.firstOrNull {
            positionMs >= it.startMs + subtitleDelay - SUBTITLE_MUTE_PAD_MS &&
                positionMs < it.endMs + subtitleDelay + SUBTITLE_MUTE_PAD_MS
        }
    } else null

    val muteTarget: CleanSkip? = when {
        active?.channel == "audio" -> active
        subtitleMute != null -> subtitleMute
        else -> null
    }
    val muteKey = muteTarget?.let { if (it === subtitleMute) "sub-${it.key}" else it.key }

    // Leaving a muted stretch: turn the sound back on
    if (cleanMutedKey != null && muteKey != cleanMutedKey) {
        releaseCleanStreamMute()
    }
    if (muteTarget != null && cleanMutedKey == null) {
        setCleanStreamMuted(true)
        cleanMutedKey = muteKey
        // Subtitle mutes are short and frequent, so they stay silent
        if (muteTarget !== subtitleMute) showCleanStreamNotice("Muted: ${muteTarget.label}")
    }

    if (active == null || active.channel == "audio") return
    // This profile prefers a "Skip" button (see cleanStreamPendingIntervals)
    if (config.skipWithButton) return

    val now = SystemClock.elapsedRealtime()
    if (now - cleanLastSeekAt < CLEAN_SEEK_GUARD_MS) return
    cleanLastSeekAt = now

    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val target = (active.endMs + endShift + 50).coerceIn(0L, duration)
    seekPlaybackTo(target, SeekParameters.EXACT, exact = true)
    scheduleProgressSyncAfterSeek()
    val seconds = ((active.endMs - active.startMs) / 1000).coerceAtLeast(1)
    showCleanStreamNotice("Skipped ${seconds}s: ${active.label}")
    offerCleanStreamUndo(active, target)
}

/**
 * Load (or reload) the strong-language mutes when subtitle muting is on for
 * this profile and the subtitle in use changes. Uses the selected add-on
 * subtitle, otherwise an English one, otherwise an Arabic one.
 */
private fun PlayerRuntimeController.refreshCleanStreamSubtitleMutes(config: com.nuvio.tv.core.cleanstream.CleanStreamConfig) {
    if (!config.enabled || !config.subtitleMuteEnabled) {
        if (cleanSubtitleSourceKey != null) {
            cleanSubtitleSourceKey = null
            cleanSubtitleMutes = emptyList()
        }
        return
    }
    val state = _uiState.value
    val subtitle = state.selectedAddonSubtitle ?: pickMuteSubtitle(state.addonSubtitles) ?: return
    val key = "${subtitle.url}|${config.customMuteWords}"
    if (key == cleanSubtitleSourceKey) return
    cleanSubtitleSourceKey = key
    cleanSubtitleMutes = emptyList()
    scope.launch {
        val mutes = SubtitleProfanity.loadMutes(subtitle.url, subtitle.headers, config.customMuteWords)
        if (cleanSubtitleSourceKey == key) cleanSubtitleMutes = mutes
    }
}

private fun pickMuteSubtitle(subtitles: List<Subtitle>): Subtitle? {
    fun matches(sub: Subtitle, codes: Set<String>) = sub.lang.trim().lowercase().let { lang ->
        codes.any { lang == it || lang.startsWith("$it-") || lang.startsWith("$it ") }
    }
    return subtitles.firstOrNull { matches(it, setOf("en", "eng", "english")) }
        ?: subtitles.firstOrNull { matches(it, setOf("ar", "ara", "arabic")) }
}

internal fun PlayerRuntimeController.releaseCleanStreamMute() {
    if (cleanMutedKey == null) return
    cleanMutedKey = null
    setCleanStreamMuted(false)
}

private fun PlayerRuntimeController.setCleanStreamMuted(muted: Boolean) {
    if (isUsingMpvEngine()) {
        mpvView?.setCleanStreamMuted(muted)
    } else {
        _exoPlayer?.volume = if (muted) 0f else 1f
    }
}

/** Reuses the small "Source: ..." pill that the player already shows. */
internal fun PlayerRuntimeController.showCleanStreamNotice(text: String) {
    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update { it.copy(showStreamSourceIndicator = true, streamSourceIndicatorText = text) }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(CLEAN_NOTICE_MS)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

/* ------------------------------------------------------------------ */
/* Unconfirmed scenes: offered as a "Skip" button                      */
/* ------------------------------------------------------------------ */

/**
 * Other viewers' unconfirmed marks as skip intervals. They use a type that
 * Nuvio never auto-skips, so the viewer sees "Skip <scene> (unconfirmed)"
 * and decides with the remote. Pressing it confirms the scene.
 */
internal fun PlayerRuntimeController.cleanStreamPendingIntervals(): List<SkipInterval> {
    val config = CleanStreamSettings.current
    if (!config.enabled) return emptyList()
    // Confirmed scenes as a "Skip <scene>" button, for profiles that prefer to press it
    val buttons = if (config.skipWithButton) {
        cleanSkips.filter { it.channel != "audio" && !isCleanSkipDisabled(it) }.map { skip ->
            SkipInterval(
                // Shown a little before the scene, in case this copy's timing is slightly off
                startTime = (skip.startMs + config.offsetMs - config.marginMs - CLEAN_BUTTON_LEAD_MS).coerceAtLeast(0L) / 1000.0,
                endTime = (skip.endMs + config.offsetMs + config.marginMs) / 1000.0,
                type = CLEANSTREAM_BUTTON_TYPE_PREFIX + skip.label,
                provider = CLEANSTREAM_BUTTON_PROVIDER_PREFIX + skip.key,
            )
        }
    } else emptyList()
    return buttons + cleanStreamUndoIntervals() + cleanPending.filterNot { isCleanSkipDisabled(it) }.mapNotNull { p ->
        val id = p.ids.firstOrNull() ?: return@mapNotNull null
        SkipInterval(
            startTime = (p.startMs + config.offsetMs).coerceAtLeast(0L) / 1000.0,
            endTime = (p.endMs + config.offsetMs) / 1000.0,
            type = CLEANSTREAM_PROMPT_TYPE_PREFIX + p.label,
            provider = CLEANSTREAM_PROVIDER_PREFIX + id,
        )
    }
}

/** The viewer pressed "Skip" on an unconfirmed scene: confirm it on the server. */
internal fun PlayerRuntimeController.onCleanStreamPromptSkipped(interval: SkipInterval) {
    if (!interval.provider.startsWith(CLEANSTREAM_PROVIDER_PREFIX)) return
    val segmentId = interval.provider.removePrefix(CLEANSTREAM_PROVIDER_PREFIX)
    val contentKey = cleanContentKey ?: return
    val serverUrl = CleanStreamSettings.current.serverUrl
    // Do not offer the same scene again in this session
    cleanPending = cleanPending.filterNot { it.ids.contains(segmentId) }
    recomputeMergedSkipIntervals()
    scope.launch {
        // Our copy's fingerprint lets this skip be lined up on other copies too
        val ref = withTimeoutOrNull(15_000L) { cleanStreamLocalTimeline() }
        CleanStreamClient.vote(serverUrl, contentKey, segmentId, up = true, ref = ref)
    }
    showCleanStreamNotice("Thanks, you confirmed this scene")
}

/* ------------------------------------------------------------------ */
/* ♥ button: one press at the start, one at the end                    */
/* ------------------------------------------------------------------ */

/**
 * First press: remember the start. Second press: share the scene as
 * kissing / sexual content after a short delay. A third press during the
 * delay cancels.
 */
internal fun PlayerRuntimeController.toggleCleanStreamMark() {
    val contentKey = cleanContentKey
    val config = CleanStreamSettings.current
    if (contentKey == null || !config.enabled) {
        showCleanStreamNotice("Scene marking is not available for this video")
        return
    }

    // Third press: cancel a share that is waiting
    cleanShareJob?.let { job ->
        if (job.isActive) {
            job.cancel()
            cleanShareJob = null
            showCleanStreamNotice("Cancelled. Nothing was shared.")
            return
        }
    }

    val position = currentPlaybackPositionMs() ?: return
    val start = cleanMarkStartMs

    if (start == null) {
        cleanMarkStartMs = position
        _uiState.update { it.copy(cleanStreamMarking = true) }
        showCleanStreamNotice("Scene start marked. Press ♥ again when it ends.")
        return
    }

    cleanMarkStartMs = null
    _uiState.update { it.copy(cleanStreamMarking = false) }
    if (position - start < 1_000L) {
        showCleanStreamNotice("Too short, nothing saved. Press ♥ at the start and again at the end.")
        return
    }

    val severity = config.quickSeverity
    // Store times as they are in the reference copy (remove the local timing adjustment)
    val startMs = (start - config.offsetMs).coerceAtLeast(0L)
    val endMs = (position - config.offsetMs).coerceAtLeast(startMs + 1_000L)
    showCleanStreamNotice("Sharing in 5 seconds. Press ♥ to cancel.")
    cleanShareJob = scope.launch {
        delay(CLEAN_SHARE_DELAY_MS)
        // This copy's fingerprint, so other copies of the film can line the scene up
        val ref = withTimeoutOrNull(15_000L) { cleanStreamLocalTimeline() }
        val status = CleanStreamClient.contribute(
            serverUrl = config.serverUrl,
            contentId = contentKey,
            startMs = startMs,
            endMs = endMs,
            category = "sex",
            severity = severity,
            comment = "Kissing / sexual scene (marked on TV)",
            ref = ref,
        )
        showCleanStreamNotice(
            when (status) {
                null -> "Could not share the scene. Check the internet connection."
                "approved" -> "Shared ✓ It now applies for everyone. Thank you!"
                else -> "Shared ✓ It applies for you now, and for everyone once confirmed."
            }
        )
        // Reload so the new skip applies straight away for this viewer
        cleanSkipsKey = null
        fetchCleanStreamSkips(contentId, currentSeason, currentEpisode)
    }
}
