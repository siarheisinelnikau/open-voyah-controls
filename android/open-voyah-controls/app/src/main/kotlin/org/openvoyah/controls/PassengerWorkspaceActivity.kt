package org.openvoyah.controls

import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.platform.display.PassengerWorkspaceLauncher
import org.openvoyah.controls.presentation.display.PassengerWorkspaceScreen
import org.openvoyah.controls.presentation.display.PassengerWorkspaceViewModel
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.theme.controlsAccentOverlay

/** A separate task because the two displays can show Controls at the same time. */
class PassengerWorkspaceActivity : ComponentActivity() {
    private var topInset by mutableIntStateOf(0)
    private lateinit var model: PassengerWorkspaceViewModel
    private val displays get() = getSystemService(DisplayManager::class.java)
    private var listening = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) { verifyDisplay() }
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == PassengerWorkspaceLauncher.DISPLAY_ID) finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val appearance = AppearancePreferences(applicationContext).load()
        if (appearance.palette == AppPalette.DAY) setTheme(R.style.AppTheme_Day)
        theme.applyStyle(controlsAccentOverlay(appearance.accent, appearance.palette), true)
        super.onCreate(savedInstanceState)
        if (!verifyDisplay() || !acceptInset(intent)) return
        model = ViewModelProvider(this, viewModelFactory {
            initializer { PassengerWorkspaceViewModel(applicationContext) }
        })[PassengerWorkspaceViewModel::class.java]
        setContent {
            ControlsTheme(systemBars = window) {
                PassengerWorkspaceScreen(model, topInset, open = { model.open(this, it) }, exit = ::finishAndRemoveTask)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (verifyDisplay() && acceptInset(intent)) setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing && verifyDisplay()) {
            displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            listening = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (verifyDisplay() && ::model.isInitialized) model.refresh()
    }

    override fun onStop() {
        if (listening) {
            displays.unregisterDisplayListener(listener)
            listening = false
        }
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        verifyDisplay()
    }

    private fun verifyDisplay(): Boolean {
        if (PassengerWorkspaceLauncher.onPassengerDisplay(this)) return true
        finishAndRemoveTask()
        return false
    }

    private fun acceptInset(intent: Intent): Boolean {
        val value = intent.getIntExtra(PassengerWorkspaceLauncher.TOP_INSET, -1)
        if (value in 0..1024) {
            topInset = value
            return true
        }
        finishAndRemoveTask()
        return false
    }
}
