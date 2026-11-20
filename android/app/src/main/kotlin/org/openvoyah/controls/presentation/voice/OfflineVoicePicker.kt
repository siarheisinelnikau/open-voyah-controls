package org.openvoyah.controls.presentation.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.platform.voice.OfflineVoiceFeedback
import org.openvoyah.controls.platform.voice.forRecognitionLanguage
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitTextAction

@Composable
internal fun OfflineVoicePicker(
    language: String, previous: String, options: VoiceAudioOptions, dismiss: () -> Unit, useVoice: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var session by remember(app, language) { mutableStateOf<OfflineVoiceFeedback?>(null) }
    var selected by remember { mutableStateOf(previous) }
    var previewFailed by remember { mutableStateOf(false) }
    DisposableEffect(app, language, lifecycle) {
        fun start() { if (session == null) session = OfflineVoiceFeedback(app, language) }
        fun stop() { session?.close(); session = null; previewFailed = false }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }

    val engine = session
    val voices = engine?.choices?.collectAsStateWithLifecycle()?.value
    val playing = engine?.isSpeaking?.collectAsStateWithLifecycle()?.value == true
    LaunchedEffect(engine) {
        if (engine != null && withTimeoutOrNull(2_500L) { engine.choices.first { it != null } } == null) engine.close()
    }
    LaunchedEffect(engine, playing) {
        if (playing) {
            delay(5_000L)
            engine?.stop()
        }
    }

    fun choose(name: String) {
        engine?.stop()
        selected = name
        previewFailed = false
    }

    val displayLocale = Locale.forLanguageTag(UiText.language())
    val sample = remember(app, language) {
        app.forRecognitionLanguage(language).getString(R.string.voice_offline_sample_text)
    }
    CockpitAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(UiText.get(R.string.voice_offline_voice)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val listHeight = (LocalConfiguration.current.screenHeightDp * .38f).dp.coerceIn(54.dp, 216.dp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight).selectableGroup()) {
                    item {
                        OfflineVoiceRow(UiText.get(R.string.voice_offline_automatic), null, selected.isEmpty()) { choose("") }
                    }
                    items(voices.orEmpty(), key = { it.name }) { voice ->
                        OfflineVoiceRow(voice.locale.getDisplayName(displayLocale), voice.name, selected == voice.name) { choose(voice.name) }
                    }
                }
                val state = when {
                    voices == null -> R.string.voice_offline_loading
                    voices.isEmpty() -> R.string.voice_offline_unavailable
                    selected.isNotEmpty() && voices.none { it.name == selected } -> R.string.voice_offline_saved_unavailable
                    previewFailed -> R.string.voice_offline_sample_unavailable
                    else -> null
                }
                state?.let { Text(UiText.get(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!voices.isNullOrEmpty()) {
                    CockpitAction(UiText.get(if (playing) R.string.voice_offline_stop else R.string.voice_offline_sample), {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            if (playing) engine?.stop()
                            else previewFailed = engine?.speak(sample, options, selected) != true
                        }
                    }, enabled = playing || options.speechVolumePercent() > 0)
                }
            }
        },
        confirmButton = { CockpitAction(UiText.get(R.string.voice_offline_use), { engine?.stop(); useVoice(selected) }, primary = true) },
        dismissButton = { CockpitTextAction(UiText.get(R.string.ui_89)) { engine?.stop(); dismiss() } },
    )
}

@Composable
private fun OfflineVoiceRow(title: String, name: String?, selected: Boolean, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, role = Role.RadioButton, onClick = choose),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            name?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
