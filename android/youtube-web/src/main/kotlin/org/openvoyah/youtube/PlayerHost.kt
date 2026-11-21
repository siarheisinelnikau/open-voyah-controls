package org.openvoyah.youtube

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Publishes the page's player as a media session, so the head unit's media keys, the media card in Controls and the
 * cluster see what plays and can pause it.
 *
 * Audio focus is not handled here: the WebView's own media session requests it for every playing video and pauses,
 * ducks or resumes the page when another source takes over. A second request from the app would take focus from the
 * WebView and stop the video it was meant to protect.
 *
 * [script] runs JavaScript in the page; [playingChanged] lets the window keep the screen on during playback.
 */
internal class PlayerHost(
    activity: Activity,
    private val script: (String) -> Unit,
    private val playingChanged: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val session = MediaSession(activity, "OpenVoyahYouTube").apply {
        setSessionActivity(PendingIntent.getActivity(activity, 0,
            Intent(activity, YouTubeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE))
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = player("play()")
            override fun onPause() = player("pause()")
            override fun onStop() = player("pause()")
            override fun onSkipToNext() = player("next()")
            override fun onSkipToPrevious() = player("previous()")
            override fun onSeekTo(pos: Long) = player("seek(${pos.coerceAtLeast(0)})")
        }, main)
    }
    private var playing = false
    private var metadataKey = ""

    /** Called by the injected player bridge on the WebView's binder thread. */
    @JavascriptInterface
    fun state(json: String) {
        if (json.length > MAX_STATE_LENGTH) return
        main.post { update(json) }
    }

    /**
     * The window stopped being visible: nobody can watch the video, so it pauses, as on a phone without Premium. Sent
     * even when no playback was reported, in case the page started a video before the bridge was in place.
     */
    fun hidden() = player("pause()")

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun update(json: String) {
        val state = try { JSONObject(json) } catch (_: Exception) { return }
        val nowPlaying = state.optBoolean("playing")
        if (nowPlaying != playing) {
            playing = nowPlaying
            playingChanged(nowPlaying)
        }
        publish(WebPolicy.mediaTitle(state.optString("title")).take(MAX_TEXT), state.optString("artist").take(MAX_TEXT),
            state.optLong("position").coerceAtLeast(0), state.optLong("duration").coerceAtLeast(0))
    }

    private fun publish(title: String, artist: String, position: Long, duration: Long) {
        val key = "$title\u0000$artist\u0000$duration"
        if (key != metadataKey) {
            metadataKey = key
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build())
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(ACTIONS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position,
                if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
        if (playing && !session.isActive) session.isActive = true
    }

    private fun player(call: String) = script("window.__ovcYouTube && window.__ovcYouTube.$call")

    private companion object {
        const val MAX_STATE_LENGTH = 2048
        const val MAX_TEXT = 200
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
    }
}
