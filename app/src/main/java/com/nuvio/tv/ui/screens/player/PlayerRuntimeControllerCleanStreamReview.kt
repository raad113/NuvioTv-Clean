package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.cleanstream.CleanStreamClient
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/*
 * NuvioTv-Clean: "Review unconfirmed scenes" (a per-profile setting, off by default).
 *
 * An unconfirmed scene still shows its Skip button. If the viewer lets it
 * play instead, then when it ends they are asked whether it was right:
 * "Yes, skip it for everyone" confirms it, "Not a scene" reports it.
 * Ignoring the question sends nothing.
 */

/**
 * A question about an unconfirmed scene: [ask] while it plays ("Skip / Confirm
 * and skip / Not a scene"), otherwise after it played ("Was it marked right?").
 */
data class CleanStreamReviewPrompt(
    val segmentId: String,
    val label: String,
    val ask: Boolean = false,
    val endMs: Long = 0L,
)

/** The viewer's answer to a [CleanStreamReviewPrompt]. */
enum class CleanStreamAnswer { SKIP, CONFIRM, NOT_A_SCENE, DISMISS }

/** Show the "unconfirmed scene" choice this long before the scene starts. */
private const val ASK_LEAD_MS = 2_000L

/**
 * Called on each progress tick on profiles with the Skip button: while an
 * unconfirmed scene plays, offer Skip / Confirm and skip / Not a scene.
 */
internal fun PlayerRuntimeController.trackCleanStreamAsk(positionMs: Long, offsetMs: Long) {
    val current = _uiState.value.cleanReview
    val active = cleanPending.firstOrNull { p ->
        val id = p.ids.firstOrNull()
        id != null && p.channel != "audio" && id !in cleanAskDone && !isCleanSkipDisabled(p) &&
            positionMs >= p.startMs + offsetMs - ASK_LEAD_MS && positionMs < p.endMs + offsetMs
    }
    when {
        active != null -> {
            val id = active.ids.first()
            if (current == null || current.segmentId != id) {
                _uiState.update { it.copy(cleanReview = CleanStreamReviewPrompt(id, active.label, ask = true, endMs = active.endMs + offsetMs)) }
            }
        }
        // The scene is over (or was jumped past): the question goes away unanswered
        current != null && current.ask -> _uiState.update { it.copy(cleanReview = null) }
    }
}

/** Ask only when most of the scene was actually watched. */
private const val REVIEW_MIN_WATCHED_SHARE = 0.6

/** Ask within this time after the scene ends (not after jumping far past it). */
private const val REVIEW_ASK_WINDOW_MS = 5_000L
private const val REVIEW_SHOW_MS = 10_000L

/** Called on each progress tick while the setting is on. */
internal fun PlayerRuntimeController.trackCleanStreamReview(positionMs: Long, offsetMs: Long) {
    val last = cleanReviewLastPos
    cleanReviewLastPos = positionMs
    // Only normal playback counts as watching (not seeking)
    val step = if (last != null && positionMs > last && positionMs - last <= 2_000L) positionMs - last else 0L
    for (p in cleanPending) {
        val id = p.ids.firstOrNull() ?: continue
        if (id in cleanReviewed || isCleanSkipDisabled(p) || id in cleanAskAnswered) continue
        val start = p.startMs + offsetMs
        val end = p.endMs + offsetMs
        if (positionMs in start until end) {
            cleanReviewWatched[id] = (cleanReviewWatched[id] ?: 0L) + step
        } else if (positionMs >= end && positionMs < end + REVIEW_ASK_WINDOW_MS) {
            val watched = cleanReviewWatched[id] ?: 0L
            if (watched >= (end - start) * REVIEW_MIN_WATCHED_SHARE && _uiState.value.cleanReview?.ask != true) {
                cleanReviewed += id
                showCleanStreamReview(CleanStreamReviewPrompt(id, p.label))
            }
        }
    }
}

private fun PlayerRuntimeController.showCleanStreamReview(prompt: CleanStreamReviewPrompt) {
    _uiState.update { it.copy(cleanReview = prompt) }
    cleanReviewJob?.cancel()
    cleanReviewJob = launchCleanStream("cleanstream review") {
        delay(REVIEW_SHOW_MS)
        _uiState.update { if (it.cleanReview == prompt) it.copy(cleanReview = null) else it }
    }
}

/** The viewer answered the question on screen. */
internal fun PlayerRuntimeController.answerCleanStreamReview(answer: CleanStreamAnswer) {
    val prompt = _uiState.value.cleanReview ?: return
    cleanReviewJob?.cancel()
    _uiState.update { it.copy(cleanReview = null) }
    if (prompt.ask) cleanAskDone += prompt.segmentId
    if (answer == CleanStreamAnswer.DISMISS) return
    if (prompt.ask) cleanAskAnswered += prompt.segmentId

    // Skip (with or without confirming): jump to the end of the scene
    if (prompt.ask && (answer == CleanStreamAnswer.SKIP || answer == CleanStreamAnswer.CONFIRM) && !isPartyControlLocked) {
        val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
        seekPlaybackTo((prompt.endMs + 300).coerceAtMost(duration), androidx.media3.exoplayer.SeekParameters.EXACT, exact = true)
        scheduleProgressSyncAfterSeek()
    }
    if (answer == CleanStreamAnswer.SKIP) {
        showCleanStreamNotice("Skipped. Nothing was sent.")
        return
    }
    val right = answer == CleanStreamAnswer.CONFIRM
    val contentKey = cleanContentKey ?: return
    val serverUrl = CleanStreamSettings.current.serverUrl
    // Not offered again in this playback either way
    cleanPending = cleanPending.filterNot { it.ids.contains(prompt.segmentId) }
    if (!right) cleanDisabledIds += prompt.segmentId
    recomputeMergedSkipIntervals()
    launchCleanStream("cleanstream review") {
        if (right) {
            // This copy's fingerprint lets the scene be lined up on other copies too
            val ref = withTimeoutOrNull(15_000L) { cleanStreamLocalTimeline() }
            CleanStreamClient.vote(serverUrl, contentKey, prompt.segmentId, up = true, ref = ref)
        } else {
            CleanStreamClient.vote(serverUrl, contentKey, prompt.segmentId, up = false)
        }
    }
    val editor = CleanStreamSettings.current.editorKey.isNotBlank()
    showCleanStreamNotice(
        when {
            right && editor -> "Confirmed for everyone. Changed your mind? Use Recent decisions on the admin page."
            right -> "Thanks, you confirmed this scene"
            editor -> "Removed for everyone. Changed your mind? Use Recent decisions on the admin page."
            else -> "Thanks, reported as not a scene"
        }
    )
}
