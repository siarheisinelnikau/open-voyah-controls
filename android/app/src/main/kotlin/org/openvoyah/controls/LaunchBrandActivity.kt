package org.openvoyah.controls

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.presentation.theme.LaunchBrand

/** Owns the launcher animation in a separate task from the cockpit. */
class LaunchBrandActivity : ComponentActivity() {
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
        if (intent.action != SHOW_BOOT_BRAND || savedInstanceState != null || !ValueAnimator.areAnimatorsEnabled() ||
            !AppearancePreferences(applicationContext).load().animateRoutes) {
            finish()
            return
        }
        setContent { LaunchBrand(::finish) }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) finish()
    }

    companion object {
        internal const val SHOW_BOOT_BRAND = "org.openvoyah.controls.action.SHOW_BOOT_BRAND"
    }
}
