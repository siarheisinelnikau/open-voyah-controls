package org.openvoyah.controls.data.settings

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.data.frida.FridaBridge

internal class FirmwareMaintenance(context: Context) {
    private val app = context.applicationContext
    private val dictionary = LocalizationSettings(app)

    fun translationEnabled(): Boolean = dictionary.enabled()
    fun windowEnabled(): Boolean = FridaBridge.windowHookEnabled(app)

    fun windowEnabled(enabled: Boolean): String {
        FridaBridge(app).setWindowHook(enabled)
        return UiText.get(if (enabled) R.string.window_hook_on else R.string.window_hook_off)
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
}
