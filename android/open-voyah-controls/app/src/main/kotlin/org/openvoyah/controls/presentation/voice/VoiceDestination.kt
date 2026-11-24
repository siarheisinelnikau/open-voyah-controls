package org.openvoyah.controls.presentation.voice

import android.Manifest
import android.content.Context
import org.openvoyah.controls.platform.voice.SpeechModelDownloads
import org.openvoyah.controls.platform.voice.SpeechModels
import org.openvoyah.controls.domain.voice.SpeechEngine
import org.openvoyah.controls.data.voice.SpeechModelCatalog
import org.openvoyah.controls.data.voice.SpeechModel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.launch
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.data.settings.VoiceSettingsRepository
import org.openvoyah.controls.platform.voice.MicrophonePermission
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitAppPicker
import org.openvoyah.controls.presentation.theme.CockpitChoice
import org.openvoyah.controls.presentation.theme.CockpitDetail
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.CockpitModal
import org.openvoyah.controls.presentation.theme.CockpitOptionRow
import org.openvoyah.controls.presentation.theme.CockpitSwitch
import org.openvoyah.controls.presentation.theme.CockpitTextAction
import org.openvoyah.controls.presentation.theme.CockpitTextField
import org.openvoyah.controls.presentation.theme.SettingGuideHeading

/** Voice configuration stays inside the app's navigation; recording uses the dedicated overlay. */
@Composable
internal fun VoiceDestination(back: () -> Unit, tryVoice: () -> Unit) {
    val activity = checkNotNull(LocalContext.current.voiceActivity()) { "Voice settings require a ComponentActivity" }
    val settings = remember(activity) {
        ViewModelProvider(activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VoiceSettingsViewModel(VoiceSettingsRepository(activity.applicationContext)) as T
        })["voice-settings", VoiceSettingsViewModel::class.java]
    }
    val microphone = remember(activity) {
        ViewModelProvider(activity)["voice-microphone", VoiceMicrophoneViewModel::class.java]
    }
    val permission = remember(activity) { MicrophonePermission(activity.applicationContext) }
    val dialogs = remember(activity) { CockpitDialogs(activity) }
    val owner = remember(settings) { Any() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val routeState by lifecycle.currentStateFlow.collectAsState()
    val state by settings.state.collectAsStateWithLifecycle()
    val microphoneState by microphone.state.collectAsStateWithLifecycle()
    val latestTry by rememberUpdatedState(tryVoice)
    var active by remember { mutableStateOf(true) }
    var pendingTry by rememberSaveable { mutableStateOf(false) }

    fun systemAction(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: RuntimeException) {
        settings.message(R.string.ui_85)
        false
    }

    fun refreshMicrophone(finished: Boolean = true, continueTry: Boolean = true) {
        val granted = permission.granted()
        microphone.refresh(granted, permission.requested(),
            activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO), finished)
        if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && granted && pendingTry && continueTry) {
            pendingTry = false
            systemAction(latestTry)
        }
    }

    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (active) {
            if (!granted) pendingTry = false
            refreshMicrophone()
        }
    }
    val saveVoice = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { file ->
            lifecycleOwner.lifecycleScope.launch {
                lifecycle.withStarted {
                    if (active) { settings.enter(owner); settings.transfer(file.toString(), restoring = false) }
                }
            }
        }
    }
    val loadVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { file ->
            lifecycleOwner.lifecycleScope.launch {
                lifecycle.withStarted {
                    if (active) { settings.enter(owner); settings.transfer(file.toString(), restoring = true) }
                }
            }
        }
    }

    fun recoverMicrophone() {
        if (!active || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        refreshMicrophone(continueTry = false)
        when (microphone.state.value.access) {
            VoiceMicrophoneViewModel.Access.ALLOWED -> if (pendingTry) {
                pendingTry = false
                systemAction(latestTry)
            }
            VoiceMicrophoneViewModel.Access.SETTINGS -> {
                if (!systemAction { activity.startActivity(permission.settingsIntent()) }) pendingTry = false
            }
            VoiceMicrophoneViewModel.Access.REQUESTABLE -> if (microphone.beginRequest()) {
                try {
                    permission.recordRequest()
                    requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                } catch (_: RuntimeException) {
                    pendingTry = false
                    refreshMicrophone()
                    settings.message(R.string.voice_microphone_unavailable)
                }
            }
        }
    }

    DisposableEffect(activity, settings, dialogs, owner, lifecycle) {
        active = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) settings.enter(owner)
        onDispose {
            active = false
            if (!activity.isChangingConfigurations) pendingTry = false
            dialogs.dismiss()
            settings.leave(owner, activity.isChangingConfigurations)
        }
    }
    DisposableEffect(lifecycle, activity, settings, dialogs, owner) {
        fun resumed() {
            settings.enter(owner)
            refreshMicrophone()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> settings.enter(owner)
                Lifecycle.Event.ON_RESUME -> resumed()
                Lifecycle.Event.ON_STOP -> {
                    // Route changes stop only this owner; an external Android handoff stops the Activity too.
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) pendingTry = false
                    dialogs.dismiss()
                    settings.closeDialogs()
                    settings.leave(owner, activity.isChangingConfigurations)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumed()
        onDispose { lifecycle.removeObserver(observer) }
    }

    val actions = remember(dialogs, settings, saveVoice, loadVoice, activity) {
        VoiceDestinationDialogs(dialogs, settings,
            save = { systemAction { saveVoice.launch("controls-voice.json") } },
            load = { systemAction { loadVoice.launch(arrayOf("*/*")) } },
            app = activity.applicationContext)
    }
    val engineFailures by SpeechModels.engineFailures.collectAsState()
    LaunchedEffect(activity) { withContext(Dispatchers.IO) { SpeechModelDownloads.refresh(activity) } }
    val config = state.configuration
    if (config == null) {
        CockpitDetail(voiceText(R.string.voice_options_title), null, back) {
            Text(state.startupError?.let { voiceText(R.string.ui_164) + it } ?: voiceText(R.string.voice_catalog_loading),
                style = MaterialTheme.typography.bodyLarge)
            if (state.startupError != null) {
                CockpitAction(voiceText(R.string.ui_165), { settings.restorePrevious() }, enabled = !state.busy, primary = true)
            }
        }
        return
    }

    val status = state.message?.let { (it.resource?.let(::voiceText).orEmpty() + it.detail.orEmpty()) }
    VoiceSettingsScreen(config.catalog, config.language,
        RecognitionEngine.effective(config.engine, config.language, SpeechModels.zipformerSupported(), engineFailures.keys),
        config.vehicleCommandsReviewed, config.assigned, status,
        microphoneState, { pendingTry = false; recoverMicrophone() }, { command ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) actions.showCommand(command)
        }, { option ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !settings.state.value.busy) {
                when (option) {
                    VoiceOption.TRY_VOICE -> { pendingTry = true; recoverMicrophone() }
                    VoiceOption.RECOGNITION -> actions.showRecognition()
                    VoiceOption.LEVELS -> actions.showDefaultLevels()
                    VoiceOption.WINDOWS -> actions.showWindowOptions()
                    VoiceOption.VEHICLE -> actions.showVehicleCommands()
                    VoiceOption.AUDIO -> settings.openAudio()
                    VoiceOption.RECOVERY -> actions.showRecovery()
                }
            }
        }, back)
    if (routeState.isAtLeast(Lifecycle.State.STARTED) && state.onlineOpen) {
        OnlineVoiceDialog(config.onlineConfigured, state.work == VoiceSettingsViewModel.Work.ONLINE,
            state.onlineFailed, settings::closeOnline) { enabled, key -> settings.saveOnline(enabled, key) }
    }
    if (routeState.isAtLeast(Lifecycle.State.STARTED) && state.audioOpen) {
        VoiceAudioDialog(config, settings::closeAudio) { settings.audio(it) }
    }
}

private tailrec fun Context.voiceActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.voiceActivity()
    else -> null
}

private fun voiceText(id: Int): String = UiText.get(id)


@Composable
private fun RecognitionHeading(title: Int) {
    SettingGuideHeading(voiceText(title), title, Modifier.semantics { heading() })
}

/** Dialog drafts are composition/window-owned; every accepted change goes through the model. */
private class VoiceDestinationDialogs(
    private val dialogs: CockpitDialogs,
    private val model: VoiceSettingsViewModel,
    private val save: () -> Unit,
    private val load: () -> Unit,
    private val app: Context,
) {
    /**
     * Engine, recognition language, downloadable models and online interpretation in one place. Choosing an engine
     * or a language closes the section; downloads keep running when it closes.
     */
    fun showRecognition() {
        val config = model.state.value.configuration ?: return
        dialogs.show { close ->
            val downloads by SpeechModelDownloads.state.collectAsState()
            val failures by SpeechModels.engineFailures.collectAsState()
            val zipformerSupported = SpeechModels.zipformerSupported()
            CockpitModal(voiceText(R.string.voice_recognition), footer = {
                CockpitTextAction(voiceText(R.string.ui_172), close)
            }) {
                val running = RecognitionEngine.effective(config.engine, config.language, zipformerSupported, failures.keys)
                RecognitionHeading(R.string.voice_recognition_engine)
                for (engine in listOf(RecognitionEngine.ZIPFORMER, RecognitionEngine.VOSK)) {
                    val needed = SpeechModelCatalog.forEngine(engine.engine, config.language)
                    val available = when (engine) {
                        RecognitionEngine.ZIPFORMER -> zipformerSupported && engine.engine.supports(config.language)
                        RecognitionEngine.VOSK -> needed?.id in downloads.installed
                    }
                    val detail = failures[engine.engine]?.let { voiceText(R.string.voice_engine_failed).format(it) }
                        ?: voiceText(engine.detail)
                    CockpitOptionRow(voiceText(engine.label), detail, engine == running, enabled = available) {
                        close()
                        if (engine.engine.key != config.engine || engine.engine in failures) model.engine(engine.engine.key)
                    }
                }
                Text(voiceText(R.string.voice_recognition_engines_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RecognitionHeading(R.string.voice_language)
                for (code in SpeechEngine.LANGUAGES) {
                    val needed = checkNotNull(SpeechModelCatalog.forEngine(SpeechEngine.VOSK, code))
                    val available = needed.id in downloads.installed ||
                        SpeechEngine.forLanguage(SpeechEngine.fromKey(config.engine), code,
                            zipformerSupported && SpeechEngine.ZIPFORMER !in failures) == SpeechEngine.ZIPFORMER
                    CockpitOptionRow(voiceText(recognitionLanguageName(code)),
                        if (available) null else voiceText(R.string.voice_model_needed).format(megabytes(needed)),
                        config.language == code, enabled = available) {
                        close()
                        if (config.language != code) model.language(code)
                    }
                    ModelDownload(needed, downloads)
                }
                RecognitionHeading(R.string.voice_online_title)
                CockpitChoice(voiceText(if (config.onlineConfigured) R.string.voice_recognition_on else R.string.voice_recognition_off),
                    voiceText(R.string.voice_recognition_online_detail), guideId = R.string.voice_online_title) {
                    close()
                    model.openOnline()
                }
            }
        }
    }

    /** Download, progress, failure and delete actions for one downloadable model. */
    @Composable
    private fun ModelDownload(speechModel: SpeechModel, downloads: SpeechModelDownloads.State) {
        val progress = downloads.active[speechModel.id]
        val failure = downloads.failed[speechModel.id]
        when {
            progress != null -> {
                val percent = if (progress.total > 0) (progress.done * 100 / progress.total).toInt() else 0
                Text(voiceText(R.string.voice_model_downloading).format(percent), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator({ percent / 100f }, Modifier.fillMaxWidth())
                CockpitTextAction(voiceText(R.string.voice_model_cancel)) { SpeechModelDownloads.cancel(speechModel) }
            }
            speechModel.id in downloads.installed ->
                CockpitTextAction(voiceText(R.string.voice_model_delete)) { SpeechModelDownloads.delete(app, speechModel) }
            else -> {
                failure?.let {
                    Text(voiceText(when (it) {
                        SpeechModelDownloads.Failure.STORAGE -> R.string.voice_model_failed_storage
                        SpeechModelDownloads.Failure.VERIFICATION -> R.string.voice_model_failed_verification
                        SpeechModelDownloads.Failure.NETWORK -> R.string.voice_model_failed_network
                    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                CockpitAction(voiceText(R.string.voice_model_download).format(megabytes(speechModel)),
                    { SpeechModelDownloads.download(app, speechModel) })
            }
        }
    }

    private fun megabytes(speechModel: SpeechModel) =
        voiceText(R.string.voice_model_megabytes).format((speechModel.downloadSize + 999_999) / 1_000_000)

    fun showDefaultLevels() {
        val config = model.state.value.configuration ?: return
        val defaults = config.catalog.all().filter { it.id().endsWith("LevelDefault") }
        val labels = defaults.map { "${it.title()} · ${config.levels[it.id()] ?: 2}" }.toTypedArray()
        dialogs.choices(voiceText(R.string.default_levels), labels, { index ->
            val command = defaults[index]
            dialogs.choices(command.title(), arrayOf("1", "2", "3"), { level -> model.level(command.id(), level + 1) })
        }, guideId = R.string.default_levels)
    }

    fun showVehicleCommands() {
        val config = model.state.value.configuration ?: return
        dialogs.show { close -> VehicleCommandsDialog(config.catalog, close) }
    }

    fun showWindowOptions() {
        val config = model.state.value.configuration ?: return
        dialogs.show { close ->
            var smoke by remember { mutableStateOf(config.doubleSmoke) }
            var ventilation by remember { mutableStateOf(config.doubleVentilation) }
            CockpitModal(voiceText(R.string.window_options), footer = {
                CockpitTextAction(voiceText(R.string.ui_89), close)
                CockpitAction(voiceText(R.string.ui_104), {
                    close()
                    model.windows(smoke, ventilation)
                }, primary = true)
            }) {
                CockpitSwitch(R.string.double_smoke, smoke) { smoke = it }
                CockpitSwitch(R.string.double_ventilation, ventilation) { ventilation = it }
            }
        }
    }

    fun showRecovery() {
        // Media session access moved to Settings → Sound and media.
        val choices = intArrayOf(R.string.ui_179, R.string.ui_181, R.string.ui_182, R.string.ui_183, R.string.ui_165)
        dialogs.choices(voiceText(R.string.ui_178), choices.map(::voiceText).toTypedArray(), { choice ->
            when (choice) {
                0 -> model.repairModel()
                1 -> addContact()
                2 -> save()
                3 -> load()
                4 -> model.restorePrevious()
            }
        })
    }

    fun showCommand(command: VoiceCatalog.Command) {
        val state = model.state.value
        if (state.busy) return
        val guide = describeVoiceCommand(command, command.id() in state.configuration?.assigned.orEmpty())
        dialogs.show { close ->
            VoiceCommandDetails(guide, close) { close(); chooseApp(command) }
        }
    }

    private fun chooseApp(command: VoiceCatalog.Command) {
        when {
            VoiceCommandUseCase.isWeather(command.id()) -> { model.message(R.string.weather_offline); return }
            command.id().startsWith("Call_") -> { model.message(R.string.ui_190); return }
        }
        dialogs.show { close ->
            CockpitAppPicker(voiceText(R.string.voice_app_for_command).format(command.title()), close, { entry ->
                close()
                model.app(command.id(), entry?.component()?.flattenToString().orEmpty())
            }, defaultChoice = voiceText(R.string.ui_191), backLabel = voiceText(R.string.ui_89))
        }
    }

    private fun addContact() {
        dialogs.show { close ->
            var name by remember { mutableStateOf("") }
            var phone by remember { mutableStateOf("") }
            CockpitModal(voiceText(R.string.ui_195), footer = {
                CockpitTextAction(voiceText(R.string.ui_89), close)
                CockpitAction(voiceText(R.string.ui_104), { close(); model.contact(name, phone) }, primary = true)
            }) {
                CockpitTextField(name, { name = it }, voiceText(R.string.ui_193), Modifier.fillMaxWidth(), guideId = R.string.ui_193)
                CockpitTextField(phone, { phone = it }, voiceText(R.string.ui_194), Modifier.fillMaxWidth(), guideId = R.string.ui_194,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            }
        }
    }
}
