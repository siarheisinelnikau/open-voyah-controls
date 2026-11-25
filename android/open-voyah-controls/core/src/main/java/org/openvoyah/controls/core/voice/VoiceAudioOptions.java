package org.openvoyah.controls.core.voice;

import java.util.Objects;

/** Local capture, playback and popup preferences for a single voice interaction. */
public record VoiceAudioOptions(int speechRatePercent, int speechVolumePercent,
        boolean readyCue, int readyCueVolumePercent, FeedbackStyle feedbackStyle, EndCue endCue,
        ListeningWindow listeningWindow, ResultDuration resultDuration, boolean showRecognizedPhrase, Integer endCueVolumePercent,
        ReadyCueStyle readyCueStyle, SpokenReplyChannel spokenReplyChannel) {
    public static final int MIN_RATE = 50;
    public static final int MAX_RATE = 150;
    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 100;
    public static final int MAX_READY_PROMPT_CHARACTERS = 32;
    public static final VoiceAudioOptions DEFAULTS = new VoiceAudioOptions(100, 100, false, 35);

    public enum ReadyCueStyle {
        TONE("tone"), SPOKEN("spoken");

        private final String key;
        ReadyCueStyle(String key) { this.key = key; }
        public String key() { return key; }

        public static ReadyCueStyle fromKey(String key) {
            for (var style : values()) if (style.key.equals(key)) return style;
            return null;
        }
    }

    public enum SpokenReplyChannel {
        ASSISTANT("assistant"), MEDIA("media");

        private final String key;
        SpokenReplyChannel(String key) { this.key = key; }
        public String key() { return key; }

        public static SpokenReplyChannel fromKey(String key) {
            for (var channel : values()) if (channel.key.equals(key)) return channel;
            return null;
        }
    }

    public enum FeedbackStyle {
        READBACK("readback"), OUTCOME("outcome");

        private final String key;
        FeedbackStyle(String key) { this.key = key; }
        public String key() { return key; }

        public static FeedbackStyle fromKey(String key) {
            for (FeedbackStyle style : values()) if (style.key.equals(key)) return style;
            return null;
        }
    }

    public enum EndCue {
        OFF("off", 0f), SOFT("soft", .25f), STANDARD("standard", .65f);

        private final String key;
        private final float gain;
        EndCue(String key, float gain) { this.key = key; this.gain = gain; }
        public String key() { return key; }
        public float gain() { return gain; }

        public static EndCue fromKey(String key) {
            for (EndCue cue : values()) if (cue.key.equals(key)) return cue;
            return null;
        }
    }

    /**
     * How long the microphone waits for a command. A phrase still being spoken when the window ends may run up to
     * [EXTENSION_SECONDS] longer, so a long command such as a temperature with its seat is not cut off; silence still
     * ends listening on time.
     */
    public enum ListeningWindow {
        THREE_SECONDS(3), FOUR_SECONDS(4), SIX_SECONDS(6);

        public static final int EXTENSION_SECONDS = 3;
        private final int seconds;
        ListeningWindow(int seconds) { this.seconds = seconds; }
        public int seconds() { return seconds; }
        public long millis() { return seconds * 1000L; }
        /** Samples for the window plus the extension: the most a capture can record. */
        public int maxSamples(int sampleRate) { return Math.multiplyExact(seconds + EXTENSION_SECONDS, sampleRate); }

        /**
         * Whether to keep recording [elapsedMillis] after the microphone started: always inside the window, then only
         * while [speaking] says a phrase is in progress, and never past the extension.
         */
        public boolean keepListening(long elapsedMillis, java.util.function.BooleanSupplier speaking) {
            if (elapsedMillis < millis()) return true;
            if (elapsedMillis >= millis() + EXTENSION_SECONDS * 1000L) return false;
            return speaking.getAsBoolean();
        }

        public static ListeningWindow fromKey(String key) {
            for (var window : values()) if (Integer.toString(window.seconds).equals(key)) return window;
            return null;
        }
    }

    public enum ResultDuration {
        ONE_SECOND(1), TWO_SECONDS(2), FOUR_SECONDS(4);

        private final int seconds;
        ResultDuration(int seconds) { this.seconds = seconds; }
        public int seconds() { return seconds; }
        public long millis() { return seconds * 1000L; }

        public static ResultDuration fromKey(String key) {
            for (var duration : values()) if (Integer.toString(duration.seconds).equals(key)) return duration;
            return null;
        }
    }

    public VoiceAudioOptions(int speechRatePercent, int speechVolumePercent, boolean readyCue, int readyCueVolumePercent) {
        this(speechRatePercent, speechVolumePercent, readyCue, readyCueVolumePercent, FeedbackStyle.READBACK, EndCue.OFF);
    }

    public VoiceAudioOptions(int speechRatePercent, int speechVolumePercent, boolean readyCue,
            int readyCueVolumePercent, FeedbackStyle feedbackStyle, EndCue endCue) {
        this(speechRatePercent, speechVolumePercent, readyCue, readyCueVolumePercent, feedbackStyle, endCue,
            ListeningWindow.FOUR_SECONDS, ResultDuration.TWO_SECONDS, true);
    }

    public VoiceAudioOptions(int speechRatePercent, int speechVolumePercent, boolean readyCue,
            int readyCueVolumePercent, FeedbackStyle feedbackStyle, EndCue endCue,
            ListeningWindow listeningWindow, ResultDuration resultDuration, boolean showRecognizedPhrase) {
        this(speechRatePercent, speechVolumePercent, readyCue, readyCueVolumePercent, feedbackStyle, endCue,
            listeningWindow, resultDuration, showRecognizedPhrase, null);
    }

    public VoiceAudioOptions(int speechRatePercent, int speechVolumePercent, boolean readyCue,
            int readyCueVolumePercent, FeedbackStyle feedbackStyle, EndCue endCue,
            ListeningWindow listeningWindow, ResultDuration resultDuration, boolean showRecognizedPhrase, Integer endCueVolumePercent) {
        this(speechRatePercent, speechVolumePercent, readyCue, readyCueVolumePercent, feedbackStyle, endCue,
            listeningWindow, resultDuration, showRecognizedPhrase, endCueVolumePercent, ReadyCueStyle.TONE, SpokenReplyChannel.ASSISTANT);
    }

    public VoiceAudioOptions {
        Objects.requireNonNull(feedbackStyle);
        Objects.requireNonNull(endCue);
        Objects.requireNonNull(listeningWindow);
        Objects.requireNonNull(resultDuration);
        Objects.requireNonNull(readyCueStyle);
        Objects.requireNonNull(spokenReplyChannel);
        if (speechRatePercent < MIN_RATE || speechRatePercent > MAX_RATE
                || speechVolumePercent < MIN_VOLUME || speechVolumePercent > MAX_VOLUME
                || readyCueVolumePercent < MIN_VOLUME || readyCueVolumePercent > MAX_VOLUME
                || endCueVolumePercent != null && (endCueVolumePercent < MIN_VOLUME || endCueVolumePercent > MAX_VOLUME)) {
            throw new IllegalArgumentException("Invalid voice audio percentage");
        }
    }

    public float speechRate() { return speechRatePercent / 100f; }
    public float speechGain() { return speechVolumePercent / 100f; }
    public float readyCueGain() { return readyCueVolumePercent / 100f; }
    public float endCueGain() {
        if (endCue == EndCue.OFF || endCueVolumePercent == null) return endCue.gain();
        return endCueVolumePercent / 100f;
    }

    public static boolean validOfflineVoiceName(String value) {
        return value.isEmpty() || (value.length() <= 256 && value.codePoints().anyMatch(c -> !Character.isWhitespace(c))
            && value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT));
    }

    /** An empty preference follows the recognition language's built-in prompt. */
    public static boolean validReadyPrompt(String value) {
        if (value == null || value.length() > 2 * MAX_READY_PROMPT_CHARACTERS) return false;
        if (value.isEmpty()) return true;
        return value.codePointCount(0, value.length()) <= MAX_READY_PROMPT_CHARACTERS
            && value.codePoints().anyMatch(Character::isLetterOrDigit)
            && value.codePoints().noneMatch(c -> switch (Character.getType(c)) {
                case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true;
                default -> false;
            });
    }
}
