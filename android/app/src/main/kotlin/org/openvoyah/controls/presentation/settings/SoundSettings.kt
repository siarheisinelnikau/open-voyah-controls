package org.openvoyah.controls.presentation.settings

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.MediaAccessService
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.audio.DuckSettings
import org.openvoyah.controls.domain.audio.StartupVolume
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitIcon
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.CockpitSection
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.voice.DUCK_LEVELS
import org.openvoyah.controls.presentation.voice.STARTUP_VOLUME
import org.openvoyah.controls.presentation.voice.VoiceAudioChoice
import org.openvoyah.controls.presentation.voice.VoiceAudioLevel

/** Reads and writes the media settings that the voice settings store keeps: ducking and the startup volume. */
internal interface MediaSoundStore {
    fun duck(): DuckSettings
    fun startup(): StartupVolume
    fun save(duck: DuckSettings, startup: StartupVolume)
}

internal class VoiceMediaSoundStore(private val settings: VoiceSettings) : MediaSoundStore {
    override fun duck(): DuckSettings = settings.mediaDuck()
    override fun startup(): StartupVolume = settings.startupVolume()
    override fun save(duck: DuckSettings, startup: StartupVolume) = settings.media(duck, startup)
}

internal data class MediaSoundState(
    val duck: DuckSettings? = null,
    val startup: StartupVolume? = null,
    val failed: Boolean = false,
)

/**
 * Settings → Sound and media. A slider writes once it rests for [SAVE_DELAY_MILLIS], so a drag does not rewrite the
 * voice settings at every step.
 */
internal class MediaSoundViewModel(private val store: MediaSoundStore) : ViewModel() {
    private val mutable = MutableStateFlow(MediaSoundState())
    val state: StateFlow<MediaSoundState> = mutable.asStateFlow()
    private var pending: Job? = null

    fun refresh() {
        viewModelScope.launch {
            mutable.value = try {
                withContext(Dispatchers.IO) { MediaSoundState(store.duck(), store.startup()) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: RuntimeException) {
                MediaSoundState(failed = true)
            }
        }
    }

    fun duck(change: (DuckSettings) -> DuckSettings) = edit { it.copy(duck = it.duck?.let(change), failed = false) }
    fun startup(change: (StartupVolume) -> StartupVolume) = edit { it.copy(startup = it.startup?.let(change), failed = false) }

    private fun edit(change: (MediaSoundState) -> MediaSoundState) {
        mutable.update(change)
        val shown = mutable.value
        val duck = shown.duck ?: return
        val startup = shown.startup ?: return
        pending?.cancel()
        pending = viewModelScope.launch {
            delay(SAVE_DELAY_MILLIS)
            try { withContext(Dispatchers.IO) { store.save(duck, startup) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { mutable.update { it.copy(failed = true) } }
        }
    }

    private companion object {
        const val SAVE_DELAY_MILLIS = 400L
    }
}

@Composable
private fun mediaSoundModel(): MediaSoundViewModel {
    val activity = checkNotNull(LocalContext.current.findActivity()) { "Sound settings require a ComponentActivity host" }
    return remember(activity) {
        ViewModelProvider(activity, viewModelFactory {
            initializer { MediaSoundViewModel(VoiceMediaSoundStore(VoiceSettings(activity.applicationContext))) }
        })[MediaSoundViewModel::class.java]
    }
}

/** Settings → Sound and media → Volume at startup: after the head unit starts, beside the limit for Open Controls. */
@Composable
internal fun StartupVolumeSettings() {
    val model = mediaSoundModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    val state by model.state.collectAsStateWithLifecycle()
    val startup = state.startup
    CockpitSection(UiText.get(R.string.settings_startup_volume_title), CockpitIcon.PLAY) {
        CockpitSwitch(UiText.get(R.string.voice_startup_volume), startup?.enabled == true, enabled = startup != null) { on ->
            model.startup { it.copy(enabled = on) }
        }
        CockpitReveal(startup?.enabled == true) {
            Column {
                VoiceAudioLevel(R.string.voice_startup_volume_level, startup?.percent ?: StartupVolume.DEFAULT_PERCENT, STARTUP_VOLUME) { percent ->
                    model.startup { it.copy(percent = percent) }
                }
                Text(UiText.get(R.string.voice_startup_volume_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.failed) CockpitFeedback(UiText.get(R.string.ui_219), error = true)
    }
}

/** Settings → Sound and media → Media ducking: while a command is heard and during a call. */
@Composable
internal fun DuckingSettings() {
    val model = mediaSoundModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    val state by model.state.collectAsStateWithLifecycle()
    val duck = state.duck
    CockpitSection(UiText.get(R.string.settings_part_ducking), CockpitIcon.VOICE) {
        CockpitSwitch(UiText.get(R.string.voice_duck_media), duck?.listening == true, enabled = duck != null) { on ->
            model.duck { it.copy(listening = on) }
        }
        CockpitSwitch(UiText.get(R.string.voice_duck_calls), duck?.calls == true, enabled = duck != null) { on ->
            model.duck { it.copy(calls = on) }
        }
        CockpitReveal(duck != null && (duck.listening || duck.calls)) {
            Column {
                VoiceAudioChoice(R.string.voice_duck_level, DUCK_LEVELS, duck?.levelPercent ?: DuckSettings.DEFAULT_LEVEL,
                    announceGroup = true) { level -> model.duck { it.copy(levelPercent = level) } }
                Text(UiText.get(R.string.voice_duck_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.failed) CockpitFeedback(UiText.get(R.string.ui_219), error = true)
    }
}

/**
 * Settings → Sound and media → Media access: the notification listener that lets Controls read the playing track, for
 * the virtual display, the cluster and the desktop media card.
 */
@Composable
internal fun MediaAccessSettings() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf<Boolean?>(null) }
    var failed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val listener = ComponentName(context, MediaAccessService::class.java)
        granted = context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(listener)
    }
    CockpitSection(UiText.get(R.string.display_media_access), CockpitIcon.PLAY, UiText.get(R.string.settings_media_access_summary)) {
        granted?.let { CockpitFeedback(UiText.get(if (it) R.string.settings_media_access_on else R.string.settings_media_access_off)) }
        CockpitAction(UiText.get(R.string.settings_media_access_open), {
            failed = try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                false
            } catch (_: RuntimeException) { true }
        })
        if (failed) CockpitFeedback(UiText.get(R.string.ui_134), error = true)
    }
}

private tailrec fun android.content.Context.findActivity(): androidx.activity.ComponentActivity? = when (this) {
    is androidx.activity.ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
