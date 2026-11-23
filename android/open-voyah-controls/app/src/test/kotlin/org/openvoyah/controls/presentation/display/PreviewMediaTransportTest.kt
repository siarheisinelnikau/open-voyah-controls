package org.openvoyah.controls.presentation.display

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.core.display.PlaybackProgress
import org.openvoyah.controls.domain.display.PreviewMediaAction
import org.openvoyah.controls.domain.display.PreviewMediaFallback
import org.openvoyah.controls.domain.display.PreviewSeekStep
import org.openvoyah.controls.domain.display.preferredMediaSource
import org.openvoyah.controls.domain.display.relativePosition
import org.openvoyah.controls.domain.display.validMediaPackage
import org.openvoyah.controls.platform.media.previewTransportActions

class PreviewMediaTransportTest {
    @Test fun supportedCommandsFollowPlaybackStateAndSessionActions() {
        val actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO
        assertEquals(setOf(PreviewMediaAction.PLAY, PreviewMediaAction.NEXT),
            previewTransportActions(PlaybackState.STATE_PAUSED, actions, seekable = false))
        assertEquals(setOf(PreviewMediaAction.PAUSE, PreviewMediaAction.NEXT,
            PreviewMediaAction.SEEK_BACK, PreviewMediaAction.SEEK_FORWARD),
            previewTransportActions(PlaybackState.STATE_PLAYING, actions, seekable = true))
        assertEquals(setOf(PreviewMediaAction.PREVIOUS),
            previewTransportActions(PlaybackState.STATE_BUFFERING, PlaybackState.ACTION_SKIP_TO_PREVIOUS, seekable = true))
        assertEquals(emptySet<PreviewMediaAction>(),
            previewTransportActions(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PAUSE, seekable = true))
    }

    @Test fun seekingComputesTheLatestPositionAndStaysInsideTheTrack() {
        val playing = PlaybackProgress(20_000, 5_000, 1_000, 2f)
        assertEquals(0L, playing.relativePosition(PreviewMediaAction.SEEK_BACK, 2_000, PreviewSeekStep.TEN))
        assertEquals(17_000L, playing.relativePosition(PreviewMediaAction.SEEK_FORWARD, 2_000, PreviewSeekStep.TEN))
        assertEquals(20_000L, playing.relativePosition(PreviewMediaAction.SEEK_FORWARD, 4_000, PreviewSeekStep.TEN))
        assertEquals(2_000L, playing.relativePosition(PreviewMediaAction.SEEK_BACK, 2_000, PreviewSeekStep.FIVE))
        assertEquals(12_000L, playing.relativePosition(PreviewMediaAction.SEEK_FORWARD, 2_000, PreviewSeekStep.FIVE))
        val paused = PlaybackProgress(240_000, 32_000, 1_000, 0f)
        assertEquals(2_000L, paused.relativePosition(PreviewMediaAction.SEEK_BACK, 4_000, PreviewSeekStep.THIRTY))
        assertEquals(62_000L, paused.relativePosition(PreviewMediaAction.SEEK_FORWARD, 4_000, PreviewSeekStep.THIRTY))
        val longTrack = PlaybackProgress(Long.MAX_VALUE, Long.MAX_VALUE - 2, 1_000, 0f)
        assertEquals(Long.MAX_VALUE, longTrack.relativePosition(PreviewMediaAction.SEEK_FORWARD, 5_000, PreviewSeekStep.THIRTY))
    }

    @Test fun preferenceSelectsOnlyItsPackageAndFallsBackToTheNormalPriority() {
        data class Source(val name: String, val playing: Boolean)
        val default = Source("com.example.default", true)
        val paused = Source("com.example.preferred", false)
        val playing = paused.copy(playing = true)
        fun pick(sources: List<Source>, preference: String, fallback: PreviewMediaFallback = PreviewMediaFallback.AUTOMATIC) =
            preferredMediaSource(sources, preference, Source::name, fallback) { list ->
                list.firstOrNull { it.playing } ?: list.firstOrNull()
            }

        assertEquals(default, pick(listOf(paused, default), ""))
        assertEquals(paused, pick(listOf(default, paused), paused.name))
        assertEquals(playing, pick(listOf(default, paused, playing), paused.name))
        assertEquals(default, pick(listOf(default, paused), "com.example.missing"))
        assertEquals(null, pick(emptyList(), paused.name))
        val strict = PreviewMediaFallback.PREFERRED_ONLY
        assertEquals(null, pick(listOf(default, paused), "com.example.missing", strict))
        assertEquals(playing, pick(listOf(default, paused, playing), paused.name, strict))
        assertEquals(default, pick(listOf(paused, default), "", strict))
    }

    @Test fun archiveMediaSourceAcceptsOnlyBoundedPackageNamesOrAutomatic() {
        for (valid in listOf("", "com.example.HomePlayer2", "android")) assertTrue(validMediaPackage(valid))
        for (invalid in listOf("com.foo/Activity", "com.1player", "com.example.", "com..player", " player", "a".repeat(256))) {
            assertFalse(validMediaPackage(invalid))
        }
    }
}
