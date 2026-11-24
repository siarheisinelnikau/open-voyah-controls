package org.openvoyah.controls.data.voice

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

/** Stages the pinned APK model as files for the native recognizer. Called under the speech preparation lock. */
internal class BundledVoiceModel(private val usableSpace: (File) -> Long = File::getUsableSpace) {
    class StorageUnavailable : IOException(UiText.get(R.string.setup_space_failed))

    fun prepare(context: Context, current: () -> Boolean): File {
        val target = directory(context)
        if (File(target, READY).isFile) return target
        if (usableSpace(context.filesDir) < PREPARATION_SPACE) throw StorageUnavailable()
        val names = context.assets.list(MODEL_ID)?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Bundled speech model is missing")
        val parent = checkNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create bundled speech model directory")
        val staging = File(parent, ".$MODEL_ID.part")
        if (!staging.deleteRecursively() || !staging.mkdir()) throw IOException("Cannot stage bundled speech model")
        try {
            val buffer = ByteArray(64 * 1024)
            for (name in names) {
                if (!current()) throw CancellationException("Speech model preparation cancelled")
                context.assets.open("$MODEL_ID/$name").use { input ->
                    File(staging, name).outputStream().use { output ->
                        while (true) {
                            if (!current()) throw CancellationException("Speech model preparation cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (!current()) throw CancellationException("Speech model preparation cancelled")
            File(staging, READY).writeText(MODEL_ID)
            if (!target.deleteRecursively() || !staging.renameTo(target)) {
                throw IOException("Cannot install bundled speech model")
            }
            return target
        } finally {
            staging.deleteRecursively()
        }
    }

    fun invalidate(context: Context) {
        // Keep the existing files until the replacement has copied successfully.
        val marker = File(directory(context), READY)
        if (marker.exists() && !marker.delete()) throw IOException(UiText.get(R.string.voice_model_repair_failed))
    }

    // Reuses the verified copy downloaded by earlier versions if it is already installed.
    private fun directory(context: Context) = File(context.filesDir, "speech-models/$MODEL_ID")

    private companion object {
        const val MODEL_ID = "sherpa-onnx-zipformer-ru-int8-2025-04-20"
        const val READY = "ready"
        const val PREPARATION_SPACE = 128L * 1024 * 1024
    }
}
