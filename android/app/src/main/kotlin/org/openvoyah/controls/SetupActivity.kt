package org.openvoyah.controls

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.platform.display.useControlsDensity
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.setup.SetupStepText
import org.openvoyah.controls.presentation.setup.SetupSteps
import org.openvoyah.controls.presentation.setup.SetupViewModel
import org.openvoyah.controls.presentation.setup.rememberSetupClock
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitBackdrop
import org.openvoyah.controls.presentation.theme.CockpitFeedback
import org.openvoyah.controls.presentation.theme.CockpitHeading
import org.openvoyah.controls.presentation.theme.CockpitMotion
import org.openvoyah.controls.presentation.theme.CockpitReveal
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.theme.LocalCockpitMotion

/**
 * Protected foreground entry: long first-run checks survive a slow model load. Each step is a row with its own
 * status and elapsed time; the summary and the next actions appear when the check ends.
 */
class SetupActivity : ComponentActivity() {
    private lateinit var model: SetupViewModel
    private var counted = false

    private fun requestNonce(intent: Intent): String? = intent.getStringExtra("request")
        ?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        useControlsDensity(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nonce = requestNonce(intent)
        if (nonce == null) { finish(); return }
        openPages++
        counted = true
        model = ViewModelProvider(this)[SetupViewModel::class.java]
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ControlsTheme(systemBars = window) {
                val motion = LocalCockpitMotion.current
                val text = remember { SetupStepText(UiText::get) }
                val now = rememberSetupClock(!state.finished)
                var copied by remember(state.finished) { mutableStateOf(false) }
                CockpitBackdrop {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp)) {
                        Column(
                            Modifier.widthIn(max = 800.dp).fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            CockpitHeading(UiText.get(if (state.finished) R.string.setup_result else R.string.setup_title))
                            // Real work runs until the check ends: the bar marks it and the rows say which step it is.
                            if (!state.finished) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(text.summary(state.progress, now), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            CockpitReveal(state.finished) {
                                AnimatedContent(state.message to state.failed, transitionSpec = {
                                    if (motion.enabled) {
                                        fadeIn(CockpitMotion.change()).togetherWith(fadeOut(CockpitMotion.exit())) using
                                            SizeTransform { _, _ -> CockpitMotion.change() }
                                    } else ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                                }, label = "setup result") { (message, failed) ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(UiText.get(message), style = MaterialTheme.typography.bodyLarge,
                                            color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified)
                                        if (failed) {
                                            Text(UiText.get(R.string.setup_next_retry), style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            SetupSteps(state.progress, text, now)
                            CockpitReveal(state.finished) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (state.failed) {
                                            CockpitAction(UiText.get(R.string.setup_retry), { model.retry() },
                                                Modifier.heightIn(min = 56.dp), enabled = state.finished, primary = true)
                                        }
                                        CockpitAction(UiText.get(R.string.setup_open), {
                                            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                                            finish()
                                        }, Modifier.heightIn(min = 56.dp), enabled = state.finished, primary = !state.failed)
                                        // A failed check can leave the speech model or the connection missing; trying voice would only fail again.
                                        if (state.failed) {
                                            CockpitAction(UiText.get(R.string.setup_open_diagnostics), {
                                                startActivity(ControlsNavigation.intent(this@SetupActivity, ControlsRoute.DIAGNOSTICS))
                                            }, Modifier.heightIn(min = 56.dp), enabled = state.finished)
                                        } else {
                                            CockpitAction(UiText.get(R.string.quick_voice_try), {
                                                startActivity(QuickVoiceActivity.intent(this@SetupActivity))
                                            }, Modifier.heightIn(min = 56.dp), enabled = state.finished)
                                        }
                                        CockpitAction(UiText.get(R.string.setup_copy_details), {
                                            copied = copyDetails(text.details(state.progress, UiText.get(state.message), now))
                                        }, Modifier.heightIn(min = 56.dp), enabled = state.finished)
                                    }
                                    CockpitFeedback(if (copied) UiText.get(R.string.setup_details_copied) else null)
                                }
                            }
                        }
                    }
                }
            }
        }
        model.start(nonce)
    }

    /** The details hold step names, durations and error codes only: no key, script output or private data. */
    private fun copyDetails(details: String): Boolean = try {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(UiText.get(R.string.setup_details_title), details))
        true
    } catch (error: RuntimeException) {
        false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val nonce = requestNonce(intent) ?: return
        setIntent(intent)
        model.start(nonce)
    }

    override fun onDestroy() {
        if (counted) openPages--
        counted = false
        super.onDestroy()
    }

    companion object {
        /** Setup pages that exist in this process; the startup intro does not start over one. Main thread. */
        internal var openPages = 0
            private set
    }
}
