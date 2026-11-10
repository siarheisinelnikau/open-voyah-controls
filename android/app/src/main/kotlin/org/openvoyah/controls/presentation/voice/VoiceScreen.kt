package org.openvoyah.controls.presentation.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitIcon

internal enum class VoiceOption(val label: Int) {
    LANGUAGE(R.string.voice_language),
    PROFILE(R.string.ui_189),
    LEVELS(R.string.default_levels),
    WINDOWS(R.string.window_options),
    AUDIO(R.string.voice_audio_settings),
    ONLINE(R.string.voice_online_title),
    RECOVERY(R.string.ui_177),
}

@Composable
internal fun VoiceSettingsScreen(
    catalog: VoiceCatalog,
    language: String,
    profile: String,
    assignments: Set<String>,
    message: String?,
    openCommand: (VoiceCatalog.Command) -> Unit,
    openOption: (VoiceOption) -> Unit,
    back: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val allCommands = remember(catalog) { catalog.all() }
    val searchable = remember(allCommands) {
        allCommands.associateWith { VoiceCatalog.normalize(it.phrases().joinToString(" ")) }
    }
    val visible = remember(searchable, search) {
        val query = VoiceCatalog.normalize(search)
        if (query.isBlank()) allCommands else allCommands.filter { searchable.getValue(it).contains(query) }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp)
        ) {
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SettingsOptions(language, profile, message, openOption, back)
                    }
                    Surface(
                        Modifier.weight(1f).fillMaxSize(),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            SearchField(search, { search = it })
                            LazyColumn(Modifier.weight(1f)) {
                                if (visible.isEmpty()) item { EmptyResults() }
                                items(visible, key = { it.id() }) {
                                    CommandRow(it, it.id() in assignments, openCommand)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item { SettingsOptions(language, profile, message, openOption, back) }
                    item { SearchField(search, { search = it }) }
                    if (visible.isEmpty()) item { EmptyResults() }
                    items(visible, key = { it.id() }) {
                        CommandRow(it, it.id() in assignments, openCommand)
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickVoicePopup(state: VoiceViewModel.State) {
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(
        if (state.failed) colors.error.copy(alpha = .65f) else colors.primary.copy(alpha = .42f),
        tween(170), label = "voice popup border",
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 11.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(41.dp).background(colors.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                CockpitGlyph(CockpitIcon.VOICE)
            }
            AnimatedContent(
                state, Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(tween(160)) + slideInVertically(tween(160)) { 7 })
                        .togetherWith(fadeOut(tween(90)))
                },
                label = "voice response",
            ) { current ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(current.status, style = MaterialTheme.typography.titleMedium,
                        color = if (current.failed) colors.error else colors.onSurface,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    current.phrase?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsOptions(language: String, profile: String, message: String?, select: (VoiceOption) -> Unit, back: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CockpitHeading(label(R.string.voice_options_title), back = back)
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CockpitGlyph(CockpitIcon.VOICE)
                Text(label(R.string.voice_button_hint), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        message?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
        }
        BoxWithConstraints {
            val columns = if (maxWidth >= 420.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in VoiceOption.entries.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in row) {
                            val name = when (option) {
                                VoiceOption.LANGUAGE -> label(option.label) + " · " + if (language == "ru") "Русский" else "English"
                                VoiceOption.PROFILE -> label(R.string.ui_176) + profile
                                else -> label(option.label)
                            }
                            OutlinedButton({ select(option) }, Modifier.weight(1f).heightIn(min = 54.dp),
                                shape = MaterialTheme.shapes.medium) {
                                Text(name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label(R.string.ui_34), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, onChange, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(label(R.string.ui_185)) })
    }
}

@Composable
private fun EmptyResults() {
    Text(label(R.string.voice_no_commands), Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CommandRow(command: VoiceCatalog.Command, assigned: Boolean, select: (VoiceCatalog.Command) -> Unit) {
    val title = command.title() + if (assigned) label(R.string.ui_186) else ""
    Column {
        Text(title, Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(role = Role.Button) { select(command) }
            .padding(horizontal = 8.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun VoiceAudioDialog(
    duckMedia: Boolean,
    feedbackEnabled: Boolean,
    dismiss: () -> Unit,
    save: (Boolean, Boolean) -> Unit,
) {
    var duck by remember { mutableStateOf(duckMedia) }
    var feedback by remember { mutableStateOf(feedbackEnabled) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(label(R.string.voice_audio_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceOptionToggle(label(R.string.voice_duck_media), duck) { duck = it }
                VoiceOptionToggle(label(R.string.voice_spoken_feedback), feedback) { feedback = it }
            }
        },
        confirmButton = {
            Button({ save(duck, feedback) }, Modifier.heightIn(min = 52.dp)) { Text(label(R.string.ui_104)) }
        },
        dismissButton = {
            TextButton(dismiss, Modifier.heightIn(min = 52.dp)) { Text(label(R.string.ui_89)) }
        },
    )
}

@Composable
internal fun VoiceOptionToggle(label: String, checked: Boolean, enabled: Boolean = true, update: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = update),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun label(id: Int) = UiText.get(id)
