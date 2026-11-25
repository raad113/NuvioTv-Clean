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

/** The question shown after an unconfirmed scene played. */
data class CleanStreamReviewPrompt(val segmentId: String, val label: String)

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
        if (id in cleanReviewed || isCleanSkipDisabled(p)) continue
        val start = p.startMs + offsetMs
        val end = p.endMs + offsetMs
        if (positionMs in start until end) {
            cleanReviewWatched[id] = (cleanReviewWatched[id] ?: 0L) + step
        } else if (positionMs >= end && positionMs < end + REVIEW_ASK_WINDOW_MS) {
            val watched = cleanReviewWatched[id] ?: 0L
            if (watched >= (end - start) * REVIEW_MIN_WATCHED_SHARE) {
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

/** The viewer answered: [right] true confirms the scene, false reports it; null closes without answering. */
internal fun PlayerRuntimeController.answerCleanStreamReview(right: Boolean?) {
    val prompt = _uiState.value.cleanReview ?: return
    cleanReviewJob?.cancel()
    _uiState.update { it.copy(cleanReview = null) }
    if (right == null) return
    val contentKey = cleanContentKey ?: return
    val serverUrl = CleanStreamSettings.current.serverUrl
    // Not offered again in this playback either way
    cleanPending = cleanPending.filterNot { it.ids.contains(prompt.segmentId) }
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
    showCleanStreamNotice(if (right) "Thanks, you confirmed this scene" else "Thanks, reported as not a scene")
}
