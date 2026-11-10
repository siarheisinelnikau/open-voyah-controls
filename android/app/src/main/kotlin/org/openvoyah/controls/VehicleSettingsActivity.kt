package org.openvoyah.controls

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.openvoyah.controls.platform.ui.VehicleSettingsDestination
import org.openvoyah.controls.platform.ui.VehicleSettingsLauncher
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.vehicle.VehicleSettingsScreen

class VehicleSettingsActivity : ComponentActivity() {
    private val launcher by lazy { VehicleSettingsLauncher(applicationContext) }
    private var available by mutableStateOf<Set<VehicleSettingsDestination>>(emptySet())
    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ControlsTheme { VehicleSettingsScreen(available, status, ::finish, ::openStock, ::openManual) } }
    }

    override fun onResume() {
        super.onResume()
        available = VehicleSettingsDestination.entries.filter { stockIntent(it) != null }.toSet()
    }

    private fun stockIntent(destination: VehicleSettingsDestination): Intent? = try {
        launcher.intent(destination)
    } catch (_: RuntimeException) { null }

    private fun openStock(destination: VehicleSettingsDestination) {
        val intent = stockIntent(destination)
        if (intent == null) {
            available = available - destination
            status = UiText.get(R.string.vehicle_settings_unavailable)
        } else open(intent)
    }

    private fun openManual() = open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.voyah-global.com/service")))

    private fun open(intent: Intent) {
        try { startActivity(intent); status = null }
        catch (_: RuntimeException) { status = UiText.get(R.string.ui_85) }
    }
}
