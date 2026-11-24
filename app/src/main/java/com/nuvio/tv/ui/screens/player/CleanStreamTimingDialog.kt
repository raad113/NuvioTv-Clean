package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.abs

/**
 * NuvioTv-Clean: "Skip timing for this film", opened with a long press on ♥.
 * Moves every skip of the film earlier or later on this TV; the change applies
 * straight away so the viewer can check it, and is shared when the panel closes.
 */
@Composable
internal fun CleanStreamTimingDialog(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    brightness: Float = 1f,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .playerControlsBrightness(brightness)
                .width(460.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Skip timing for this film",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                )
                Text(
                    text = "If skips start too early or too late on this version, move them. " +
                        "The change applies straight away.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = describe(offsetMs),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    Button(onClick = { onAdjust(-5_000L) }) { Text("−5 s") }
                    Button(onClick = { onAdjust(-1_000L) }, modifier = Modifier.focusRequester(firstFocus)) { Text("−1 s") }
                    Button(onClick = { onAdjust(1_000L) }) { Text("+1 s") }
                    Button(onClick = { onAdjust(5_000L) }) { Text("+5 s") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    Button(onClick = onReset) { Text("Reset") }
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

/** "Skips 6 s earlier", "Skips 2.5 s later" or "Skips as stored". */
private fun describe(offsetMs: Long): String {
    if (offsetMs == 0L) return "Skips as stored"
    val seconds = abs(offsetMs) / 1000.0
    val amount = if (seconds % 1.0 == 0.0) "${seconds.toLong()} s" else String.format(java.util.Locale.US, "%.1f s", seconds)
    return if (offsetMs < 0) "Skips $amount earlier" else "Skips $amount later"
}
