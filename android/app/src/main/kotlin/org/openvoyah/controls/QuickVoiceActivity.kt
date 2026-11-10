package org.openvoyah.controls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openvoyah.controls.core.VoiceCatalog
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.platform.voice.OfflineVoiceFeedback
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.voice.QuickVoicePopup
import org.openvoyah.controls.presentation.voice.VoiceViewModel
import org.openvoyah.controls.presentation.voice.VoiceViewModel.Phase

/** The OEM button opens this floating Android window on the selected display. */
class QuickVoiceActivity : ComponentActivity() {
    private var model by mutableStateOf<VoiceViewModel?>(null)
    private var startup by mutableStateOf(VoiceViewModel.State(Phase.PREPARING, UiText.get(R.string.ui_139)))
    private var feedback: OfflineVoiceFeedback? = null
    private var foreground = false
    private var startPending = true
    private var permissionPending = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) startIfReady()
        else {
            startPending = false
            showFailure(UiText.get(R.string.quick_mic_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply { y = px(36) }
        setContent {
            ControlsTheme {
                val viewModel = model
                if (viewModel == null) QuickVoicePopup(startup)
                else {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    QuickVoicePopup(state)
                }
            }
        }
        window.setLayout(minOf(px(440), resources.displayMetrics.widthPixels - px(24)).coerceAtLeast(1),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        if (savedInstanceState != null) {
            finish()
            return
        }
        lifecycleScope.launch { prepareCapture() }
    }

    private suspend fun prepareCapture() {
        val settings = VoiceSettings(applicationContext)
        try {
            val configuration = withContext(Dispatchers.IO) {
                Configuration(settings.withContacts(), settings.language(), settings.option("duckMedia"),
                    settings.option("spokenFeedback"))
            }
            val viewModel = VoiceScreenFactory.create(this, configuration.catalog, settings,
                configuration.language, configuration.duckMedia)
            if (configuration.spokenFeedback) {
                feedback = OfflineVoiceFeedback(this, configuration.language)
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        viewModel.feedback.collect { phrase ->
                            if (foreground) feedback?.speak(phrase)
                        }
                    }
                }
            }
            model = viewModel
            lifecycleScope.launch {
                viewModel.state.first { it.phase == Phase.DONE }
                delay(RESULT_MS)
                finish()
            }
            startIfReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            showFailure(UiText.get(R.string.ui_164) + failure.message.orEmpty())
        }
    }

    private fun startIfReady() {
        val viewModel = model ?: return
        if (!startPending || !foreground || !hasWindowFocus() || permissionPending) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startPending = false
        viewModel.listen()
    }

    private fun showFailure(message: String) {
        val viewModel = model
        if (viewModel != null) viewModel.finish(VoiceResult(message, failed = true))
        else {
            startup = VoiceViewModel.State(Phase.DONE, message, failed = true)
            lifecycleScope.launch {
                delay(RESULT_MS)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        startIfReady()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startIfReady()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
    }

    override fun onPause() {
        foreground = false
        feedback?.stop()
        if (!permissionPending) {
            model?.close()
            finish()
        }
        super.onPause()
    }

    override fun onDestroy() {
        model?.close()
        feedback?.close()
        super.onDestroy()
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private data class Configuration(
        val catalog: VoiceCatalog,
        val language: String,
        val duckMedia: Boolean,
        val spokenFeedback: Boolean,
    )

    companion object {
        private const val RESULT_MS = 2_000L

        @JvmStatic
        fun intent(context: Context): Intent =
            Intent(context, QuickVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
