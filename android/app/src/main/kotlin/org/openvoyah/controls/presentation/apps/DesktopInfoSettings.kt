package org.openvoyah.controls.presentation.apps

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.apps.DesktopInfoPreferences
import org.openvoyah.controls.data.settings.VoiceContacts
import org.openvoyah.controls.domain.apps.AppGridDesktop
import org.openvoyah.controls.domain.apps.DesktopClockFormat
import org.openvoyah.controls.domain.apps.DesktopHeadlightContent
import org.openvoyah.controls.domain.apps.DesktopInfoCard
import org.openvoyah.controls.domain.apps.DesktopInfoConfiguration
import org.openvoyah.controls.domain.apps.DesktopInfoSize
import org.openvoyah.controls.domain.apps.DesktopMediaOpenTarget
import org.openvoyah.controls.domain.apps.DesktopMediaStyle
import org.openvoyah.controls.domain.apps.DesktopRouteContent
import org.openvoyah.controls.domain.apps.DesktopRouteOpenTarget
import org.openvoyah.controls.domain.apps.PhoneFavoriteEdit
import org.openvoyah.controls.platform.media.PreviewMediaSource
import org.openvoyah.controls.platform.media.PreviewMediaSources
import org.openvoyah.controls.presentation.theme.CockpitAlertDialog
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitTextAction

private class DesktopInfoSettingsViewModel(
    private val preferences: DesktopInfoPreferences,
    private val mediaSources: PreviewMediaSources,
    voiceContacts: VoiceContacts,
) : ViewModel() {
    data class State(
        val configuration: Result<DesktopInfoConfiguration>? = null,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    )

    private enum class Write { IDLE, SAVING, FAILED }

    private val write = MutableStateFlow(Write.IDLE)
    val state = combine(preferences.observe(), write) { configuration, status ->
        State(configuration, status == Write.SAVING, status == Write.FAILED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), State())
    val contacts = voiceContacts.observe()

    fun set(desktop: AppGridDesktop, card: DesktopInfoCard, size: DesktopInfoSize?) = save { preferences.set(desktop, card, size) }
    fun mediaStyle(desktop: AppGridDesktop, style: DesktopMediaStyle) = save { preferences.setMediaStyle(desktop, style) }
    fun mediaPackage(desktop: AppGridDesktop, packageName: String) = save { preferences.setMediaPackage(desktop, packageName) }
    fun mediaOpenTarget(desktop: AppGridDesktop, target: DesktopMediaOpenTarget) =
        save { preferences.setMediaOpenTarget(desktop, target) }

    fun clockFormat(desktop: AppGridDesktop, format: DesktopClockFormat) = save { preferences.setClockFormat(desktop, format) }
    fun headlightContent(desktop: AppGridDesktop, content: DesktopHeadlightContent) =
        save { preferences.setHeadlightContent(desktop, content) }

    fun routeContent(desktop: AppGridDesktop, content: DesktopRouteContent) =
        save { preferences.setRouteContent(desktop, content) }

    fun routeOpenTarget(desktop: AppGridDesktop, target: DesktopRouteOpenTarget) =
        save { preferences.setRouteOpenTarget(desktop, target) }

    fun editPhoneFavorite(desktop: AppGridDesktop, id: String, edit: PhoneFavoriteEdit) = save {
        preferences.editPhoneFavorite(desktop, id, edit)
    }

    suspend fun mediaChoices(preferredPackage: String): List<PreviewMediaSource> = withContext(Dispatchers.IO) {
        mediaSources.list(preferredPackage)
    }

    private fun save(action: suspend () -> Unit) {
        if (write.getAndUpdate { Write.SAVING } == Write.SAVING) return
        viewModelScope.launch {
            write.value = try {
                action()
                Write.IDLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Write.FAILED
            }
        }
    }
}

@Composable
internal fun DesktopInfoSettings(
    extraDesktops: Boolean,
    desktopName: (AppGridDesktop) -> String,
    editable: Boolean,
) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var show by rememberSaveable { mutableStateOf(false) }
    var editingPhoneFavorites by rememberSaveable { mutableStateOf(false) }
    val title = UiText.get(R.string.desktop_info_settings)
    CockpitChoice(title, enabled = editable && active) { show = true }
    if (!show || !active) return

    val activity = checkNotNull(LocalActivity.current as? ComponentActivity) {
        "Desktop card settings require a ComponentActivity host"
    }
    val model = remember(activity) {
        val app = activity.applicationContext
        ViewModelProvider(activity, viewModelFactory {
            initializer { DesktopInfoSettingsViewModel(DesktopInfoPreferences(app), PreviewMediaSources(app), VoiceContacts(app)) }
        })[DesktopInfoSettingsViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val configuration = state.configuration?.getOrNull()
    val canEdit = configuration != null && !state.saving && editable
    var selected by rememberSaveable { mutableStateOf(AppGridDesktop.MAIN) }
    val desktop = if (extraDesktops) selected else AppGridDesktop.MAIN
    fun canSave(): Boolean = canEdit && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    if (editingPhoneFavorites) {
        val contacts by model.contacts.collectAsStateWithLifecycle(initialValue = null, minActiveState = Lifecycle.State.RESUMED)
        DesktopPhoneFavoritesEditor(configuration?.phoneFavoritesOf(desktop), contacts, canEdit,
            state.saveFailed || state.configuration?.isFailure == true, back = { editingPhoneFavorites = false }) { id, edit ->
            if (canSave()) model.editPhoneFavorite(desktop, id, edit)
        }
        return
    }
    CockpitAlertDialog(
        onDismissRequest = { show = false },
        title = { Text(title) },
        confirmButton = { CockpitTextAction(UiText.get(R.string.apps_desktop_done)) { show = false } },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (extraDesktops) {
                    DesktopCardChoice(R.string.desktop_info_desktop, desktopName(desktop), !state.saving,
                        AppGridDesktop.entries, desktopName) { selected = it }
                }
                for (card in DesktopInfoCard.entries) {
                    val size = configuration?.sizeOf(desktop, card)
                    DesktopCardChoice(card.label(), configuration?.let { UiText.get(size.label()) }, canEdit, layoutOptions,
                        { UiText.get(it.label()) }) { if (canSave()) model.set(desktop, card, it) }
                    when (card) {
                        DesktopInfoCard.CLOCK -> DesktopCardChoice(R.string.desktop_info_clock_format,
                            configuration?.clockFormatOf(desktop)?.let { UiText.get(it.label()) }, canEdit,
                            DesktopClockFormat.entries, { UiText.get(it.label()) }) { if (canSave()) model.clockFormat(desktop, it) }
                        DesktopInfoCard.MEDIA -> {
                            DesktopCardChoice(R.string.desktop_info_media_style,
                                configuration?.mediaStyleOf(desktop)?.let { UiText.get(it.label()) }, canEdit,
                                DesktopMediaStyle.entries, { UiText.get(it.label()) }) { if (canSave()) model.mediaStyle(desktop, it) }
                            DesktopMediaSourceChoice(model, configuration?.mediaPackageOf(desktop), canEdit) {
                                if (canSave()) model.mediaPackage(desktop, it)
                            }
                            DesktopCardChoice(R.string.desktop_info_media_open_target,
                                configuration?.mediaOpenTargetOf(desktop)?.let { UiText.get(it.label()) }, canEdit,
                                DesktopMediaOpenTarget.entries, { UiText.get(it.label()) }) {
                                if (canSave()) model.mediaOpenTarget(desktop, it)
                            }
                        }
                        DesktopInfoCard.HEADLIGHTS -> DesktopCardChoice(R.string.desktop_info_headlights_content,
                            configuration?.headlightContentOf(desktop)?.let { UiText.get(it.label()) }, canEdit,
                            DesktopHeadlightContent.entries, { UiText.get(it.label()) }) {
                            if (canSave()) model.headlightContent(desktop, it)
                        }
                        DesktopInfoCard.PHONE -> CockpitChoice(UiText.get(R.string.desktop_info_phone_edit), enabled = canEdit) {
                            editingPhoneFavorites = true
                        }
                        DesktopInfoCard.ROUTE -> if (size != null) {
                            DesktopCardChoice(R.string.desktop_info_route_content,
                                configuration?.routeContentOf(desktop)?.let { UiText.get(it.label()) }, canEdit,
                                DesktopRouteContent.entries, { UiText.get(it.label()) }) {
                                if (canSave()) model.routeContent(desktop, it)
                            }
                            DesktopCardChoice(R.string.desktop_info_route_open_target,
                                configuration?.routeOpenTargetOf(desktop)?.let { UiText.get(it.label()) }, canEdit,
                                DesktopRouteOpenTarget.entries, { UiText.get(it.label()) },
                                supporting = {
                                    if (it == DesktopRouteOpenTarget.NAVIGATOR) UiText.get(R.string.desktop_info_route_open_navigator_hint)
                                    else null
                                }) {
                                if (canSave()) model.routeOpenTarget(desktop, it)
                            }
                        }
                        else -> Unit
                    }
                }
                if (state.saveFailed || state.configuration?.isFailure == true) {
                    CockpitFeedback(UiText.get(R.string.desktop_info_settings_failed), error = true)
                }
            }
        },
    )
}

@Composable
private fun DesktopMediaSourceChoice(
    model: DesktopInfoSettingsViewModel,
    preferredPackage: String?,
    enabled: Boolean,
    select: (String) -> Unit,
) {
    val players by produceState(emptyList<PreviewMediaSource>(), model, preferredPackage) {
        preferredPackage?.let { value = model.mediaChoices(it) }
    }
    val automatic = UiText.get(R.string.desktop_info_media_source_automatic)
    val options = remember(players, automatic) { listOf(PreviewMediaSource("", automatic)) + players }
    val labelCounts = remember(options) { options.groupingBy { it.label }.eachCount() }
    val selected = preferredPackage?.let { saved -> options.firstOrNull { it.packageName == saved }?.label ?: saved }
    DesktopCardChoice(R.string.desktop_info_media_source, selected, enabled, options, PreviewMediaSource::label,
        supporting = { source ->
            source.packageName.takeIf { it.isNotEmpty() && it != source.label && (labelCounts[source.label] ?: 0) > 1 }
        }) { select(it.packageName) }
}

@Composable
private fun <T> DesktopCardChoice(
    title: Int, value: String?, enabled: Boolean, options: List<T>, label: (T) -> String,
    supporting: (T) -> String? = { null }, select: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CockpitChoice(UiText.get(title), value, enabled = enabled) { expanded = true }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            for (option in options) DropdownMenuItem(text = {
                Column {
                    Text(label(option))
                    supporting(option)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }, onClick = {
                expanded = false
                select(option)
            })
        }
    }
}

private val layoutOptions = listOf(null, DesktopInfoSize.COMPACT, DesktopInfoSize.WIDE)

private fun DesktopInfoSize?.label(): Int = when (this) {
    null -> R.string.desktop_info_hidden
    DesktopInfoSize.COMPACT -> R.string.desktop_info_compact
    DesktopInfoSize.WIDE -> R.string.desktop_info_wide
}

private fun DesktopClockFormat.label(): Int = when (this) {
    DesktopClockFormat.SYSTEM -> R.string.desktop_info_clock_format_system
    DesktopClockFormat.HOUR_24 -> R.string.desktop_info_clock_format_24h
    DesktopClockFormat.HOUR_12 -> R.string.desktop_info_clock_format_12h
}

private fun DesktopMediaStyle.label(): Int = when (this) {
    DesktopMediaStyle.TEXT -> R.string.desktop_info_media_style_text
    DesktopMediaStyle.COVER -> R.string.desktop_info_media_style_cover
}

private fun DesktopMediaOpenTarget.label(): Int = when (this) {
    DesktopMediaOpenTarget.NONE -> R.string.desktop_info_media_open_none
    DesktopMediaOpenTarget.DISPLAY -> R.string.virtual_display
    DesktopMediaOpenTarget.PLAYER -> R.string.desktop_info_media_open_player
}

private fun DesktopHeadlightContent.label(): Int = when (this) {
    DesktopHeadlightContent.SWITCH_POSITION -> R.string.desktop_info_headlights_content_switch
    DesktopHeadlightContent.LAMP_ACTIVITY -> R.string.desktop_info_headlights_content_lamps
}

private fun DesktopRouteContent.label(): Int = when (this) {
    DesktopRouteContent.NEXT_TURN -> R.string.desktop_info_route_next_turn
    DesktopRouteContent.CURRENT_AND_NEXT -> R.string.desktop_info_route_current_and_next
}

private fun DesktopRouteOpenTarget.label(): Int = when (this) {
    DesktopRouteOpenTarget.DISPLAY -> R.string.virtual_display
    DesktopRouteOpenTarget.NAVIGATOR -> R.string.desktop_info_route_open_navigator
    DesktopRouteOpenTarget.NONE -> R.string.desktop_info_media_open_none
}
