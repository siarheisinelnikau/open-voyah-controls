package org.openvoyah.controls.platform.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.openvoyah.controls.domain.apps.WhitePlate

/** Custom app icons: a small PNG rendition of the chosen picture, and the plate-free form of a launcher icon. */
internal object AppIconImages {
    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_SOURCE_EDGE = 16_384
    private const val MAX_SOURCE_PIXELS = 64_000_000L
    const val RENDITION_EDGE = 192
    const val MAX_RENDITION_BYTES = 96 * 1024
    /** A mark without its plate fills this share of its square, about as much as a plate fills a tile. */
    private const val MARK_SHARE = 0.82f

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

    /** Keeps transparency: the rendition is a PNG of at most [RENDITION_EDGE] pixels on its longer side. */
    fun normalize(source: ByteArray): ByteArray {
        require(source.size in 1..MAX_SOURCE_BYTES)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            val (width, height) = info.size.let { it.width to it.height }
            require(width in 1..MAX_SOURCE_EDGE && height in 1..MAX_SOURCE_EDGE && width.toLong() * height <= MAX_SOURCE_PIXELS)
            useSoftwareBitmap(decoder)
            val longestEdge = maxOf(width, height)
            if (longestEdge > RENDITION_EDGE) {
                val scale = RENDITION_EDGE.toDouble() / longestEdge
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        try {
            var current = bitmap
            while (true) {
                val encoded = png(current)
                if (encoded.size <= MAX_RENDITION_BYTES) return encoded
                if (maxOf(current.width, current.height) <= 48) throw IOException("Could not fit the image into the icon budget")
                val smaller = Bitmap.createScaledBitmap(current, (current.width * 3 / 4).coerceAtLeast(1),
                    (current.height * 3 / 4).coerceAtLeast(1), true)
                if (current !== bitmap) current.recycle()
                current = smaller
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun decode(rendition: ByteArray): Bitmap {
        require(rendition.size in 1..MAX_RENDITION_BYTES)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(rendition))) { decoder, info, _ ->
            require(info.mimeType == "image/png" && info.size.width in 1..RENDITION_EDGE && info.size.height in 1..RENDITION_EDGE)
            useSoftwareBitmap(decoder)
        }
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode the icon" }
    }.toByteArray()

    /** The chosen picture centred in a transparent square of [pixels]. */
    fun custom(rendition: ByteArray, pixels: Int): Bitmap {
        val picture = decode(rendition)
        try { return fit(picture, 1f, pixels) } finally { picture.recycle() }
    }

    /**
     * The launcher icon without its plate, cropped to the mark and set in a square of [pixels], or null when
     * no plate could be found. An adaptive icon gives its foreground layer; any other icon loses the white area
     * that reaches its edges.
     */
    fun plain(drawable: Drawable, pixels: Int): Bitmap? {
        val foreground = (drawable as? AdaptiveIconDrawable)?.foreground
        // An adaptive icon shows the middle two thirds of its layers; render the whole layer at that scale.
        val edge = if (foreground != null) pixels * 3 / 2 else pixels
        val source = createBitmap(edge, edge)
        try {
            (foreground ?: drawable).mutate().apply {
                setBounds(0, 0, edge, edge)
                draw(Canvas(source))
            }
            val colors = IntArray(edge * edge)
            source.getPixels(colors, 0, edge, 0, 0, edge, edge)
            // A foreground may still carry a white plate of its own; any other icon must have one.
            val stripped = WhitePlate.strip(colors, edge, edge) ?: if (foreground != null) colors else return null
            val bounds = WhitePlate.bounds(stripped, edge, edge) ?: return null
            val mark = createBitmap(bounds[2] - bounds[0], bounds[3] - bounds[1])
            mark.setPixels(stripped, bounds[1] * edge + bounds[0], edge, 0, 0, mark.width, mark.height)
            try { return fit(mark, MARK_SHARE, pixels) } finally { mark.recycle() }
        } finally {
            source.recycle()
        }
    }

    private fun fit(picture: Bitmap, share: Float, pixels: Int): Bitmap {
        val scale = pixels * share / maxOf(picture.width, picture.height)
        val width = picture.width * scale
        val height = picture.height * scale
        return createBitmap(pixels, pixels).also { result ->
            val left = (pixels - width) / 2
            val top = (pixels - height) / 2
            Canvas(result).drawBitmap(picture, null, android.graphics.RectF(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
    }

    private fun useSoftwareBitmap(decoder: ImageDecoder) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
}
