package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.cleanstream.CleanStreamClient
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * NuvioTv-Clean: "Send sync report" (timing panel).
 *
 * Nuvio Clean notes what it did with the film's timing: which subtitles and
 * sync it used, how each skip was placed and how many words it muted. The
 * viewer can send these notes to the skip server when timing looks wrong.
 * They contain times and counts only: no subtitle text, no addresses.
 */

private const val CLEAN_DIAG_MAX_LINES = 150

/** Note one step, with the playback position at the time. */
internal fun PlayerRuntimeController.cleanDiag(text: String) {
    // From the UI state: the player itself may only be asked on the main thread
    val pos = _uiState.value.currentPosition.takeIf { it > 0 }
    val line = "[${pos?.let { formatDiagTime(it) } ?: "-"}] $text"
    synchronized(cleanDiagLines) {
        cleanDiagLines.addLast(line)
        while (cleanDiagLines.size > CLEAN_DIAG_MAX_LINES) cleanDiagLines.removeFirst()
    }
}

internal fun formatDiagTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d.%01d".format(s / 3600, (s / 60) % 60, s % 60, (ms % 1000) / 100)
}

/** Send the notes, with the current state, to the skip server. */
internal fun PlayerRuntimeController.sendCleanStreamSyncReport() {
    val state = _uiState.value
    val config = CleanStreamSettings.current
    val header = buildString {
        appendLine("Sync report")
        appendLine("Title: $cleanContentKey")
        appendLine("Position: ${currentPlaybackPositionMs()?.let { formatDiagTime(it) }}  Length: ${formatDiagTime(currentPlaybackDurationMs())}")
        val selected = state.selectedAddonSubtitle
        appendLine("Selected add-on subtitle: ${selected?.let { "${it.lang} (${it.id.take(24)})" } ?: "none (built-in or off)"}")
        appendLine("Subtitle delay: ${state.subtitleDelayMs} ms  Sync: ${selected?.url?.let { cleanSubtitleSync(it)?.first } ?: "none"}")
        appendLine("Add-on subtitles: ${state.addonSubtitles.joinToString { it.lang }.take(300)}")
        appendLine("Local fingerprint: ${cleanLocalRef?.let { "${it.kind}, ${it.cues.size} lines" } ?: "none"}")
        appendLine("Skips: ${cleanSkipsRaw.size} confirmed, ${cleanPendingRaw.size} unconfirmed, anchors ${cleanAnchors.size}, corrections ${cleanCorrections.size}")
        appendLine("Mutes ready: ${cleanSubtitleMutes.size}  Mute setting: ${config.subtitleMuteEnabled}  Skip button: ${config.skipWithButton}")
        appendLine("This TV's timing adjustment: ${cleanContentKey?.let { CleanStreamSettings.titleOffset(it) } ?: "none"}  Global offset: ${config.offsetMs}")
        appendLine("Placed skips (start, end):")
        (cleanSkips + cleanPending).sortedBy { it.startMs }.take(40).forEach { s ->
            appendLine("  ${formatDiagTime(s.startMs)} - ${formatDiagTime(s.endMs)} ${s.label}${if (s.refId != null) " [fingerprint]" else ""}")
        }
        appendLine("First mutes:")
        cleanSubtitleMutes.take(15).forEach { m -> appendLine("  ${formatDiagTime(m.startMs)} - ${formatDiagTime(m.endMs)}") }
        appendLine("Steps:")
    }
    val steps = synchronized(cleanDiagLines) { cleanDiagLines.joinToString("\n") }
    launchCleanStream("cleanstream report") {
        val sent = withContext(Dispatchers.IO) { CleanStreamClient.sendReportNow("sync", header + steps) }
        showCleanStreamNotice(if (sent) "Sync report sent. Thank you!" else "Could not send the report. Check the internet connection.")
    }
}
