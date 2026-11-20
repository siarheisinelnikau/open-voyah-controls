package org.openvoyah.controls.platform.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openvoyah.controls.MediaAccessService
import org.openvoyah.controls.core.display.DisplayEventBus.Media
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.domain.display.DisplayPreviewContent
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.domain.display.preferredMediaSource

internal data class PreviewMediaSnapshot(
    val packageName: String,
    val media: Media,
    val progress: PlaybackProgress?,
    val artwork: Bitmap?,
    val revision: Long,
    val actions: Set<PreviewMediaAction>,
)

/** Observes a player for consumers that need session data beyond the automatic feed. */
@MainThread
internal class PreviewMediaController(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(app, MediaAccessService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val current = MutableStateFlow<PreviewMediaSnapshot?>(null)
    val state = current.asStateFlow()

    private data class Options(
        val preferred: String,
        val fallback: PreviewMediaFallback,
        val artwork: Boolean,
        val controls: Boolean,
    )

    private var options: Options? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var sessions: List<MediaController> = emptyList()
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var reading = MediaSessionReading()
    private var track: MediaSessionReading.Track? = null
    private var artwork: Bitmap? = null
    private var revision = 0L

    fun configure(content: DisplayPreviewContent) = configure(content, requireSession = false)

    fun configure(content: DisplayPreviewContent, requireSession: Boolean) {
        if (!content.showMedia || (!requireSession && !content.showAlbumArtwork && !content.showTransportControls &&
                content.preferredMediaPackage.isEmpty())) {
            close()
            return
        }
        val next = Options(content.preferredMediaPackage, content.mediaFallback,
            content.showAlbumArtwork, content.showTransportControls)
        val previous = options
        if (previous == next) return
        options = next
        if (sessionsListener == null) start()
        else select(sessions, refreshArtwork = previous?.artwork != next.artwork)
    }

    private fun start() {
        val manager = manager ?: return
        val listener = object : MediaSessionManager.OnActiveSessionsChangedListener {
            override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
                if (sessionsListener === this) select(controllers.orEmpty())
            }
        }
        try {
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
            sessionsListener = listener
            select(manager.getActiveSessions(component))
        } catch (_: SecurityException) { close() }
    }

    private fun select(available: List<MediaController>, refreshArtwork: Boolean = false) {
        val configuration = options ?: return
        sessions = available
        val next = selectSource(available, configuration)
        if (controller?.sessionToken == next?.sessionToken) {
            update(refreshArtwork = refreshArtwork)
            return
        }
        disconnectController()
        controller = next
        if (next != null) {
            val listener = listenTo(next)
            callback = listener
            next.registerCallback(listener, handler)
            update(initial = true)
        }
    }

    private fun listenTo(selected: MediaController) = object : MediaController.Callback() {
        private fun active(): Boolean = selected === controller && callback === this

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (active()) update(refreshArtwork = true)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (!active()) return
            if (state == null) reading.clearPlayback()
            update(reported = state)
        }

        override fun onSessionDestroyed() {
            if (active()) select(sessions.filter { it.sessionToken != selected.sessionToken })
        }
    }

    private fun update(reported: PlaybackState? = null, initial: Boolean = false, refreshArtwork: Boolean = false) {
        val selected = controller ?: return
        val configuration = options ?: return
        val metadata = selected.metadata
        val playback = selected.playbackState
        val snapshot = reading.read(metadata, playback, if (initial) playback else reported)
        val newTrack = snapshot.track() != track
        if (newTrack) {
            track = snapshot.track()
            revision++
        }
        if (!configuration.artwork || track == null) artwork = null
        else if (newTrack || refreshArtwork || initial) artwork = metadata?.previewArtwork()
        val actions = if (configuration.controls && playback != null) {
            previewTransportActions(playback.state, playback.actions, snapshot.progress() != null)
        } else emptySet()
        current.value = PreviewMediaSnapshot(selected.packageName, snapshot.media(), snapshot.progress(), artwork, revision, actions)
    }

    fun execute(
        action: PreviewMediaAction,
        expectedRevision: Long,
        preferred: String,
        fallback: PreviewMediaFallback,
        seekStep: PreviewSeekStep,
    ): Boolean {
        val selected = controller ?: return false
        val displayed = current.value ?: return false
        val configuration = options ?: return false
        if (!configuration.controls || configuration.preferred != preferred || configuration.fallback != fallback ||
            !fallback.permits(preferred, selected.packageName) || displayed.revision != expectedRevision ||
            action !in displayed.actions) return false
        return try {
            val available = manager?.getActiveSessions(component) ?: return false
            if (selectSource(available, configuration)?.sessionToken != selected.sessionToken) {
                select(available)
                return false
            }
            val playback = selected.playbackState ?: return false
            val metadata = selected.metadata
            val snapshot = reading.read(metadata, playback, null)
            if (snapshot.track() != track) {
                update(refreshArtwork = true)
                return false
            }
            selected.dispatchPreviewAction(action, playback, snapshot.progress(), seekStep)
        } catch (_: SecurityException) {
            close()
            false
        }
    }

    override fun close() {
        options = null
        val listener = sessionsListener
        sessionsListener = null
        if (listener != null) manager?.removeOnActiveSessionsChangedListener(listener)
        sessions = emptyList()
        disconnectController()
    }

    private fun disconnectController() {
        val old = controller
        controller = null
        callback?.let { old?.unregisterCallback(it) }
        callback = null
        reading = MediaSessionReading()
        track = null
        artwork = null
        revision++
        current.value = null
    }

    private fun selectSource(available: List<MediaController>, configuration: Options): MediaController? =
        preferredMediaSource(available, configuration.preferred, MediaController::getPackageName,
            configuration.fallback, MediaSessionMonitor::selectPlaying)
}
