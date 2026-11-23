package org.openvoyah.controls.platform.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import kotlin.math.roundToInt

internal const val PREVIEW_ARTWORK_SIDE = 256
private const val ARTWORK_ALLOCATION_BYTES = PREVIEW_ARTWORK_SIDE * PREVIEW_ARTWORK_SIDE * 8

/** Uses only embedded Android bitmaps. Artwork URIs never leave the metadata object. */
internal fun MediaMetadata.previewArtwork(): Bitmap? =
    getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART).boundedArtwork()
        ?: getBitmap(MediaMetadata.METADATA_KEY_ART).boundedArtwork()
        ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON).boundedArtwork()

private fun Bitmap?.boundedArtwork(): Bitmap? {
    if (this == null || isRecycled || width <= 0 || height <= 0) return null
    return try {
        val longSide = maxOf(width, height)
        val candidate = if (longSide > PREVIEW_ARTWORK_SIDE) {
            val scale = PREVIEW_ARTWORK_SIDE.toDouble() / longSide
            Bitmap.createScaledBitmap(this, (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1), true)
        } else this
        if (!candidate.isMutable && candidate.config != Bitmap.Config.HARDWARE &&
            candidate.allocationByteCount <= ARTWORK_ALLOCATION_BYTES) candidate
        else candidate.copy(Bitmap.Config.ARGB_8888, false)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
