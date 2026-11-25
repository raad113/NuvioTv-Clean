package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
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

/* ------------------------------------------------------------------ */
/* Glass style: translucent layers, a light edge, a glow when focused   */
/* ------------------------------------------------------------------ */

private val GlassEdge = Color.White.copy(alpha = 0.28f)
private val GlassEdgeFocused = Color.White.copy(alpha = 0.75f)
private val Mint = Color(0xFF3EE0A8)
private val MarkingRed = Color(0xFFE53935)

/** A dark glass card for panels over the video. */
private fun Modifier.glassPanel(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
    .background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xCC2A313A), Color(0xB3161A20))),
        shape,
    )
    .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), shape)

/** A pill-shaped glass button; [tint] colours the glass (white for a neutral one). */
@Composable
private fun GlassButton(
    text: String,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, label = "glassScale")
    val shape = RoundedCornerShape(50)
    val top = tint.copy(alpha = if (focused) 0.55f else 0.22f)
    val bottom = tint.copy(alpha = if (focused) 0.30f else 0.08f)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focused) Modifier.shadow(18.dp, shape, ambientColor = tint, spotColor = tint) else Modifier)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, bottom)), shape)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) GlassEdgeFocused else GlassEdge, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A thin highlight along the top edge, as light on glass
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = if (focused) 0.6f else 0.3f))
        )
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

/** Red glass "Marking" tag with the scene's length so far, shown until the end is marked. */
@Composable
internal fun CleanStreamMarkingTag(startMs: Long?, positionMs: Long, modifier: Modifier = Modifier) {
    if (startMs == null) return
    val seconds = ((positionMs - startMs).coerceAtLeast(0L) / 1000L)
    val shape = RoundedCornerShape(50)
    // The dot pulses gently, like a recording light
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "marking").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(800),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "markingDot",
    )
    Row(
        modifier = modifier
            .shadow(16.dp, shape, ambientColor = MarkingRed, spotColor = MarkingRed)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MarkingRed.copy(alpha = 0.80f), Color(0xB3801010))),
                shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).graphicsLayer { alpha = pulse }.background(Color.White, CircleShape))
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

/** "Was this scene marked right?" on dark glass with two glass buttons; Back closes it without answering. */
@Composable
internal fun CleanStreamReviewPanel(
    prompt: CleanStreamReviewPrompt,
    onAnswer: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val yesFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(prompt) { runCatching { yesFocus.requestFocus() } }
    val shape = RoundedCornerShape(20.dp)
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .glassPanel(shape)
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .closeOnBack { onAnswer(null) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.tv.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Shield,
                contentDescription = null,
                tint = Mint,
                modifier = Modifier.size(20.dp),
            )
            Text(text = "Was this scene marked right?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(text = "Marked as: ${prompt.label}", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassButton(
                text = "✓  Yes, skip it for everyone",
                onClick = { onAnswer(true) },
                tint = Mint,
                modifier = Modifier.focusRequester(yesFocus),
            )
            GlassButton(text = "✗  Not a scene", onClick = { onAnswer(false) }, tint = Color.White)
        }
    }
}

/** Back (and Escape) closes without answering. */
private fun Modifier.closeOnBack(onBack: () -> Unit): Modifier =
    this.then(Modifier.onKeyEvent { e ->
        val code = e.nativeKeyEvent.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.nativeKeyEvent.action == KeyEvent.ACTION_UP) onBack()
            true
        } else false
    })
