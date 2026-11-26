package org.openvoyah.controls.presentation.vehicle.comfort

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openvoyah.controls.ButtonAccessibilityService
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.vehicle.comfort.PlaybackComfortPreferences
import org.openvoyah.controls.domain.vehicle.comfort.PlaybackComfortSettings
import org.openvoyah.controls.platform.media.ComfortMediaTransport
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch

private data class PlaybackComfortScreenState(
    val settings: PlaybackComfortSettings? = null,
    val access: Boolean = false,
    val background: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

private class PlaybackComfortViewModel(
    private val preferences: PlaybackComfortPreferences,
    private val media: ComfortMediaTransport,
) : ViewModel() {
    private val mutable = MutableStateFlow(PlaybackComfortScreenState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.observe().collect { saved ->
                mutable.update { it.copy(settings = saved.getOrNull(), failed = saved.isFailure) }
            }
        }
    }

    fun refresh() = mutable.update { it.copy(access = media.hasAccess(), background = ButtonAccessibilityService.isConnected()) }

    fun edit(change: (PlaybackComfortSettings) -> PlaybackComfortSettings) {
        val before = state.value.settings ?: return
        if (state.value.saving) return
        mutable.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            try {
                val replacement = change(before)
                preferences.update(before, replacement)
                mutable.update { it.copy(settings = replacement) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(failed = true) }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }
}

@Composable
internal fun PlaybackComfortSection() {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val model = remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { PlaybackComfortViewModel(PlaybackComfortPreferences(activity.application), ComfortMediaTransport(activity.application)) }
        })["comfort.playback", PlaybackComfortViewModel::class.java]
    }
    val state by model.state.collectAsStateWithLifecycle()
    var active by remember(activity) { mutableStateOf(false) }
    var openFailed by remember(activity) { mutableStateOf(false) }
    LifecycleResumeEffect(model) {
        active = true
        model.refresh()
        onPauseOrDispose { active = false }
    }
    val saved = state.settings
    CockpitSection(UiText.get(R.string.comfort_playback_title), CockpitIcon.PLAY) {
        CockpitSwitch(R.string.comfort_playback_door, saved?.pauseOnDriverDoor == true, enabled = saved != null && !state.saving) {
            if (active) model.edit { current -> current.copy(pauseOnDriverDoor = it) }
        }
        if (saved?.pauseOnDriverDoor == true) Text(UiText.get(R.string.comfort_playback_door_hint),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CockpitSwitch(R.string.comfort_playback_wake, saved?.restoreAfterSleep == true, enabled = saved != null && !state.saving) {
            if (active) model.edit { current -> current.copy(restoreAfterSleep = it) }
        }
        if (saved?.restoreAfterSleep == true) {
            Text(UiText.get(R.string.comfort_playback_wake_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.background) Text(UiText.get(R.string.comfort_playback_background_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.access && saved?.configured == true) {
            Text(UiText.get(R.string.comfort_playback_access_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CockpitAction(UiText.get(R.string.comfort_playback_access), {
                if (active) {
                    openFailed = try {
                        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        false
                    } catch (_: RuntimeException) { true }
                }
            })
        }
        if (state.failed) CockpitFeedback(UiText.get(R.string.vehicle_automation_save_failed), error = true)
        if (openFailed) CockpitFeedback(UiText.get(R.string.comfort_playback_access_failed), error = true)
    }
}
