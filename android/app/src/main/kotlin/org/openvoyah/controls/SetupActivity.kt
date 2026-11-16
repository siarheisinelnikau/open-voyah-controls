package org.openvoyah.controls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openvoyah.controls.presentation.setup.SetupViewModel
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.theme.CockpitAction
import org.openvoyah.controls.presentation.theme.CockpitMotion

/** Protected foreground entry: long first-run checks survive a slow model load. */
class SetupActivity : ComponentActivity() {
    private lateinit var model: SetupViewModel

    private fun requestNonce(intent: Intent): String? = intent.getStringExtra("request")
        ?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nonce = requestNonce(intent)
        if (nonce == null) { finish(); return }
        model = ViewModelProvider(this)[SetupViewModel::class.java]
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ControlsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(32.dp)) {
                        Column(
                            Modifier.widthIn(max = 800.dp).fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Text(
                                UiText.get(if (state.finished) R.string.setup_result else R.string.setup_title),
                                style = MaterialTheme.typography.headlineLarge
                            )
                            AnimatedContent(state.message, transitionSpec = {
                                fadeIn(tween(CockpitMotion.CHANGE)).togetherWith(fadeOut(tween(CockpitMotion.EXIT)))
                            }, label = "setup progress") { message ->
                                Text(UiText.get(message), style = MaterialTheme.typography.bodyLarge)
                            }
                            AnimatedVisibility(state.finished,
                                enter = fadeIn(tween(CockpitMotion.CHANGE)) + expandVertically(tween(CockpitMotion.CHANGE)),
                                exit = fadeOut(tween(CockpitMotion.EXIT)) + shrinkVertically(tween(CockpitMotion.EXIT))) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CockpitAction(UiText.get(R.string.setup_open), {
                                        startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                                        finish()
                                    }, Modifier.heightIn(min = 56.dp), enabled = state.finished, primary = true)
                                    CockpitAction(UiText.get(R.string.quick_voice_try), {
                                        startActivity(QuickVoiceActivity.intent(this@SetupActivity))
                                    }, Modifier.heightIn(min = 56.dp), enabled = state.finished)
                                }
                            }
                        }
                    }
                }
            }
        }
        model.start(nonce)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val nonce = requestNonce(intent) ?: return
        setIntent(intent)
        model.start(nonce)
    }
}
