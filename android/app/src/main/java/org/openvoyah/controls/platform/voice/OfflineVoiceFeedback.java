package org.openvoyah.controls.platform.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

/** Optional speech through an installed offline Android voice for the command language. */
public final class OfflineVoiceFeedback implements AutoCloseable {
    private final TextToSpeech engine;
    private final String language;
    private String pending;
    private boolean initialized;
    private boolean available;
    private boolean closed;

    public OfflineVoiceFeedback(Context context, String language) {
        this.language = language;
        Handler main = new Handler(Looper.getMainLooper());
        engine = new TextToSpeech(context.getApplicationContext(), result -> main.post(() -> ready(result)));
    }

    private void ready(int result) {
        if (closed) return;
        initialized = true;
        if (result != TextToSpeech.SUCCESS) { pending = null; return; }
        Voice preferred = null;
        var voices = engine.getVoices();
        if (voices != null) for (Voice voice : voices) {
            var features = voice.getFeatures();
            if (!voice.getLocale().getLanguage().equals(language) || voice.isNetworkConnectionRequired()
                    || features == null || features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            if (preferred == null || voice.getQuality() > preferred.getQuality()) preferred = voice;
        }
        available = preferred != null && engine.setVoice(preferred) == TextToSpeech.SUCCESS;
        engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        if (pending != null) {
            String queued = pending;
            pending = null;
            speak(queued);
        }
    }

    public void speak(String message) {
        if (closed) return;
        if (!initialized) { pending = message; return; }
        if (available) engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "command");
    }

    public void stop() {
        pending = null;
        if (available) engine.stop();
    }

    @Override public void close() {
        closed = true;
        stop();
        engine.shutdown();
    }
}
