package org.openvoyah.controls

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.core.voice.VolumePhrases
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.data.settings.VoiceSettingsDocuments
import org.openvoyah.controls.data.voice.CloudVoiceSettings
import org.openvoyah.controls.platform.voice.OfflineVoiceInput
import org.openvoyah.controls.presentation.voice.OnlineVoiceDialog
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.voice.VoiceAudioDialog
import org.openvoyah.controls.presentation.voice.VoiceOption
import org.openvoyah.controls.presentation.voice.VoiceSettingsScreen

/** Configuration and command catalog. The guarded popup owns recording and dispatch. */
class VoiceActivity : ComponentActivity() {
    private lateinit var settings: VoiceSettings
    private lateinit var documents: VoiceSettingsDocuments
    private lateinit var onlineSettings: CloudVoiceSettings
    private var configuration by mutableStateOf<Configuration?>(null)
    private var startupError by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var assigned by mutableStateOf<Set<String>>(emptySet())
    private var audioDialog by mutableStateOf(false)
    private var onlineDialog by mutableStateOf(false)
    private var onlineConfigured by mutableStateOf(false)
    private var onlineSaving by mutableStateOf(false)
    private var onlineFailed by mutableStateOf(false)
    private var settingsBusy = false
    private var activeDialog: AlertDialog? = null

    private val saveVoice = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { file -> transfer(file, false) }
    }
    private val loadVoice = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { file -> transfer(file, true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VoiceSettings(applicationContext)
        onlineSettings = CloudVoiceSettings(applicationContext)
        documents = VoiceSettingsDocuments(contentResolver, settings)
        setContent { ControlsTheme { Content() } }
        lifecycleScope.launch { loadScreen() }
    }

    private suspend fun loadScreen() {
        try {
            val config = withContext(Dispatchers.IO) {
                val catalog = settings.withContacts()
                val mappings = catalog.all().mapNotNull { command -> command.id().takeIf { settings.app(it).isNotEmpty() } }.toSet()
                Configuration(catalog, settings.language(), settings.profileLabel(),
                    settings.option("duckMedia"), settings.option("spokenFeedback"), mappings)
            }
            onlineConfigured = withContext(Dispatchers.IO) { onlineSettings.configured() }
            assigned = config.assigned
            configuration = config
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            startupError = text(R.string.ui_164) + failure.message.orEmpty()
        }
    }

    @Composable
    private fun Content() {
        val config = configuration
        if (config == null) {
            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(startupError ?: text(R.string.voice_catalog_loading), style = MaterialTheme.typography.titleMedium)
                    if (startupError != null) {
                        Button({ changeSettings { settings.restore() } }) { Text(text(R.string.ui_165)) }
                        TextButton({ startActivity(Intent(this@VoiceActivity, MainActivity::class.java)); finish() }) {
                            Text(text(R.string.ui_166))
                        }
                    }
                }
            }
            return
        }

        VoiceSettingsScreen(config.catalog, config.language, config.profile, assigned, status, ::showCommand, ::showOption, ::finish)
        if (onlineDialog) {
            OnlineVoiceDialog(onlineConfigured, onlineSaving, onlineFailed, { onlineDialog = false }, ::saveOnline)
        }
        if (audioDialog) {
            VoiceAudioDialog(config.duckMedia, config.spokenFeedback, { audioDialog = false }) { duck, feedback ->
                audioDialog = false
                changeSettings { settings.audio(duck, feedback) }
            }
        }
    }

    private fun showOption(option: VoiceOption) {
        if (settingsBusy) return
        when (option) {
            VoiceOption.LANGUAGE -> choose(R.string.voice_language, arrayOf("Русский", "English")) {
                changeSettings { settings.language(if (it == 0) "ru" else "en") }
            }
            VoiceOption.PROFILE -> choose(R.string.ui_189, PROFILES) {
                changeSettings { settings.profile(PROFILES[it]) }
            }
            VoiceOption.LEVELS -> showDefaultLevels()
            VoiceOption.WINDOWS -> showWindowOptions()
            VoiceOption.AUDIO -> audioDialog = true
            VoiceOption.ONLINE -> {
                onlineFailed = false
                onlineDialog = true
            }
            VoiceOption.RECOVERY -> showRecoveryOptions()
        }
    }

    private fun saveOnline(enabled: Boolean, key: String) {
        if (onlineSaving) return
        onlineSaving = true
        onlineFailed = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { onlineSettings.save(enabled, key) }
                onlineConfigured = enabled
                onlineDialog = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onlineFailed = true
            } finally {
                onlineSaving = false
            }
        }
    }

    private fun showDefaultLevels() {
        val defaults = configuration?.catalog?.all()?.filter { it.id().endsWith("LevelDefault") }.orEmpty()
        val levels = defaults.map { "${it.title()} · ${settings.level(it.id())}" }.toTypedArray()
        choose(R.string.default_levels, levels) { index ->
            val command = defaults[index]
            showDialog(AlertDialog.Builder(this).setTitle(command.title()).setItems(arrayOf("1", "2", "3")) { _, level ->
                changeSettings(reload = false) { settings.level(command.id(), level + 1) }
            })
        }
    }

    private fun showWindowOptions() {
        val keys = arrayOf("doubleSmoke", "doubleVentilation")
        val values = BooleanArray(keys.size) { settings.option(keys[it]) }
        showDialog(AlertDialog.Builder(this).setTitle(text(R.string.window_options))
            .setMultiChoiceItems(arrayOf(text(R.string.double_smoke), text(R.string.double_ventilation)),
                values) { _, index, checked -> values[index] = checked }
            .setNegativeButton(text(R.string.ui_89), null)
            .setPositiveButton(text(R.string.ui_104)) { _, _ ->
                changeSettings(reload = false) { keys.forEachIndexed { index, key -> settings.option(key, values[index]) } }
            })
    }

    private fun showRecoveryOptions() {
        val choices = intArrayOf(R.string.ui_179, R.string.ui_180, R.string.ui_181, R.string.ui_182, R.string.ui_183, R.string.ui_165)
        choose(R.string.ui_178, choices.map(::text).toTypedArray()) { choice ->
            when (choice) {
                0 -> changeSettings(reload = false, onSaved = { status = text(R.string.ui_184) }) {
                    OfflineVoiceInput(applicationContext, duckMedia = false).repair(settings.language())
                }
                1 -> systemAction { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                2 -> addContact()
                3 -> systemAction { saveVoice.launch("controls-voice.json") }
                4 -> systemAction { loadVoice.launch(arrayOf("*/*")) }
                5 -> changeSettings { settings.restore() }
            }
        }
    }

    private fun showCommand(command: VoiceCatalog.Command) {
        if (settingsBusy) return
        val details = if (command.id() == VolumePhrases.COMMAND_ID) {
            String.format(Locale.ROOT, text(R.string.voice_volume_examples), command.title())
        } else command.phrases().joinToString("\n")
        val dialog = AlertDialog.Builder(this).setTitle(command.title()).setNegativeButton(text(R.string.ui_172), null)
        if (command.id() in setOf("RestartMusic", "RestartNavigator")) {
            dialog.setMessage(details + "\n\n" + text(R.string.voice_restart_assignment))
        } else {
            dialog.setMessage(details).setNeutralButton(text(R.string.ui_188)) { _, _ -> chooseApp(command) }
        }
        showDialog(dialog)
    }

    private fun chooseApp(command: VoiceCatalog.Command) {
        when {
            VoiceCommandUseCase.isWeather(command.id()) -> { status = text(R.string.weather_offline); return }
            command.id().startsWith("Call_") -> { status = text(R.string.ui_190); return }
        }
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { InstalledApps.list(applicationContext) }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            val labels = (listOf(text(R.string.ui_191)) + apps.map { it.label() }).toTypedArray()
            showDialog(AlertDialog.Builder(this@VoiceActivity).setTitle(text(R.string.ui_192) + command.title() + "»")
                .setItems(labels) { _, index ->
                    val component = if (index == 0) "" else apps[index - 1].component().flattenToString()
                    changeSettings(reload = false, onSaved = {
                        assigned = if (component.isEmpty()) assigned - command.id() else assigned + command.id()
                    }) { settings.app(command.id(), component) }
                })
        }
    }

    private fun addContact() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), 0, px(20), 0)
        }
        val name = EditText(this).apply { hint = text(R.string.ui_193); setSingleLine() }
        val phone = EditText(this).apply { hint = text(R.string.ui_194); inputType = InputType.TYPE_CLASS_PHONE }
        fields.addView(name)
        fields.addView(phone)
        showDialog(AlertDialog.Builder(this).setTitle(text(R.string.ui_195)).setView(fields)
            .setNegativeButton(text(R.string.ui_89), null)
            .setPositiveButton(text(R.string.ui_104)) { _, _ ->
                val enteredName = name.text.toString()
                val enteredPhone = phone.text.toString()
                changeSettings { settings.contact(enteredName, enteredPhone) }
            })
    }

    private fun changeSettings(reload: Boolean = true, onSaved: () -> Unit = {}, write: suspend () -> Unit) {
        if (settingsBusy) return
        settingsBusy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { write() }
                if (reload) recreate() else onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showError(failure.message.orEmpty())
            } finally {
                settingsBusy = false
            }
        }
    }

    private fun transfer(file: Uri, restoring: Boolean) {
        if (settingsBusy) return
        settingsBusy = true
        lifecycleScope.launch {
            try {
                if (restoring) {
                    documents.restore(file)
                    recreate()
                } else {
                    documents.save(file)
                    status = text(R.string.ui_196)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                showError(text(R.string.ui_197) + failure.message.orEmpty())
            } finally {
                settingsBusy = false
            }
        }
    }

    private fun systemAction(open: () -> Unit) {
        try { open() } catch (failure: RuntimeException) { showError(text(R.string.ui_85)) }
    }

    private fun choose(title: Int, labels: Array<String>, selected: (Int) -> Unit) {
        showDialog(AlertDialog.Builder(this).setTitle(text(title)).setItems(labels) { _, index -> selected(index) }
            .setNegativeButton(text(R.string.ui_89), null))
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        activeDialog?.dismiss()
        activeDialog = builder.show()
    }

    private fun showError(message: String) {
        if (configuration == null) startupError = message else status = message
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        super.onDestroy()
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun text(id: Int) = UiText.get(id)

    private data class Configuration(
        val catalog: VoiceCatalog,
        val language: String,
        val profile: String,
        val duckMedia: Boolean,
        val spokenFeedback: Boolean,
        val assigned: Set<String>,
    )

    private companion object {
        val PROFILES = arrayOf("UNKNOWN", "FREE", "FREE_REST", "FREE_REST_BY", "FREE_REST_OVERSEA", "NXP",
            "FREE_NORWAY", "DREAMER", "DREAMER_REST", "DREAMER_REST_MI", "DREAMER_REST_MI_V2")
    }
}
