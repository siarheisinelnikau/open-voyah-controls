package org.openvoyah.controls.presentation.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitGlyph
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.LocalCockpitLayout
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

/** RECOGNITION groups the engine, the recognition language and the optional online interpretation. */
internal enum class VoiceOption(val label: Int) {
    TRY_VOICE(R.string.quick_voice_try),
    RECOGNITION(R.string.voice_recognition),
    LEVELS(R.string.default_levels),
    WINDOWS(R.string.window_options),
    VEHICLE(R.string.voice_vehicle_commands),
    AUDIO(R.string.voice_audio_settings),
    RECOVERY(R.string.ui_177),
}

@Composable
internal fun VoiceSettingsScreen(
    catalog: VoiceCatalog,
    language: String,
    engine: RecognitionEngine,
    vehicleCommandsReviewed: Boolean,
    assignments: Set<String>,
    message: String?,
    microphone: VoiceMicrophoneViewModel.State,
    recoverMicrophone: () -> Unit,
    openCommand: (VoiceCatalog.Command) -> Unit,
    openOption: (VoiceOption) -> Unit,
    back: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<VoiceGuideCategory?>(null) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    val interfaceLanguage = UiText.language()
    val guides = remember(catalog, assignments, interfaceLanguage) {
        catalog.all().map { describeVoiceCommand(it, it.id() in assignments) }
    }
    val categories = remember(guides) {
        VoiceGuideCategory.entries.filter { category -> guides.any { it.category == category } }
    }
    val selectedCategory = category?.takeIf { it in categories }
    val groups = remember(guides, selectedCategory, search) { filterVoiceGuides(guides, selectedCategory, search) }
    val layout = LocalCockpitLayout.current
    CockpitBackdrop {
        // The same header position and padding as CockpitDetail, so Back and the title stay put between pages.
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal = layout.spacing(22.dp), vertical = layout.spacing(14.dp)),
            verticalArrangement = Arrangement.spacedBy(layout.spacing(14.dp)),
        ) {
            CockpitHeading(label(R.string.voice_options_title), back = back)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                if (maxWidth >= 760.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(.82f).verticalScroll(rememberScrollState())) {
                            SettingsOptions(language, engine, vehicleCommandsReviewed, message, microphone, recoverMicrophone, openOption)
                        }
                        Surface(
                            Modifier.weight(1.18f).fillMaxSize(),
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                VoiceCatalogFilters(search, { search = it }, categories, selectedCategory) { category = it }
                                key(search, selectedCategory) {
                                    LazyColumn(Modifier.weight(1f)) { voiceCatalogRows(groups, openCommand) }
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!showCatalog, { showCatalog = false }, label = { Text(label(R.string.cockpit_settings)) })
                            FilterChip(showCatalog, { showCatalog = true }, label = { Text(label(R.string.ui_34)) })
                        }
                        if (showCatalog) {
                            VoiceCatalogFilters(search, { search = it }, categories, selectedCategory, compact = true) { category = it }
                            key(search, selectedCategory) {
                                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                                    voiceCatalogRows(groups, openCommand)
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                                SettingsOptions(language, engine, vehicleCommandsReviewed, message, microphone, recoverMicrophone, openOption)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickVoicePopup(
    state: VoiceViewModel.State,
    showRecognizedPhrase: Boolean,
    recoverMicrophone: (() -> Unit)? = null,
    listeningMillis: Long = 0L,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalCockpitMotion.current
    val colorSpec: AnimationSpec<Color> = if (motion.enabled) CockpitMotion.change() else snap()
    val phase = state.phase
    val border by animateColorAsState(
        if (state.failed) colors.error.copy(alpha = .65f) else colors.primary.copy(alpha = .42f),
        colorSpec, label = "voice popup border",
    )
    val tile by animateColorAsState(
        when {
            state.failed -> colors.errorContainer
            phase == VoiceViewModel.Phase.LISTENING -> colors.tertiaryContainer
            phase == VoiceViewModel.Phase.EXECUTING -> colors.primaryContainer
            else -> colors.surfaceVariant
        },
        colorSpec, label = "voice microphone tile",
    )
    val glyph by animateColorAsState(
        when {
            state.failed -> colors.onErrorContainer
            phase == VoiceViewModel.Phase.LISTENING -> colors.onTertiaryContainer
            phase == VoiceViewModel.Phase.EXECUTING -> colors.onPrimaryContainer
            else -> colors.tertiary
        },
        colorSpec, label = "voice microphone glyph",
    )
    val rise = with(LocalDensity.current) { 7.dp.roundToPx() }
    // One frame for every phase: the popup does not grow or shrink while it listens, runs and answers.
    Surface(
        Modifier.fillMaxWidth().height(QUICK_VOICE_HEIGHT_DP.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(64.dp).background(tile, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                CockpitGlyph(CockpitIcon.VOICE, Modifier.size(34.dp), tint = glyph)
            }
            // Keyed on the phase: a new status or phrase within one phase updates in place instead of animating again.
            AnimatedContent(
                state, Modifier.weight(1f).fillMaxHeight(),
                transitionSpec = {
                    if (motion.enabled) {
                        (fadeIn(CockpitMotion.change()) + slideInVertically(CockpitMotion.change()) { rise })
                            .togetherWith(fadeOut(CockpitMotion.exit())) using null
                    } else ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                },
                label = "voice response",
                contentKey = { it.phase },
            ) { current ->
                val phrase = current.phrase?.takeIf { showRecognizedPhrase }
                val statusColor = if (current.failed) colors.error else if (phrase != null) colors.onSurfaceVariant else colors.onSurface
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    // What was heard leads, so the result or the error below it reads as the answer to that phrase.
                    if (phrase != null) {
                        Text(phrase, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val statusStyle = when {
                        phrase != null -> MaterialTheme.typography.titleMedium
                        recoverMicrophone != null -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.headlineSmall
                    }
                    Text(current.status, style = statusStyle, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current.phase == VoiceViewModel.Phase.LISTENING && listeningMillis > 0) {
                        ListeningBar(listeningMillis, Modifier.padding(vertical = 4.dp).fillMaxWidth())
                    }
                    recoverMicrophone?.let { recover ->
                        CockpitTextAction(label(R.string.quick_voice_microphone_settings), recover)
                    }
                }
            }
        }
    }
}

/** The quick voice popup keeps this size, in dp, for every phase and result. */
internal const val QUICK_VOICE_WIDTH_DP = 640
internal const val QUICK_VOICE_HEIGHT_DP = 144

/** Empties once over the recorder's listening window, so the time left to speak can be read at a glance. */
@Composable
private fun ListeningBar(windowMillis: Long, modifier: Modifier = Modifier) {
    var left by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(windowMillis) {
        // Frame time tracks the recorder's real deadline; an animation would stretch with the animator duration scale.
        val start = withFrameMillis { it }
        do {
            val elapsed = withFrameMillis { it } - start
            left = 1f - (elapsed.toFloat() / windowMillis).coerceAtMost(1f)
        } while (elapsed < windowMillis)
    }
    // The status line already announces listening; a progress value that changes every frame would only be noise.
    LinearProgressIndicator(progress = { left }, modifier = modifier.clearAndSetSemantics { },
        color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun SettingsOptions(language: String, engine: RecognitionEngine, vehicleCommandsReviewed: Boolean, message: String?,
                            microphone: VoiceMicrophoneViewModel.State, recoverMicrophone: () -> Unit,
                            select: (VoiceOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val access = microphone.access
                    val microphoneMessage = when {
                        microphone.requesting -> R.string.voice_microphone_waiting
                        access == VoiceMicrophoneViewModel.Access.ALLOWED -> R.string.voice_microphone_ready
                        access == VoiceMicrophoneViewModel.Access.SETTINGS -> R.string.voice_microphone_settings_needed
                        else -> R.string.voice_microphone_needed
                    }
                    Text(label(microphoneMessage), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (access == VoiceMicrophoneViewModel.Access.ALLOWED) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error)
                    if (access != VoiceMicrophoneViewModel.Access.ALLOWED) {
                        CockpitAction(label(if (access == VoiceMicrophoneViewModel.Access.SETTINGS)
                            R.string.voice_microphone_open_settings else R.string.voice_microphone_allow),
                            recoverMicrophone, enabled = !microphone.requesting, primary = true)
                    }
                    Text(label(R.string.voice_button_hint), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label(if (vehicleCommandsReviewed) R.string.voice_vehicle_reviewed
                        else R.string.voice_vehicle_approval_required), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        message?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
        }
        // Trying the microphone is a command; every other option opens a dialog, so it is a row like other settings.
        CockpitAction(label(VoiceOption.TRY_VOICE.label), { select(VoiceOption.TRY_VOICE) }, enabled = !microphone.requesting)
        BoxWithConstraints {
            val columns = if (maxWidth >= 500.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in VoiceOption.entries.filter { it != VoiceOption.TRY_VOICE }.chunked(columns)) {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in row) {
                            val recognition = if (option == VoiceOption.RECOGNITION) {
                                label(engine.label) + " · " + label(recognitionLanguageName(language))
                            } else null
                            CockpitChoice(option.label, recognition, modifier = Modifier.weight(1f).fillMaxHeight()) { select(option) }
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun label(id: Int) = UiText.get(id)
