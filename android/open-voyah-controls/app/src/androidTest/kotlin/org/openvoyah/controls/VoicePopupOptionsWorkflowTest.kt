package org.openvoyah.controls

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.core.voice.VoiceAudioOptions
import org.openvoyah.controls.core.voice.VoiceAudioOptions.EndCue
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ListeningWindow
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ReadyCueStyle
import org.openvoyah.controls.core.voice.VoiceAudioOptions.ResultDuration
import org.openvoyah.controls.core.voice.VoiceAudioOptions.SpokenReplyChannel
import org.openvoyah.controls.core.voice.VoiceCommandUseCase
import org.openvoyah.controls.data.settings.VoiceSettings
import org.openvoyah.controls.domain.voice.SpeechEvent
import org.openvoyah.controls.domain.voice.VoiceFeedback
import org.openvoyah.controls.domain.voice.VoiceInput
import org.openvoyah.controls.domain.voice.VoiceResult
import org.openvoyah.controls.presentation.navigation.ControlsNavigation
import org.openvoyah.controls.presentation.navigation.ControlsRoute
import org.openvoyah.controls.presentation.theme.ControlsTheme
import org.openvoyah.controls.presentation.voice.QuickVoicePopup
import org.openvoyah.controls.presentation.voice.VoiceSettingsViewModel
import org.openvoyah.controls.presentation.voice.VoiceViewModel

class VoicePopupOptionsWorkflowTest : InstrumentationTestCase() {
    private val app get() = instrumentation.targetContext

    fun testCaptureAndPopupPreferences() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val voice = app.getSharedPreferences("voice", 0)
        val original = voice.all
        val ui = app.getSharedPreferences("ui", 0)
        val previousLanguage = ui.getString("language", null)
        val updates = app.getSharedPreferences("updates", 0)
        val previousStartup = updates.all["check_on_startup"] as? Boolean
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        val settings = VoiceSettings(app)
        var host: MainActivity? = null
        var quick: QuickVoiceActivity? = null
        try {
            check(updates.edit().putBoolean("check_on_startup", false).commit())
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            lateinit var editor: VoiceSettingsViewModel
            fun openVoiceSettings(language: String): MainActivity {
                UiText.language(language)
                settings.importJson("""{"schema":1,"values":{"language":"$language"}}""")
                settings.audio(false, false, VoiceAudioOptions.DEFAULTS, language, settings.offlineVoice(language), "")
                val activity = instrumentation.startActivitySync(ControlsNavigation.intent(app, ControlsRoute.VOICE)) as MainActivity
                host = activity
                click(UiText.get(R.string.voice_audio_settings))
                instrumentation.runOnMainSync {
                    editor = ViewModelProvider(activity)["voice-settings", VoiceSettingsViewModel::class.java]
                }
                return activity
            }
            fun saveAudio(description: String, expected: (VoiceAudioOptions) -> Boolean) {
                click(UiText.get(R.string.ui_104))
                await(description) {
                    val state = editor.state.value
                    val configuration = state.configuration ?: return@await false
                    val saved = settings.audioOptions()
                    !state.busy && !state.audioOpen && configuration.audioOptions == saved &&
                        configuration.spokenFeedback == settings.option("spokenFeedback") &&
                        configuration.readyPrompt == settings.readyPrompt(configuration.language) && expected(saved)
                }
            }
            fun reopenAudio() {
                val saveLabel = UiText.get(R.string.ui_104)
                perform(UiText.get(R.string.voice_audio_settings)) { matches ->
                    val state = editor.state.value
                    val saveVisible = nodes().any { it.isVisibleToUser && it.matches(saveLabel) }
                    if (!state.audioOpen && !state.busy && !saveVisible) clickNode(matches)
                    state.audioOpen && saveVisible
                }
            }
            fun saveAndReopen(cue: EndCue, gain: Float) {
                saveAudio("Saved $cue and refreshed the voice screen") {
                    it.endCue() == cue && it.endCueVolumePercent() == 37 && it.endCueGain() == gain
                }
                reopenAudio()
            }
            fun verifyEndCueVolume(captureVolume: Boolean) {
                val soft = UiText.get(R.string.voice_end_cue_soft)
                val softReset = UiText.get(R.string.voice_end_cue_preset_volume).format(25)
                val standardReset = UiText.get(R.string.voice_end_cue_preset_volume).format(65)
                click(soft)
                setEndCueVolume(37)
                checkCueRow(37, softReset)
                if (captureVolume) capture("voice-end-sound-volume")
                saveAndReopen(EndCue.SOFT, .37f)
                click(UiText.get(R.string.voice_end_cue_off))
                saveAndReopen(EndCue.OFF, 0f)
                click(soft)
                checkCueRow(37, softReset)
                click(UiText.get(R.string.voice_end_cue_standard))
                saveAndReopen(EndCue.STANDARD, .37f)
                checkCueRow(37, standardReset)
                click(standardReset)
                saveAudio("Reset restores the selected standard sound's preset volume") {
                    it.endCue() == EndCue.STANDARD && it.endCueVolumePercent() == null && it.endCueGain() == .65f
                }
            }
            val russian = openVoiceSettings("ru")
            verifyEndCueVolume(captureVolume = false)
            instrumentation.runOnMainSync { russian.finish() }
            await("Russian voice settings closed") { russian.isDestroyed }

            val activity = openVoiceSettings("en")
            verifyEndCueVolume(captureVolume = true)
            reopenAudio()
            click(UiText.get(R.string.voice_end_cue_soft))
            await("Soft sound uses its own preset volume") {
                nodes().any { it.isVisibleToUser && it.text?.toString() == "25%" }
            }
            click(UiText.get(R.string.voice_capture_popup))
            click(choice(R.string.voice_listening_limit, R.string.voice_seconds_6))
            click(choice(R.string.voice_result_duration, R.string.voice_seconds_4))
            click(UiText.get(R.string.voice_show_recognized_phrase))
            capture()
            saveAudio("Saved voice settings") { saved ->
                saved.listeningWindow() == ListeningWindow.SIX_SECONDS && saved.resultDuration() == ResultDuration.FOUR_SECONDS &&
                    !saved.showRecognizedPhrase() && saved.endCueVolumePercent() == null && saved.endCueGain() == .25f
            }
            reopenAudio()
            click(UiText.get(R.string.voice_spoken_feedback))
            val media = UiText.get(R.string.voice_channel_media)
            val spoken = UiText.get(R.string.voice_ready_spoken)
            click(media)
            assertSelected(media)
            capture("voice-spoken-reply-channel")
            click(UiText.get(R.string.voice_ready_cue))
            click(spoken)
            assertSelected(spoken)
            setReadyPhrase("x".repeat(VoiceAudioOptions.MAX_READY_PROMPT_CHARACTERS + 1))
            val error = UiText.get(R.string.voice_ready_phrase_invalid).format(VoiceAudioOptions.MAX_READY_PROMPT_CHARACTERS)
            perform(error) { it.isNotEmpty() }
            await("Save disabled for an overlong ready phrase") {
                val save = nodes().filter { it.isVisibleToUser && it.matches(UiText.get(R.string.ui_104)) }
                save.isNotEmpty() && clickableButton(save) == null
            }
            assertTrue(editor.state.value.audioOpen)
            assertTrue(settings.readyPrompt("en").isEmpty())
            val phrase = "Go ahead."
            setReadyPhrase(phrase)
            capture("voice-ready-prompt")
            saveAudio("Saved the custom ready phrase and media reply channel") { saved ->
                saved.readyCue() && settings.option("spokenFeedback") && saved.readyCueStyle() == ReadyCueStyle.SPOKEN &&
                    saved.spokenReplyChannel() == SpokenReplyChannel.MEDIA && settings.readyPrompt("en") == phrase
            }
            reopenAudio()
            assertSelected(media)
            assertSelected(spoken)
            click(UiText.get(R.string.voice_ready_tone))
            saveAudio("Tone retains the saved phrase") { it.readyCueStyle() == ReadyCueStyle.TONE && settings.readyPrompt("en") == phrase }
            reopenAudio()
            click(spoken)
            click(UiText.get(R.string.voice_ready_phrase_reset))
            click(UiText.get(R.string.voice_ready_cue))
            click(UiText.get(R.string.voice_spoken_feedback))
            saveAudio("Default phrase restored and muted options retained their selected sound and channel") { saved ->
                !saved.readyCue() && !settings.option("spokenFeedback") && saved.readyCueStyle() == ReadyCueStyle.SPOKEN &&
                    saved.spokenReplyChannel() == SpokenReplyChannel.MEDIA && settings.readyPrompt("en").isEmpty()
            }
            assertPopupPhrase(activity, settings)

            // A local preparation error exercises the real result timer without starting the microphone.
            check(voice.edit().putString("contact.popup-test", "{}").commit())
            val popup = instrumentation.startActivitySync(QuickVoiceActivity.intent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as QuickVoiceActivity
            quick = popup
            val message = UiText.get(R.string.ui_164) + UiText.get(R.string.ui_210)
            await("Compact error result") { nodes().any { it.matches(message) && it.isVisibleToUser } }
            val visibleAt = SystemClock.elapsedRealtime()
            await("Popup closes after the chosen display time") { popup.isFinishing || popup.isDestroyed }
            assertTrue("Four-second result closed too early", SystemClock.elapsedRealtime() - visibleAt >= 3_200)
        } finally {
            instrumentation.runOnMainSync { quick?.finish(); host?.finish() }
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            check(ui.edit().putString("language", previousLanguage).commit())
            val restore = voice.edit().clear()
            original.forEach { (key, value) -> restore.putString(key, value as String) }
            check(restore.commit())
            check(updates.edit().apply {
                if (previousStartup == null) remove("check_on_startup") else putBoolean("check_on_startup", previousStartup)
            }.commit())
        }
    }

    private fun assertPopupPhrase(activity: MainActivity, settings: VoiceSettings) {
        val phrase = "turn it up"
        val status = "Local action requested"
        val input = object : VoiceInput {
            override fun listen(language: String, grammar: Set<String>) = flowOf(SpeechEvent.Recognized(phrase))
            override suspend fun repair(language: String) = Unit
        }
        val calls = mutableListOf<String>()
        val options = settings.audioOptions()
        VoiceViewModel(settings.withContacts(), "en", VoiceCommandUseCase({ it == "VolumeUp" }, { "" }), input,
            { calls.add(it.command().id()); VoiceResult(status) },
            VoiceViewModel.Labels("Preparing", "Executing", "Unknown", "Failed", "Weather"), Dispatchers.Unconfined,
            feedbackStyle = options.feedbackStyle()).use { model ->
            model.listen()
            assertEquals(listOf("VolumeUp"), calls)
            assertEquals(VoiceFeedback.Readback(phrase), runBlocking { withTimeout(2_000) { model.feedback.first() } })
            val showPhrase = mutableStateOf(options.showRecognizedPhrase())
            instrumentation.runOnMainSync {
                activity.setContent { ControlsTheme { QuickVoicePopup(model.state.collectAsStateWithLifecycle().value, showPhrase.value) } }
            }
            await("Result status while the phrase is hidden") { nodes().any { it.matches(status) && it.isVisibleToUser } }
            assertFalse(nodes().any { it.matches(phrase) })
            instrumentation.runOnMainSync { showPhrase.value = true }
            await("Visible recognized phrase") { nodes().any { it.matches(phrase) && it.isVisibleToUser } }
            instrumentation.runOnMainSync { showPhrase.value = false }
            await("Transcript removed from accessibility") { nodes().none { it.matches(phrase) } }
            assertEquals(phrase, model.state.value.phrase)
            assertFalse(settings.exportJson().contains(phrase))
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.windows.firstOrNull { it.isActive }?.root
            ?: instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        return buildList {
            fun walk(node: AccessibilityNodeInfo) {
                if (!node.refresh()) return
                add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
            }
            walk(root)
        }
    }

    private fun AccessibilityNodeInfo.matches(label: String) = text?.toString() == label || contentDescription?.toString() == label
    private fun choice(group: Int, value: Int) = "${UiText.get(group)}, ${UiText.get(value)}"

    private fun click(label: String) = perform(label, ::clickNode)

    private fun clickableButton(matches: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        matches.flatMap { generateSequence(it) { node -> node.parent }.toList() }
            .firstOrNull { it.refresh() && it.isVisibleToUser && it.isEnabled && it.isClickable }

    private fun clickNode(matches: List<AccessibilityNodeInfo>): Boolean =
        clickableButton(matches)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    private fun assertSelected(label: String) = perform(label) { matches ->
        matches.filter { it.text?.toString() == label }.any { text ->
            generateSequence(text) { it.parent }.any { node ->
                node.refresh() && node.isVisibleToUser && node.isEnabled && (node.isSelected || node.isCheckable && node.isChecked)
            }
        }
    }

    private fun setEndCueVolume(value: Int) = perform(UiText.get(R.string.voice_end_cue_volume)) { matches ->
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        val slider = matches.firstOrNull { it.actionList.any { item -> item.id == action } }
        slider?.performAction(action, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat())
        }) == true
    }

    private fun setReadyPhrase(value: String) = perform(UiText.get(R.string.voice_ready_phrase)) {
        val field = nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled }
        field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }) == true
    }

    private fun checkCueRow(value: Int, reset: String) = perform(UiText.get(R.string.voice_end_cue_volume)) { matches ->
        val slider = matches.firstOrNull { it.rangeInfo?.current == value.toFloat() } ?: return@perform false
        val button = nodes().firstOrNull { it.isVisibleToUser && it.matches(reset) } ?: return@perform false
        val sliderBounds = Rect().also(slider::getBoundsInScreen)
        val resetBounds = Rect().also(button::getBoundsInScreen)
        sliderBounds.centerY() in resetBounds.top until resetBounds.bottom
    }

    private fun perform(label: String, action: (List<AccessibilityNodeInfo>) -> Boolean) {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var nextScroll = SystemClock.uptimeMillis() + 500
        await("Operate $label") {
            val tree = nodes()
            val matches = tree.filter { it.matches(label) }
            if (action(matches.filter { it.isVisibleToUser })) return@await true
            if (SystemClock.uptimeMillis() >= nextScroll) {
                nextScroll = SystemClock.uptimeMillis() + 380
                val show = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                if (matches.none { it.performAction(show) }) {
                    val scroll = tree.filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull {
                        Rect().also(it::getBoundsInScreen).let { b -> b.height().toLong() * b.width() }
                    }
                    if (scroll?.performAction(direction) != true) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }
            false
        }
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String = "voice-listening-popup") {
        val directory = File(app.filesDir, "ui-audit").apply { check(isDirectory || mkdirs()) }
        SystemClock.sleep(300)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            if (predicate()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        val labels = nodes().filter { it.isVisibleToUser }.mapNotNull { it.contentDescription ?: it.text }.distinct().take(18)
        fail("Timed out waiting for $description. Visible controls: $labels")
    }
}
