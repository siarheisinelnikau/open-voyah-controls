package org.openvoyah.controls.presentation.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.core.voice.VoiceAudioOptions.EndCue
import org.openvoyah.controls.core.voice.VoiceAudioOptions.FeedbackStyle
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ListeningWindow
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ReadyCueStyle
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ResultDuration
import org.openvoyah.controls.core.voice.VoiceAudioOptions.SpokenReplyChannel
import org.openvoyah.controls.platform.voice.forRecognitionLanguage
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout

@Composable
internal fun VoiceAudioDialog(
    configuration: VoiceSettingsConfiguration, dismiss: () -> Unit, save: (VoiceSettingsChange.Audio) -> Unit,
) {
    val options = configuration.audioOptions
    val language = configuration.language
    var duck by remember { mutableStateOf(configuration.duckMedia) }
    var feedback by remember { mutableStateOf(configuration.spokenFeedback) }
    var rate by remember { mutableIntStateOf(options.speechRatePercent()) }
    var speechVolume by remember { mutableIntStateOf(options.speechVolumePercent()) }
    var readyCue by remember { mutableStateOf(options.readyCue()) }
    var readyStyle by remember { mutableStateOf(options.readyCueStyle()) }
    var readyPrompt by remember(language) { mutableStateOf(configuration.readyPrompt) }
    var replyChannel by remember { mutableStateOf(options.spokenReplyChannel()) }
    var cueVolume by remember { mutableIntStateOf(options.readyCueVolumePercent()) }
    var feedbackStyle by remember { mutableStateOf(options.feedbackStyle()) }
    var endCue by remember { mutableStateOf(options.endCue()) }
    var endCueVolume by remember { mutableStateOf<Int?>(options.endCueVolumePercent()) }
    var listeningWindow by remember { mutableStateOf(options.listeningWindow()) }
    var resultDuration by remember { mutableStateOf(options.resultDuration()) }
    var showPhrase by remember { mutableStateOf(options.showRecognizedPhrase()) }
    var voiceName by remember(language) { mutableStateOf(configuration.offlineVoiceName) }
    var choosingVoice by remember { mutableStateOf(false) }
    var editingPopup by remember { mutableStateOf(false) }
    val draft = { VoiceAudioOptions(rate, speechVolume, readyCue, cueVolume, feedbackStyle, endCue,
        listeningWindow, resultDuration, showPhrase, endCueVolume, readyStyle, replyChannel) }
    val phrase = readyPrompt.trim(' ')
    val phraseValid = VoiceAudioOptions.validReadyPrompt(phrase)
    val canSave = phraseValid || !readyCue || readyStyle != ReadyCueStyle.SPOKEN
    val saveDraft = {
        if (canSave) {
            val savedPhrase = if (phraseValid) phrase else configuration.readyPrompt
            save(VoiceSettingsChange.Audio(duck, feedback, draft(), language, voiceName, savedPhrase))
        } else editingPopup = false
    }
    if (choosingVoice) {
        OfflineVoicePicker(language, voiceName, draft(), { choosingVoice = false }) {
            voiceName = it
            choosingVoice = false
        }
        return
    }
    if (editingPopup) {
        val back = { editingPopup = false }
        CockpitAlertDialog(
            onDismissRequest = back,
            title = { Text(UiText.get(R.string.voice_capture_popup)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VoiceAudioChoice(R.string.voice_listening_limit, LISTENING_WINDOWS, listeningWindow, announceGroup = true) {
                        listeningWindow = it
                    }
                    VoiceAudioChoice(R.string.voice_result_duration, RESULT_DURATIONS, resultDuration, announceGroup = true) {
                        resultDuration = it
                    }
                    Text(UiText.get(R.string.voice_result_speech_grace), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VoiceOptionToggle(UiText.get(R.string.voice_show_recognized_phrase), showPhrase) { showPhrase = it }
                }
            },
            confirmButton = {
                Button(saveDraft, Modifier.heightIn(min = 52.dp)) { Text(UiText.get(R.string.ui_104)) }
            },
            dismissButton = {
                TextButton(back, Modifier.heightIn(min = 52.dp)) { Text(UiText.get(R.string.cockpit_back)) }
            },
        )
        return
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 800
    val columnsMinimum = LocalCockpitLayout.current.columnsMinimum(580.dp, LocalDensity.current.fontScale)
    val spokenSettings: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceOptionToggle(UiText.get(R.string.voice_spoken_feedback), feedback) { feedback = it }
            AnimatedVisibility(feedback) {
                Column {
                    VoiceAudioLevel(R.string.voice_speech_rate, rate, VoiceAudioOptions.MIN_RATE..VoiceAudioOptions.MAX_RATE) { rate = it }
                    VoiceAudioLevel(R.string.voice_speech_volume, speechVolume, VOICE_VOLUME) { speechVolume = it }
                    VoiceAudioChoice(R.string.voice_feedback_style, FEEDBACK_STYLES, feedbackStyle) { feedbackStyle = it }
                    VoiceAudioChoice(R.string.voice_reply_channel, REPLY_CHANNELS, replyChannel, announceGroup = true) { replyChannel = it }
                    CockpitChoice(UiText.get(R.string.voice_offline_voice),
                        voiceName.ifEmpty { UiText.get(R.string.voice_offline_automatic) }) { choosingVoice = true }
                }
            }
        }
    }
    val captureSettings: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceOptionToggle(UiText.get(R.string.voice_duck_media), duck) { duck = it }
            VoiceOptionToggle(UiText.get(R.string.voice_ready_cue), readyCue) { readyCue = it }
            AnimatedVisibility(readyCue) {
                Column {
                    VoiceAudioChoice(R.string.voice_ready_style, READY_STYLES, readyStyle, announceGroup = true) { readyStyle = it }
                    if (readyStyle == ReadyCueStyle.SPOKEN) {
                        ReadyPromptField(language, readyPrompt, phraseValid) { readyPrompt = it }
                        Text(UiText.get(R.string.voice_ready_fallback), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    VoiceAudioLevel(R.string.voice_ready_cue_volume, cueVolume, VOICE_VOLUME) { cueVolume = it }
                }
            }
            VoiceAudioChoice(R.string.voice_end_listening_cue, END_CUES, endCue) { endCue = it }
            if (endCue != EndCue.OFF) {
                val presetVolume = (endCue.gain() * 100).roundToInt()
                val resetDescription = UiText.get(R.string.voice_end_cue_preset_volume).format(presetVolume)
                VoiceAudioLevel(R.string.voice_end_cue_volume, endCueVolume ?: presetVolume, VOICE_VOLUME, steps = 0, trailing = {
                    TextButton({ endCueVolume = null }, enabled = endCueVolume != null,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = resetDescription }) {
                        Text(UiText.get(R.string.voice_end_cue_reset))
                    }
                }) { endCueVolume = it }
            }
            CockpitChoice(UiText.get(R.string.voice_capture_popup),
                UiText.get(R.string.voice_capture_popup_summary).format(listeningWindow.seconds(), resultDuration.seconds())) {
                editingPopup = true
            }
        }
    }
    CockpitAlertDialog(
        onDismissRequest = dismiss,
        modifier = if (wide) Modifier.width(740.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !wide),
        title = { Text(UiText.get(R.string.voice_audio_settings)) },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (maxWidth >= columnsMinimum) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1f)) { spokenSettings() }
                        Column(Modifier.weight(1f)) { captureSettings() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        spokenSettings()
                        captureSettings()
                    }
                }
            }
        },
        confirmButton = {
            Button(saveDraft, Modifier.heightIn(min = 52.dp), enabled = canSave) { Text(UiText.get(R.string.ui_104)) }
        },
        dismissButton = {
            TextButton(dismiss, Modifier.heightIn(min = 52.dp)) { Text(UiText.get(R.string.ui_89)) }
        },
    )
}

@Composable
private fun ReadyPromptField(language: String, prompt: String, valid: Boolean, change: (String) -> Unit) {
    val context = LocalContext.current
    val default = remember(context, language) { context.forRecognitionLanguage(language).getString(R.string.voice_ready_prompt) }
    val reset = UiText.get(R.string.voice_ready_phrase_reset)
    val limit = VoiceAudioOptions.MAX_READY_PROMPT_CHARACTERS
    OutlinedTextField(
        value = prompt, onValueChange = change, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(UiText.get(R.string.voice_ready_phrase)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), isError = !valid,
        trailingIcon = if (prompt.isEmpty()) null else ({
            TextButton({ change("") }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = reset }) {
                Text(UiText.get(R.string.voice_end_cue_reset))
            }
        }),
        supportingText = {
            Text(if (valid) UiText.get(R.string.voice_ready_phrase_hint).format(limit, default)
                else UiText.get(R.string.voice_ready_phrase_invalid).format(limit))
        },
    )
}

@Composable
private fun <T> VoiceAudioChoice(label: Int, choices: List<Pair<T, Int>>, selected: T, announceGroup: Boolean = false, change: (T) -> Unit) {
    val palette = MaterialTheme.colorScheme
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = palette.primaryContainer,
        selectedLabelColor = palette.onPrimaryContainer,
    )
    Column(Modifier.fillMaxWidth()) {
        val group = UiText.get(label)
        Text(group, color = palette.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((choice, title) in choices) {
                val text = UiText.get(title)
                val modifier = if (announceGroup) Modifier.semantics { contentDescription = "$group, $text" } else Modifier
                FilterChip(choice == selected, { change(choice) }, label = { Text(text) }, modifier = modifier, colors = chipColors)
            }
        }
    }
}

@Composable
private fun VoiceAudioLevel(label: Int, value: Int, range: IntRange, steps: Int = (range.last - range.first) / 5 - 1,
                            trailing: (@Composable () -> Unit)? = null, change: (Int) -> Unit) {
    val title = UiText.get(label)
    val percentage = "$value%"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(percentage, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(
                value.toFloat(), { change(it.roundToInt()) },
                modifier = Modifier.weight(1f).semantics { contentDescription = title; stateDescription = percentage },
                valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
                colors = if (steps == 0) SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant)
                    else SliderDefaults.colors(),
            )
            trailing?.invoke()
        }
    }
}

private val VOICE_VOLUME = VoiceAudioOptions.MIN_VOLUME..VoiceAudioOptions.MAX_VOLUME
private val READY_STYLES = listOf(ReadyCueStyle.TONE to R.string.voice_ready_tone, ReadyCueStyle.SPOKEN to R.string.voice_ready_spoken)
private val REPLY_CHANNELS = listOf(SpokenReplyChannel.ASSISTANT to R.string.voice_channel_assistant,
    SpokenReplyChannel.MEDIA to R.string.voice_channel_media)
private val FEEDBACK_STYLES = listOf(FeedbackStyle.READBACK to R.string.voice_feedback_readback,
    FeedbackStyle.OUTCOME to R.string.voice_feedback_outcome)
private val END_CUES = listOf(EndCue.OFF to R.string.voice_end_cue_off, EndCue.SOFT to R.string.voice_end_cue_soft,
    EndCue.STANDARD to R.string.voice_end_cue_standard)
private val LISTENING_WINDOWS = listOf(ListeningWindow.THREE_SECONDS to R.string.voice_seconds_3,
    ListeningWindow.FOUR_SECONDS to R.string.voice_seconds_4, ListeningWindow.SIX_SECONDS to R.string.voice_seconds_6)
private val RESULT_DURATIONS = listOf(ResultDuration.ONE_SECOND to R.string.voice_seconds_1,
    ResultDuration.TWO_SECONDS to R.string.voice_seconds_2, ResultDuration.FOUR_SECONDS to R.string.voice_seconds_4)
