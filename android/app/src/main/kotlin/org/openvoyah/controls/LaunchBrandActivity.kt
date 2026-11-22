package org.openvoyah.controls

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.openvoyah.controls.data.settings.AppearancePreferences
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.platform.display.useControlsDensity
import org.openvoyah.controls.platform.startup.StartupWork
import org.openvoyah.controls.presentation.theme.LaunchBrand

/** Owns the launcher animation in a separate task from the cockpit. */
class LaunchBrandActivity : ComponentActivity() {
    private var releaseStartupWork: (() -> Unit)? = null

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
        // The cold start of this process would otherwise load the speech model while the intro draws.
        releaseStartupWork = StartupWork.hold()
        // The window is transparent until the intro shows; until then touches reach home underneath.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        setContent {
            // The intro draws the dark cockpit backdrop itself; the window stays transparent.
            LaunchBrand(
                appearance = appearance,
                onShown = { window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) },
                onFinished = { run ->
                    ControlsLog.info("launch_brand", "outcome", if (run.completed) "played" else "closed",
                        "settle", if (run.settled) "steady" else "limit", "settle_ms", run.settleMillis,
                        "frames", run.frames, "slow_frames", run.slowFrames, "longest_gap_ms", run.longestGapMillis,
                        "elapsed_ms", run.elapsedMillis)
                    finish()
                },
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            ControlsLog.info("launch_brand", "outcome", "interrupted")
            finish()
        }
    }

    override fun onDestroy() {
        releaseStartupWork?.invoke()
        releaseStartupWork = null
        super.onDestroy()
    }

    companion object {
        internal const val SHOW_BOOT_BRAND = "org.openvoyah.controls.action.SHOW_BOOT_BRAND"
    }
}
