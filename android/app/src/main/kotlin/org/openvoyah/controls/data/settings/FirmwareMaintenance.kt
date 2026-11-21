package org.openvoyah.controls.data.settings

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaBridge
import org.openvoyah.controls.diagnostics.ControlsLog
import org.openvoyah.controls.domain.display.WindowStripState

internal class FirmwareMaintenance(context: Context) {
    private val app = context.applicationContext
    private val dictionary = LocalizationSettings(app)

    fun translationEnabled(): Boolean = dictionary.enabled()
    fun windowEnabled(): Boolean = FridaBridge.windowHookEnabled(app)

    fun windowEnabled(enabled: Boolean): String {
        FridaBridge(app).setWindowHook(enabled)
        return UiText.get(if (enabled) R.string.window_hook_on else R.string.window_hook_off)
    }

    /** Experimental: Android itself reserves the launcher panel strip. The choice stays on this head unit. */
    fun windowStripEnabled(): Boolean = FridaBridge.windowStripEnabled(app)

    fun windowStrip(enabled: Boolean): String {
        val delivered = FridaBridge(app).setWindowStrip(enabled)
        if (!enabled && delivered) {
            // The agent releases the strip on its next tick. An undo that did not complete must not read as done.
            Thread.sleep(RELEASE_SETTLE_MILLIS)
            if (windowStripState().phase == WindowStripState.Phase.NOT_UNDONE) {
                throw IOException(UiText.get(R.string.window_strip_status_not_undone))
            }
        }
        return UiText.get(when {
            !delivered -> R.string.window_strip_saved
            enabled -> R.string.window_strip_requested
            else -> R.string.window_strip_released
        })
    }

    /** Asks the running agent; a channel that cannot be reached reads as an agent that is not there. */
    fun windowStripState(): WindowStripState = try {
        FridaBridge(app).windowStripState()
    } catch (_: IOException) {
        WindowStripState(WindowStripState.Phase.AGENT_UNAVAILABLE)
    } catch (failure: RuntimeException) {
        // The page only shows a status line; a broken stored value must not take the screen down with it.
        ControlsLog.warn("window_strip_state", failure, "outcome", "failed")
        WindowStripState(WindowStripState.Phase.UNKNOWN)
    }

    fun preset(language: String): String { dictionary.preset(language); return apply() }
    fun disable(): String { dictionary.disable(); return apply() }
    fun restore(): String { dictionary.restore(); return apply() }

    fun importDictionary(uri: Uri): String {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException(UiText.get(R.string.ui_123))
        val output = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer)
                if (count == -1) break
                if (output.size() + count > 48_000) throw IOException("Dictionary exceeds 48 KB")
                output.write(buffer, 0, count)
            }
        }
        dictionary.importJson(output.toString(Charsets.UTF_8.name()))
        return apply()
    }

    fun exportDictionary(uri: Uri): String {
        val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException(UiText.get(R.string.ui_123))
        output.use { it.write(dictionary.exportJson().toByteArray(Charsets.UTF_8)) }
        return UiText.get(R.string.ui_124)
    }

    fun restoreProfile(): String = FridaBridge(app).restoreProfile()

    private fun apply(): String {
        if (!FridaBridge.wasConfigured(app)) return UiText.get(R.string.translation_saved)
        FridaBridge(app).connect(SettingsStore(app).load())
        return UiText.get(R.string.translation_applied)
    }

    private companion object {
        // Three ticks of the agent's 400 ms settings timer.
        const val RELEASE_SETTLE_MILLIS = 1_200L
    }
}
