package org.openvoyah.controls.platform.media

import android.app.NotificationManager
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.MediaAccessService
import org.openvoyah.controls.UiText
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep

class PreviewMediaFallbackTest : InstrumentationTestCase() {
    fun testStrictPolicyClearsFallbackAndRejectsStaleTransport() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val app = instrumentation.targetContext
        val listener = ComponentName(app, MediaAccessService::class.java)
        val notifications = app.getSystemService(NotificationManager::class.java)
        val hadAccess = notifications.isNotificationListenerAccessGranted(listener)
        val events = UiText.displayEvents(app)
        val original = events.snapshot()
        val calls = LinkedBlockingQueue<String>()
        val options = DisplayPreviewContent(showTransportControls = true, preferredMediaPackage = "org.example.no_session")
        val controller = PreviewMediaController(app)
        var session: MediaSession? = null

        suspend fun mediaAccess(allowed: Boolean) {
            val action = if (allowed) "allow_listener" else "disallow_listener"
            val result = instrumentation.uiAutomation.executeShellCommand("cmd notification $action ${listener.flattenToString()}")
            ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
            withTimeout(5_000) {
                while (notifications.isNotificationListenerAccessGranted(listener) != allowed) delay(50)
            }
        }

        suspend fun observeFixture(): PreviewMediaSnapshot = withTimeout(8_000) {
            controller.state.filterNotNull().first { it.packageName == app.packageName && PreviewMediaAction.PAUSE in it.actions }
        }

        fun dispatch(content: DisplayPreviewContent, revision: Long): Boolean = controller.execute(
            PreviewMediaAction.PAUSE, revision, content.preferredMediaPackage, content.mediaFallback, PreviewSeekStep.TEN,
        )

        try {
            if (!hadAccess) mediaAccess(true)
            instrumentation.runOnMainSync {
                session = MediaSession(app, "Preview fallback fixture").apply {
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Fallback fixture").build())
                    setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .setActions(PlaybackState.ACTION_PAUSE).build())
                    setCallback(object : MediaSession.Callback() {
                        override fun onPause() { calls.add("pause") }
                    }, Handler(Looper.getMainLooper()))
                    isActive = true
                }
                controller.configure(options)
            }
            val fallback = observeFixture()
            instrumentation.runOnMainSync { assertTrue(dispatch(options, fallback.revision)) }
            assertEquals("pause", calls.poll(2, TimeUnit.SECONDS))

            val strict = options.copy(mediaFallback = PreviewMediaFallback.PREFERRED_ONLY)
            val selected = strict.copy(preferredMediaPackage = app.packageName)
            instrumentation.runOnMainSync {
                assertFalse(dispatch(strict, fallback.revision))
                controller.configure(strict)
                assertNull(controller.state.value)
                assertFalse(dispatch(strict, fallback.revision))
                controller.configure(selected)
            }
            assertNull(calls.poll(250, TimeUnit.MILLISECONDS))
            val preferred = observeFixture()
            instrumentation.runOnMainSync {
                assertFalse(dispatch(selected, fallback.revision))
                assertTrue(dispatch(selected, preferred.revision))
            }
            assertEquals("pause", calls.poll(2, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { session?.isActive = false }
            assertNull(withTimeout(5_000) { controller.state.first { it == null } })
        } finally {
            instrumentation.runOnMainSync {
                controller.close()
                session?.release()
            }
            if (!hadAccess) mediaAccess(false)
            instrumentation.runOnMainSync { events.publishMedia(original.media(), original.playbackProgress()) }
        }
    }
}
