package org.openvoyah.controls

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.settings.AppPalette
import org.openvoyah.controls.platform.display.useControlsDensity
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.theme.LaunchBrand

/** Owns the launcher animation in a separate task from the cockpit. */
class LaunchBrandActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        useControlsDensity(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            // Pinned icons may still carry the launcher component from an older APK.
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        val appearance = AppearancePreferences(applicationContext).load()
        val skipped = when {
            intent.action != SHOW_BOOT_BRAND -> "unexpected_action"
            savedInstanceState != null -> "recreated"
            !ValueAnimator.areAnimatorsEnabled() -> "animators_disabled"
            !appearance.animateRoutes -> "transitions_disabled"
            else -> null
        }
        if (skipped != null) {
            ControlsLog.info("launch_brand", "outcome", "skipped", "reason", skipped)
            finish()
            return
        }
        ControlsLog.info("launch_brand", "outcome", "shown")
        setContent {
            // The window starts on the dark cockpit background, so the intro keeps the dark roles in every palette
            // and only takes the accent, contrast and type choices from Appearance.
            ControlsTheme(appearance = appearance, temporaryPalette = AppPalette.DARK) { LaunchBrand(::finish) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) finish()
    }

    companion object {
        internal const val SHOW_BOOT_BRAND = "org.openvoyah.controls.action.SHOW_BOOT_BRAND"
    }
}
