package org.openvoyah.controls.data.settings

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

internal class VoiceSettingsDocuments(private val resolver: ContentResolver, private val settings: VoiceSettings) {
    suspend fun save(uri: Uri) = withContext(Dispatchers.IO) {
        val settingsBytes = settings.exportJson().toByteArray(Charsets.UTF_8)
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException(UiText.get(R.string.ui_123))
        stream.use { it.write(settingsBytes) }
    }

    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw IOException(UiText.get(R.string.ui_123))
        val contents = stream.use {
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                ensureActive()
                val count = it.read(buffer)
                if (count == -1) break
                if (output.size() + count > MAX_BYTES) throw IOException(UiText.get(R.string.ui_154))
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
        ensureActive()
        settings.importJson(contents)
    }

    private companion object { const val MAX_BYTES = 1_048_576 }
}
