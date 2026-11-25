package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/*
 * NuvioTv-Clean: marking a scene without opening the controls.
 *
 * Every remote (Google TV, Mi Box, TV makers' remotes) has the arrows, OK and
 * Back, and little else in common, so the shortcut is holding OK for about a
 * second while the controls are hidden. A short press still plays or pauses
 * (on release instead of on press, so a hold does not pause first). Remotes
 * and keyboards that have them can also use the red button, Bookmark or S.
 */

/** How long OK must be held to work as the shield. */
private const val CLEAN_OK_HOLD_MS = 800L

/** Whether OK is held, and whether the hold already worked as the shield. */
internal class CleanOkHold {
    var held = false
    var fired = false
}

/**
 * Handle the shield shortcut. Returns true when the key was used.
 * Called only when no panel or dialog is open.
 */
internal fun cleanStreamShieldKey(
    event: KeyEvent,
    uiState: PlayerUiState,
    hold: CleanOkHold,
    viewModel: PlayerViewModel,
): Boolean {
    when (event.keyCode) {
        KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_S -> {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) viewModel.onCleanStreamMark()
            return true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Unit
        else -> return false
    }
    when (event.action) {
        KeyEvent.ACTION_DOWN -> {
            if (event.repeatCount == 0) {
                // Only on the bare video; with the controls or the pause screen up, OK works as usual
                if (uiState.showControls || uiState.showPauseOverlay) return false
                hold.held = true
                hold.fired = false
                return true
            }
            if (!hold.held) return false
            if (!hold.fired && event.eventTime - event.downTime >= CLEAN_OK_HOLD_MS) {
                hold.fired = true
                viewModel.onCleanStreamMark()
            }
            return true
        }
        KeyEvent.ACTION_UP -> {
            if (!hold.held) return false
            hold.held = false
            // A short press: play or pause, as before
            if (!hold.fired) viewModel.onEvent(PlayerEvent.OnPlayPause)
            return true
        }
    }
    return false
}

private val MarkingRed = Color(0xFFE53935)

/** Red "Marking" tag with the scene's length so far, shown until the end is marked. */
@Composable
internal fun CleanStreamMarkingTag(startMs: Long?, positionMs: Long, modifier: Modifier = Modifier) {
    if (startMs == null) return
    val seconds = ((positionMs - startMs).coerceAtLeast(0L) / 1000L)
    Row(
        modifier = modifier
            .background(MarkingRed.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).background(Color.White, CircleShape))
        Text(
            text = "Marking  %d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Hold OK to end",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
        )
    }
}
