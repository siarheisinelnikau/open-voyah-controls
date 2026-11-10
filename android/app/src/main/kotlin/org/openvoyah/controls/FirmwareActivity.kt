package org.openvoyah.controls

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.core.Firmware
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.data.settings.FirmwareMaintenance
import org.openvoyah.controls.presentation.maintenance.FirmwareAction
import org.openvoyah.controls.presentation.maintenance.FirmwareScreen
import org.openvoyah.controls.presentation.maintenance.MaintenanceViewModel
import org.openvoyah.controls.presentation.maintenance.MaintenanceWork
import org.openvoyah.controls.presentation.theme.CockpitDialogs
import org.openvoyah.controls.presentation.theme.ControlsTheme

class FirmwareActivity : ComponentActivity() {
    private val firmware by lazy { FirmwareMaintenance(applicationContext) }
    private val dialogs = CockpitDialogs(this)
    private val model: MaintenanceViewModel by viewModels { viewModelFactory { initializer { MaintenanceViewModel(work) } } }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val adapter = firmware; model.run { adapter.importDictionary(uri) } }
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { val adapter = firmware; model.run { adapter.exportDictionary(uri) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val product = "${Build.PRODUCT} · ${Firmware.identify(Build.PRODUCT)}"
        val windowSupported = FridaBridge.windowHookSupported()
        setContent {
            ControlsTheme {
                val state = model.state.collectAsStateWithLifecycle().value
                val windowStatus = when {
                    !windowSupported -> null
                    !FridaBridge.wasConfigured(this) -> R.string.cockpit_setup_required
                    firmware.windowEnabled() -> R.string.cockpit_enabled
                    else -> R.string.cockpit_disabled
                }
                FirmwareScreen(product, firmware.translationEnabled(), windowStatus,
                    state, ::finish, ::select)
            }
        }
    }

    private fun select(action: FirmwareAction) {
        if (model.state.value.working) return
        val adapter = firmware
        try {
            when (action) {
                FirmwareAction.CLUSTER -> startActivity(Intent(this, ClusterActivity::class.java))
                FirmwareAction.WINDOW_SERVICE -> {
                    val enable = !adapter.windowEnabled()
                    confirm(R.string.window_hook_title, if (enable) R.string.window_hook_enable else R.string.window_hook_disable) {
                        model.run { adapter.windowEnabled(enable) }
                    }
                }
                FirmwareAction.RUSSIAN -> model.run { adapter.preset("ru") }
                FirmwareAction.ENGLISH -> model.run { adapter.preset("en") }
                FirmwareAction.DISABLE_TRANSLATION -> model.run(adapter::disable)
                FirmwareAction.IMPORT -> importFile.launch(arrayOf("*/*"))
                FirmwareAction.EXPORT -> exportFile.launch("controls-localization.json")
                FirmwareAction.RESTORE_TRANSLATION -> model.run(adapter::restore)
                FirmwareAction.RESTORE_PROFILE -> confirm(R.string.profile_restore, R.string.profile_restore_info) {
                    model.run(adapter::restoreProfile)
                }
            }
        } catch (error: RuntimeException) {
            model.report(error.message ?: UiText.get(R.string.ui_85))
        }
    }

    private fun confirm(title: Int, message: Int, action: () -> Unit) {
        dialogs.message(UiText.get(title), UiText.get(message), UiText.get(R.string.ui_187), Runnable(action), UiText.get(R.string.ui_89))
    }

    override fun onDestroy() { dialogs.dismiss(); super.onDestroy() }

    private companion object {
        val work = MaintenanceWork(null, { UiText.get(R.string.ui_80) }, { UiText.get(R.string.ui_205) + it.message.orEmpty() })
    }
}
