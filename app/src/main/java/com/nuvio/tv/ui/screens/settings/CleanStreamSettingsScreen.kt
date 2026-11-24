@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.cleanstream.CleanStreamCategory
import com.nuvio.tv.core.cleanstream.CleanStreamSettings
import com.nuvio.tv.core.cleanstream.CleanStreamTiming
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * NuvioTv-Family settings: turn automatic skipping on or off, choose the
 * CleanStream server, and set how strict each filter is.
 * Changes apply to the next title you play.
 */
@Composable
fun CleanStreamSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val config by remember { CleanStreamSettings.config(context) }.collectAsStateWithLifecycle()
    var showServerDialog by remember { mutableStateOf(false) }
    var showWordsDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = "NuvioTv-Family",
            subtitle = "Automatically skip or mute unwanted scenes using CleanStream community data"
        )

        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "cleanstream_enabled") {
                        SettingsToggleRow(
                            title = "Automatic skipping",
                            subtitle = "Skip flagged scenes and mute flagged language while playing",
                            checked = config.enabled,
                            onToggle = { CleanStreamSettings.setEnabled(!config.enabled) },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(
                                    if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester)
                                    else Modifier
                                )
                        )
                    }
                    item(key = "cleanstream_server") {
                        SettingsActionRow(
                            title = "CleanStream server",
                            subtitle = "Where skip data is loaded from. Use your own server to share skips with family and friends",
                            value = config.serverUrl.removePrefix("https://").removePrefix("http://"),
                            onClick = { showServerDialog = true },
                            enabled = config.enabled,
                            modifier = Modifier
                        )
                    }
                    item(key = "cleanstream_subtitle_mute") {
                        SettingsToggleRow(
                            title = "Mute bad language using subtitles (this profile)",
                            subtitle = "Mutes the sound while subtitle lines with strong English or Arabic swear words are spoken. Uses subtitles from your subtitle add-ons",
                            checked = config.subtitleMuteEnabled,
                            onToggle = { CleanStreamSettings.setSubtitleMuteEnabled(!config.subtitleMuteEnabled) },
                            enabled = config.enabled,
                            modifier = Modifier
                        )
                    }
                    item(key = "cleanstream_subtitle_words") {
                        val count = config.customMuteWords.split(',', '،').count { it.isNotBlank() }
                        SettingsActionRow(
                            title = "Extra words to mute (this profile)",
                            subtitle = "Your own words, separated by commas",
                            value = if (count == 0) "None" else "$count word${if (count == 1) "" else "s"}",
                            onClick = { showWordsDialog = true },
                            enabled = config.enabled && config.subtitleMuteEnabled,
                            modifier = Modifier
                        )
                    }
                    item(key = "cleanstream_quick_severity") {
                        val labels = mapOf("low" to "Mild (kissing)", "medium" to "Moderate", "high" to "Strong (explicit)")
                        SettingsActionRow(
                            title = "♥ button marks scenes as",
                            subtitle = "In the player, press ♥ when a kissing or sexual scene starts and again when it ends to share it",
                            value = labels[config.quickSeverity] ?: "Moderate",
                            onClick = {
                                val order = listOf("low", "medium", "high")
                                val next = order[(order.indexOf(config.quickSeverity).coerceAtLeast(0) + 1) % order.size]
                                CleanStreamSettings.setQuickSeverity(next)
                            },
                            enabled = config.enabled,
                            modifier = Modifier
                        )
                    }
                    item(key = "cleanstream_margin") {
                        SettingsActionRow(
                            title = "Safety margin",
                            subtitle = "Also skip this much before and after each scene, in case your copy is timed a little differently",
                            value = CleanStreamTiming.label(config.marginMs).removePrefix("+"),
                            onClick = { CleanStreamSettings.setMargin(CleanStreamTiming.nextAfter(CleanStreamTiming.MARGINS_MS, config.marginMs)) },
                            enabled = config.enabled,
                            modifier = Modifier
                        )
                    }
                    item(key = "cleanstream_offset") {
                        SettingsActionRow(
                            title = "Timing adjustment",
                            subtitle = "If skips happen too early, choose a + value; if too late, a - value. Set it back to 0 s for other titles",
                            value = CleanStreamTiming.label(config.offsetMs),
                            onClick = { CleanStreamSettings.setOffset(CleanStreamTiming.nextAfter(CleanStreamTiming.OFFSETS_MS, config.offsetMs)) },
                            enabled = config.enabled,
                            modifier = Modifier
                        )
                    }
                    CleanStreamCategory.entries.forEach { category ->
                        item(key = "cleanstream_level_${category.key}") {
                            val level = config.levels[category] ?: category.defaultLevel
                            SettingsActionRow(
                                title = category.label,
                                subtitle = "Press to change: Off, Low, Medium, High",
                                value = level.label,
                                onClick = { CleanStreamSettings.setLevel(category, level.next()) },
                                enabled = config.enabled,
                                modifier = Modifier
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showServerDialog) {
        CleanStreamTextDialog(
            title = "CleanStream server",
            subtitle = "For example https://cleanstream.example.com",
            currentValue = config.serverUrl,
            keyboardType = KeyboardType.Uri,
            resetLabel = "Use default",
            onSave = { value ->
                val url = value.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
                CleanStreamSettings.setServerUrl(url)
                showServerDialog = false
            },
            onReset = { CleanStreamSettings.setServerUrl(""); showServerDialog = false },
            onDismiss = { showServerDialog = false }
        )
    }

    if (showWordsDialog) {
        CleanStreamTextDialog(
            title = "Extra words to mute",
            subtitle = "Separate words with commas. Each word is matched as a whole word, in any letter case",
            currentValue = config.customMuteWords,
            keyboardType = KeyboardType.Text,
            resetLabel = "Clear",
            onSave = { value -> CleanStreamSettings.setCustomMuteWords(value); showWordsDialog = false },
            onReset = { CleanStreamSettings.setCustomMuteWords(""); showWordsDialog = false },
            onDismiss = { showWordsDialog = false }
        )
    }
}

@Composable
private fun CleanStreamTextDialog(
    title: String,
    subtitle: String,
    currentValue: String,
    keyboardType: KeyboardType,
    resetLabel: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = shape
                ),
                focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text("Cancel") }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = onReset,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(resetLabel) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text("Save") }
        }
    }
}
