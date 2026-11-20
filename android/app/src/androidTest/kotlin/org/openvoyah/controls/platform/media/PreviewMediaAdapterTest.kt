package org.openvoyah.controls.platform.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.AndroidTestCase
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewSeekStep

class PreviewMediaAdapterTest : AndroidTestCase() {
    fun testEmbeddedArtworkIsBoundedAndArtworkUrisAreIgnored() {
        val original = Bitmap.createBitmap(1024, 384, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val uriOnly = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.invalid/art")
        assertNull(uriOnly.build().previewArtwork())

        val artwork = checkNotNull(uriOnly.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, original).build().previewArtwork())
        assertEquals(PREVIEW_ARTWORK_SIDE, artwork.width)
        assertEquals(96, artwork.height)
        assertEquals(Color.MAGENTA, artwork.getPixel(128, 48))
        assertFalse(artwork.isMutable)
    }

    fun testTransportDispatchUsesTheExactControllerAndConfirmedDuration() {
        val received = LinkedBlockingQueue<String>()
        val session = MediaSession(context, "PreviewTransportFixture")
        try {
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { received.add("play") }
                override fun onPause() { received.add("pause") }
                override fun onSkipToNext() { received.add("next") }
                override fun onSkipToPrevious() { received.add("previous") }
                override fun onSeekTo(pos: Long) { received.add("seek:$pos") }
            }, Handler(Looper.getMainLooper()))
            val controller = MediaController(context, session.sessionToken)
            val now = SystemClock.elapsedRealtime()
            val paused = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 500, 0f, now)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO).build()
            val progress = PlaybackProgress(7_000, 500, now, 0f)
            val step = PreviewSeekStep.THIRTY
            assertFalse(controller.dispatchPreviewAction(PreviewMediaAction.PAUSE, paused, progress, step))
            assertFalse(controller.dispatchPreviewAction(PreviewMediaAction.PREVIOUS, paused, progress, step))
            assertFalse(controller.dispatchPreviewAction(PreviewMediaAction.SEEK_BACK, paused, null, step))
            assertTrue(controller.dispatchPreviewAction(PreviewMediaAction.PLAY, paused, progress, step))
            assertEquals("play", received.poll(2, TimeUnit.SECONDS))
            assertTrue(controller.dispatchPreviewAction(PreviewMediaAction.NEXT, paused, progress, step))
            assertEquals("next", received.poll(2, TimeUnit.SECONDS))
            assertTrue(controller.dispatchPreviewAction(PreviewMediaAction.SEEK_BACK, paused, progress, step))
            assertEquals("seek:0", received.poll(2, TimeUnit.SECONDS))
            assertTrue(controller.dispatchPreviewAction(PreviewMediaAction.SEEK_FORWARD, paused, progress, step))
            assertEquals("seek:7000", received.poll(2, TimeUnit.SECONDS))
        } finally {
            session.release()
        }
    }
}
