package org.openvoyah.controls.platform.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Copies a small, static rendition so rendering and backups no longer depend on the document provider. */
internal object DesktopWallpaperImages {
    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_SOURCE_EDGE = 16_384
    private const val MAX_SOURCE_PIXELS = 64_000_000L
    private const val RENDITION_EDGE = 960
    const val MAX_RENDITION_BYTES = 120 * 1024
    private val qualitySteps = intArrayOf(82, 70, 56, 42)

    fun read(context: Context, document: Uri): ByteArray {
        require(document.scheme == "content")
        val input = context.contentResolver.openInputStream(document) ?: throw IOException("Image is unavailable")
        val source = input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            val output = ByteArrayOutputStream()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_SOURCE_BYTES) throw IOException("Image document is too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return normalize(source)
    }

    fun normalize(source: ByteArray): ByteArray {
        require(source.size in 1..MAX_SOURCE_BYTES)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            val (width, height) = info.size.let { it.width to it.height }
            require(width in 1..MAX_SOURCE_EDGE && height in 1..MAX_SOURCE_EDGE && width.toLong() * height <= MAX_SOURCE_PIXELS)
            useSoftwareBitmap(decoder)
            val longestEdge = maxOf(width, height)
            if (longestEdge > RENDITION_EDGE) {
                val scale = RENDITION_EDGE.toDouble() / longestEdge
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        try {
            encode(bitmap)?.let { return it }
            val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width / 2).coerceAtLeast(1),
                (bitmap.height / 2).coerceAtLeast(1), true)
            try { return encode(smaller) ?: throw IOException("Could not fit image into the wallpaper budget") }
            finally { if (smaller !== bitmap) smaller.recycle() }
        } finally {
            bitmap.recycle()
        }
    }

    fun decode(rendition: ByteArray): Bitmap {
        require(rendition.size in 1..MAX_RENDITION_BYTES)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(rendition))) { decoder, info, _ ->
            require(info.mimeType == "image/jpeg" && info.size.width in 1..RENDITION_EDGE && info.size.height in 1..RENDITION_EDGE)
            useSoftwareBitmap(decoder)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        val output = ByteArrayOutputStream()
        for (quality in qualitySteps) {
            output.reset()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() in 1..MAX_RENDITION_BYTES) {
                return output.toByteArray()
            }
        }
        return null
    }

    private fun useSoftwareBitmap(decoder: ImageDecoder) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
}
