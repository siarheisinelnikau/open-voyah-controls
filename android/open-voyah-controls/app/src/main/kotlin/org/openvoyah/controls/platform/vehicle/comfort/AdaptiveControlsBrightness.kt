package org.openvoyah.controls.platform.vehicle.comfort

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.ViewTreeObserver
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openvoyah.controls.core.Firmware
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.vehicle.comfort.AdaptiveBrightnessCurve
import org.openvoyah.controls.domain.vehicle.comfort.AdaptiveBrightnessSettings

/** Android display authority for the Controls window only. */
internal object AdaptiveControlsBrightness {
    fun supported(context: Context): Boolean {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT < 29 || Firmware.identify(Build.PRODUCT) != "UNKNOWN" ||
            packages.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false
        // Covers head units that have a factory launcher but an unrecognized Android product name.
        return try {
            @Suppress("DEPRECATION")
            packages.getApplicationInfo("com.qinggan.app.launcher", 0)
            false
        } catch (_: PackageManager.NameNotFoundException) { true }
        catch (_: RuntimeException) { false }
    }

    suspend fun apply(activity: Activity, settings: AdaptiveBrightnessSettings) {
        if (Build.VERSION.SDK_INT >= 29 && settings.enabled && supported(activity) && AmbientLight.available(activity)) {
            try {
                applyToWindow(activity, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                ControlsLog.warn("adaptive_controls_brightness", failure, "outcome", "system_unavailable")
            }
        }
    }

    @RequiresApi(29)
    private suspend fun applyToWindow(activity: Activity, settings: AdaptiveBrightnessSettings) = coroutineScope {
        val resolver = activity.contentResolver
        val power = activity.getSystemService(PowerManager::class.java) ?: return@coroutineScope
        val window = activity.window
        val view = window.decorView
        val original = state(activity) ?: return@coroutineScope
        val focus = MutableStateFlow(view.hasWindowFocus())
        val manual = MutableStateFlow(false)
        val hot = MutableStateFlow(power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
        var applied: Float? = null

        fun release() {
            val previous = applied ?: return
            applied = null
            val attributes = window.attributes
            if (attributes.screenBrightness == previous) {
                attributes.screenBrightness = BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attributes
            }
        }

        val focusChanged = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            focus.value = focused
            if (!focused) release()
        }
        val focusTree = view.viewTreeObserver
        focusTree.addOnWindowFocusChangeListener(focusChanged)
        val overrideChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (state(activity) != original) {
                    manual.value = true
                    release()
                }
            }
        }
        val thermal = PowerManager.OnThermalStatusChangedListener {
            hot.value = it >= PowerManager.THERMAL_STATUS_SEVERE
            if (hot.value) release()
        }
        var thermalRegistered = false
        try {
            for (key in SYSTEM_KEYS) resolver.registerContentObserver(Settings.System.getUriFor(key), false, overrideChanged)
            power.addThermalStatusListener(activity.mainExecutor, thermal)
            thermalRegistered = true
            combine(focus, manual, hot) { focused, systemChanged, overheated -> focused && !systemChanged && !overheated }
                .distinctUntilChanged().collectLatest session@{ eligible ->
                    if (!eligible) { release(); return@session }
                    val curve = AdaptiveBrightnessCurve(settings)
                    AmbientLight.readings(activity).map { sample -> curve.level(sample?.lux ?: -1f) }
                        .distinctUntilChanged().collectLatest sample@{ level ->
                            if (level == null) { release(); return@sample }
                            delay(750)
                            if (!focus.value || manual.value || hot.value || !power.isInteractive ||
                                view.display?.displayId != Display.DEFAULT_DISPLAY ||
                                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || state(activity) != original) {
                                release()
                                return@sample
                            }
                            val attributes = window.attributes
                            if (attributes.screenBrightness == BRIGHTNESS_OVERRIDE_NONE || attributes.screenBrightness == applied) {
                                val target = level / 100f
                                if (attributes.screenBrightness != target) {
                                    attributes.screenBrightness = target
                                    window.attributes = attributes
                                    applied = target
                                }
                            }
                        }
                }
        } finally {
            release()
            resolver.unregisterContentObserver(overrideChanged)
            if (thermalRegistered) power.removeThermalStatusListener(thermal)
            if (focusTree.isAlive) focusTree.removeOnWindowFocusChangeListener(focusChanged)
            else view.viewTreeObserver.removeOnWindowFocusChangeListener(focusChanged)
        }
    }

    private fun state(context: Context): Pair<Int, Int>? = runCatching {
        val resolver = context.contentResolver
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        (mode to brightness).takeIf { mode in 0..1 && brightness in 0..255 }
    }.getOrNull()

    private val SYSTEM_KEYS = listOf(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS)
}
