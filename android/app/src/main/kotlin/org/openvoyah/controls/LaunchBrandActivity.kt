package org.openvoyah.controls

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.presentation.theme.LaunchBrand

/** Owns the launcher animation in a separate task from the cockpit. */
class LaunchBrandActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || !ValueAnimator.areAnimatorsEnabled() ||
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
}
