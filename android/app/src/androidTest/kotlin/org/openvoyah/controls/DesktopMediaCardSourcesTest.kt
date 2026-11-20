package org.openvoyah.controls

import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openvoyah.controls.core.display.DisplayEventBus.Media
import org.openvoyah.controls.data.apps.DesktopInfoSources
import org.openvoyah.controls.data.apps.DesktopMediaContent
import org.openvoyah.controls.domain.apps.DesktopMediaStyle
import org.openvoyah.controls.platform.media.PreviewMediaSources

class DesktopMediaCardSourcesTest : InstrumentationTestCase() {
    fun testCoverAndTextFollowOnlyTheCurrentSessionMetadata() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.startsWith("sdk_"))
        val context = instrumentation.targetContext
        val listener = ComponentName(context, MediaAccessService::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val previousAccess = notifications.isNotificationListenerAccessGranted(listener)
        val mediaEvents = UiText.displayEvents(context)
        val previousMedia = mediaEvents.snapshot()
        val artwork = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        var session: MediaSession? = null

        suspend fun access(allowed: Boolean) {
            val action = if (allowed) "allow_listener" else "disallow_listener"
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd notification $action ${listener.flattenToString()}")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            withTimeout(5_000) {
                while (notifications.isNotificationListenerAccessGranted(listener) != allowed) delay(50)
            }
        }

        val observed = MutableStateFlow<DesktopMediaContent?>(null)
        val sources = DesktopInfoSources(context)
        try {
            if (!previousAccess) access(true)
            instrumentation.runOnMainSync {
                session = MediaSession(context, "Desktop media card fixture").apply {
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Cover fixture")
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "desktop-cover-first")
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork).build())
                    setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                    isActive = true
                }
            }
            val watcher = launch { sources.media(DesktopMediaStyle.COVER).collect { observed.value = it } }
            try {
                val cover = withTimeout(8_000) { observed.filterNotNull().first { it.media.title() == "Cover fixture" && it.artwork != null } }
                assertEquals(Color.CYAN, checkNotNull(cover.artwork).getPixel(20, 20))
                val text = withTimeout(8_000) { sources.media(DesktopMediaStyle.TEXT).first { it.media.title() == "Cover fixture" } }
                assertNull(text.artwork)
                val packageName = context.packageName
                assertTrue(PreviewMediaSources(context).list("").any { it.packageName == packageName })
                val ambient = Media("Ambient fixture", "Ambient artist", "", true)
                instrumentation.runOnMainSync { mediaEvents.publishMedia(ambient) }
                val preferredText = withTimeout(8_000) {
                    sources.media(DesktopMediaStyle.TEXT, packageName).first { it.media.title() == "Cover fixture" }
                }
                assertNull(preferredText.artwork)
                val withoutPreferred = withTimeout(8_000) {
                    sources.media(DesktopMediaStyle.TEXT, "org.example.no_session").first { it.media == ambient }
                }
                assertNull(withoutPreferred.artwork)
                assertNull(withoutPreferred.playerPackage)
                val automaticPlayer = withTimeout(8_000) {
                    sources.media(DesktopMediaStyle.TEXT, identifyPlayer = true).first { it.media.title() == "Cover fixture" }
                }
                assertEquals(packageName, automaticPlayer.playerPackage)
                assertNull(automaticPlayer.artwork)
                val fallbackPlayer = withTimeout(8_000) {
                    sources.media(DesktopMediaStyle.TEXT, "org.example.no_session", identifyPlayer = true)
                        .first { it.media.title() == "Cover fixture" }
                }
                assertEquals(packageName, fallbackPlayer.playerPackage)
                val preferredCover = withTimeout(8_000) {
                    sources.media(DesktopMediaStyle.COVER, packageName, identifyPlayer = true)
                        .first { it.media.title() == "Cover fixture" && it.artwork != null }
                }
                assertEquals(Color.CYAN, checkNotNull(preferredCover.artwork).getPixel(20, 20))
                assertEquals(packageName, preferredCover.playerPackage)

                instrumentation.runOnMainSync {
                    session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Text fallback fixture")
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "desktop-cover-second").build())
                }
                val fallback = withTimeout(8_000) { observed.filterNotNull().first { it.media.title() == "Text fallback fixture" } }
                assertNull(fallback.artwork)

                instrumentation.runOnMainSync { session?.setMetadata(null) }
                withTimeout(8_000) {
                    observed.filterNotNull().first { !it.media.available() }
                    sources.media(DesktopMediaStyle.TEXT).first { !it.media.available() }
                }
                instrumentation.runOnMainSync { mediaEvents.publishMedia(ambient) }
                val unidentified = withTimeout(8_000) {
                    sources.media(DesktopMediaStyle.TEXT, identifyPlayer = true).first { it.media == ambient }
                }
                assertNull(unidentified.playerPackage)
            } finally { watcher.cancelAndJoin() }
        } finally {
            instrumentation.runOnMainSync { session?.release() }
            artwork.recycle()
            if (!previousAccess) access(false)
            instrumentation.runOnMainSync { mediaEvents.publishMedia(previousMedia.media(), previousMedia.playbackProgress()) }
        }
    }
}
