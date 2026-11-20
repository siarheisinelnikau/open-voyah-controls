package org.openvoyah.controls.data.settings

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText
import org.openvoyah.controls.core.PanelControls
import org.openvoyah.controls.data.frida.FridaPanelControls
import org.openvoyah.controls.presentation.settings.SettingsArchiveDocuments
import org.openvoyah.controls.presentation.settings.SettingsArchiveRuntime
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** All calls are made on the archive worker, including opening and closing provider streams. */
internal class LocalSettingsArchiveDocuments(context: Context) : SettingsArchiveDocuments {
    private val app = context.applicationContext
    private val archive = LocalSettingsArchive(app)

    private class Prepared(val value: LocalSettingsArchive.Restore) : SettingsArchiveDocuments.Restore {
        override val bindingCount: Int get() = value.bindingCount
        override val complete: Boolean get() = value.complete
    }

    override fun export(document: String) {
        // Serialize the full archive before opening the destination. A failed section must never
        // silently produce a button-only backup, or truncate an existing document.
        val bytes = archive.exportJson().toByteArray(Charsets.UTF_8)
        if (bytes.size > LocalSettingsArchive.MAX_BYTES) throw IOException(UiText.get(R.string.settings_archive_too_large))
        val output = app.contentResolver.openOutputStream(Uri.parse(document), "wt")
            ?: throw IOException(UiText.get(R.string.ui_123))
        output.use { it.write(bytes) }
    }

    override fun prepare(document: String): SettingsArchiveDocuments.Restore {
        val input = app.contentResolver.openInputStream(Uri.parse(document))
            ?: throw IOException(UiText.get(R.string.ui_123))
        val bytes = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = it.read(buffer)
                if (count == -1) break
                if (bytes.size() + count > LocalSettingsArchive.MAX_BYTES)
                    throw IOException(UiText.get(R.string.settings_archive_too_large))
                bytes.write(buffer, 0, count)
            }
        }
        return Prepared(archive.prepare(bytes.toString(Charsets.UTF_8.name())))
    }

    override fun apply(restore: SettingsArchiveDocuments.Restore): Boolean {
        val language = UiText.language()
        archive.apply((restore as Prepared).value)
        return language != UiText.language()
    }
}

/** Full restore uses the normal CONNECT operation to refresh translation files and all workers. */
internal class FridaSettingsArchiveRuntime(context: Context) : SettingsArchiveRuntime {
    private val panel = FridaPanelControls(context.applicationContext, SettingsStore(context.applicationContext))

    override fun configured(): Boolean = panel.configured()

    override suspend fun reconnect(): String? = suspendCoroutine { continuation ->
        panel.run(PanelControls.Operation.CONNECT) { result ->
            continuation.resume(if (result.successful()) null else result.message() ?: UiText.get(R.string.ui_82))
        }
    }

    override fun publish(error: (String) -> Unit) = panel.publish(error)
}
