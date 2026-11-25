package org.openvoyah.controls.core.voice;

import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceAudioOptionsTest {
    @Test public void speechRateAndGainsTranslateIndependently() {
        var options = new VoiceAudioOptions(75, 40, true, 20);
        assertEquals(.75f, options.speechRate(), 0f);
        assertEquals(.4f, options.speechGain(), 0f);
        assertTrue(options.readyCue());
        assertEquals(.2f, options.readyCueGain(), 0f);
        assertFalse(VoiceAudioOptions.DEFAULTS.readyCue());
        assertEquals(1f, VoiceAudioOptions.DEFAULTS.speechGain(), 0f);
    }

    @Test public void allowsBothAudioEndpointsAndRejectsOutOfRangeValues() {
        assertEquals(0f, new VoiceAudioOptions(50, 0, false, 100).speechGain(), 0f);
        assertEquals(1.5f, new VoiceAudioOptions(150, 100, true, 0).speechRate(), 0f);
        assertThrows(IllegalArgumentException.class, () -> new VoiceAudioOptions(49, 50, true, 35));
        assertThrows(IllegalArgumentException.class, () -> new VoiceAudioOptions(150, 101, true, 35));
        assertThrows(IllegalArgumentException.class, () -> new VoiceAudioOptions(100, 90, true, -1));
    }

    @Test public void olderConstructorsKeepTheToneAndAssistantOutput() {
        var defaults = VoiceAudioOptions.DEFAULTS;
        var precise = new VoiceAudioOptions(100, 80, true, 30, defaults.feedbackStyle(), defaults.endCue(),
            defaults.listeningWindow(), defaults.resultDuration(), false, 37);
        for (var options : new VoiceAudioOptions[]{defaults, precise}) {
            assertEquals(VoiceAudioOptions.ReadyCueStyle.TONE, options.readyCueStyle());
            assertEquals(VoiceAudioOptions.SpokenReplyChannel.ASSISTANT, options.spokenReplyChannel());
        }
        assertEquals(VoiceAudioOptions.ReadyCueStyle.SPOKEN, VoiceAudioOptions.ReadyCueStyle.fromKey("spoken"));
        assertEquals(VoiceAudioOptions.SpokenReplyChannel.MEDIA, VoiceAudioOptions.SpokenReplyChannel.fromKey("media"));
        assertNull(VoiceAudioOptions.ReadyCueStyle.fromKey("unknown"));
        assertNull(VoiceAudioOptions.SpokenReplyChannel.fromKey(null));
    }

    @Test public void legacyDefaultsRepeatCommandsWithoutAnEndCue() {
        var options = VoiceAudioOptions.DEFAULTS;
        assertEquals(VoiceAudioOptions.FeedbackStyle.READBACK, options.feedbackStyle());
        assertEquals(VoiceAudioOptions.EndCue.OFF, options.endCue());
        assertEquals(0f, options.endCue().gain(), 0f);
        assertTrue(VoiceAudioOptions.EndCue.STANDARD.gain() > VoiceAudioOptions.EndCue.SOFT.gain());
        assertNull(VoiceAudioOptions.EndCue.fromKey("loud"));
        assertNull(VoiceAudioOptions.FeedbackStyle.fromKey("raw"));
    }

    @Test public void customReadyPromptAllowsOneShortSpokenLineOrTheLocalizedDefault() {
        assertTrue(VoiceAudioOptions.validReadyPrompt(""));
        assertTrue(VoiceAudioOptions.validReadyPrompt("Go ahead."));
        assertTrue(VoiceAudioOptions.validReadyPrompt("Говорите, я слушаю."));
        String full = "А".repeat(VoiceAudioOptions.MAX_READY_PROMPT_CHARACTERS - 1) + "\uD83D\uDC4B";
        assertTrue(VoiceAudioOptions.validReadyPrompt(full));
        for (String invalid : new String[]{null, full + "!", "  ", "...", "a\nb", "a\rb", "a\tb", "a\u0000b",
                "a\u2028b", "a\u2029b", "a\u202Eb", "a\uD800"}) {
            assertFalse(VoiceAudioOptions.validReadyPrompt(invalid));
        }
    }

    @Test public void listeningAndPopupKeepHistoricDefaultsInBothOlderConstructors() {
        for (var options : new VoiceAudioOptions[]{VoiceAudioOptions.DEFAULTS,
                new VoiceAudioOptions(75, 40, true, 20, VoiceAudioOptions.FeedbackStyle.OUTCOME, VoiceAudioOptions.EndCue.SOFT)}) {
            assertEquals(4000L, options.listeningWindow().millis());
            assertEquals(2000L, options.resultDuration().millis());
            assertTrue(options.showRecognizedPhrase());
        }
    }

    @Test public void aPhraseStillSpokenAtTheEndOfTheWindowGetsUpToThreeMoreSeconds() {
        var window = VoiceAudioOptions.ListeningWindow.THREE_SECONDS;
        int[] asked = {0};
        java.util.function.BooleanSupplier speaking = () -> { asked[0]++; return true; };
        java.util.function.BooleanSupplier silent = () -> false;
        assertTrue(window.keepListening(2_999, silent));
        assertEquals("Inside the window the decoder is not asked", 0, asked[0]);
        assertFalse(window.keepListening(3_000, silent));
        assertTrue(window.keepListening(3_000, speaking));
        assertTrue(window.keepListening(5_999, speaking));
        assertFalse(window.keepListening(6_000, speaking));
        assertEquals(2, asked[0]);
        assertTrue(VoiceAudioOptions.ListeningWindow.SIX_SECONDS.keepListening(8_500, speaking));
        assertFalse(VoiceAudioOptions.ListeningWindow.SIX_SECONDS.keepListening(9_000, speaking));
    }

    @Test public void captureAndDisplayUseSeparateStrictPresets() {
        int[] captureSeconds = {3, 4, 6};
        int[] displaySeconds = {1, 2, 4};
        for (int seconds : captureSeconds) {
            var window = VoiceAudioOptions.ListeningWindow.fromKey(Integer.toString(seconds));
            assertEquals(seconds * 1000L, window.millis());
            assertEquals((seconds + 3) * 16_000, window.maxSamples(16_000));
        }
        for (int seconds : displaySeconds) {
            var duration = VoiceAudioOptions.ResultDuration.fromKey(Integer.toString(seconds));
            assertEquals(seconds * 1000L, duration.millis());
        }
        assertNull(VoiceAudioOptions.ListeningWindow.fromKey("2"));
        assertNull(VoiceAudioOptions.ListeningWindow.fromKey("06"));
        assertNull(VoiceAudioOptions.ResultDuration.fromKey("6"));
        assertNull(VoiceAudioOptions.ResultDuration.fromKey(null));
    }

    @Test public void endCueUsesItsLegacyGainOrAnIndependentExactLevel() {
        for (var cue : VoiceAudioOptions.EndCue.values()) {
            var legacy = new VoiceAudioOptions(85, 70, true, 20, VoiceAudioOptions.FeedbackStyle.OUTCOME, cue);
            assertNull(legacy.endCueVolumePercent());
            assertEquals(cue.gain(), legacy.endCueGain(), 0f);
        }
        var exact = cueOptions(VoiceAudioOptions.EndCue.SOFT, 37);
        assertEquals(.37f, exact.endCueGain(), 0f);
        assertEquals(.7f, exact.speechGain(), 0f);
        assertEquals(.2f, exact.readyCueGain(), 0f);
        assertEquals(0f, cueOptions(VoiceAudioOptions.EndCue.OFF, 100).endCueGain(), 0f);
        assertEquals(0f, cueOptions(VoiceAudioOptions.EndCue.STANDARD, 0).endCueGain(), 0f);
        assertEquals(1f, cueOptions(VoiceAudioOptions.EndCue.STANDARD, 100).endCueGain(), 0f);
        assertThrows(IllegalArgumentException.class, () -> cueOptions(VoiceAudioOptions.EndCue.OFF, -1));
        assertThrows(IllegalArgumentException.class, () -> cueOptions(VoiceAudioOptions.EndCue.SOFT, 101));
    }

    private static VoiceAudioOptions cueOptions(VoiceAudioOptions.EndCue cue, int volume) {
        return new VoiceAudioOptions(85, 70, true, 20, VoiceAudioOptions.FeedbackStyle.OUTCOME, cue,
            VoiceAudioOptions.ListeningWindow.FOUR_SECONDS, VoiceAudioOptions.ResultDuration.TWO_SECONDS, true, volume);
    }
}
