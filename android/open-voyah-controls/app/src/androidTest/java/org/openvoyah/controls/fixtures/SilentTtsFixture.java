package org.openvoyah.controls.fixtures;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.os.Process;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Test-only engine that reports synthesis arguments and produces a short buffer of silence. */
public final class SilentTtsFixture extends TextToSpeechService {
    public static final String REPLY = "org.openvoyah.controls.fixture.SYNTHESIS";
    public static final String SAMPLE_PREFIX = "ovc-test-silent-";
    public static final String ACCEPTED_OUTCOME = "The vehicle accepted the request.";
    public static final String READY_EN = "Ready.";
    public static final String READY_RU = "Слушаю.";
    public static final String READY_CUSTOM = "Go ahead.";
    public static final String AUTOMATIC = "fixture-en-automatic";
    public static final String PREFERRED = "fixture-en-preferred";
    public static final String RUSSIAN = "fixture-ru-automatic";
    private static final String APP = "org.openvoyah.controls";

    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return "eng".equals(language) || "rus".equals(language) ? TextToSpeech.LANG_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override protected int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }

    @Override protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }
    @Override protected void onStop() { }

    @Override public List<Voice> onGetVoices() {
        return java.util.Arrays.asList(
            new Voice(AUTOMATIC, Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false, Collections.emptySet()),
            new Voice(PREFERRED, Locale.UK, Voice.QUALITY_NORMAL, Voice.LATENCY_LOW, false, Collections.emptySet()),
            new Voice(RUSSIAN, Locale.forLanguageTag("ru-RU"), Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false, Collections.emptySet()));
    }

    @Override public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        return "rus".equals(language) ? RUSSIAN : AUTOMATIC;
    }

    @Override public int onIsValidVoiceName(String name) {
        return AUTOMATIC.equals(name) || PREFERRED.equals(name) || RUSSIAN.equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getCharSequenceText().toString();
        if ((!text.matches(SAMPLE_PREFIX + "[0-9a-f-]{36}") && !ACCEPTED_OUTCOME.equals(text) &&
                !READY_EN.equals(text) && !READY_RU.equals(text) && !READY_CUSTOM.equals(text)) ||
                getPackageManager().checkSignatures(Process.myUid(), request.getCallerUid()) != PackageManager.SIGNATURE_MATCH) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
            callback.done();
            return;
        }
        Intent observed = new Intent(REPLY).setPackage(APP).putExtra("sample", text)
            .putExtra("voice", request.getVoiceName()).putExtra("rate", request.getSpeechRate())
            .putExtra("gain", request.getParams().getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, -1f));
        for (String key : request.getParams().keySet()) {
            if (request.getParams().get(key) instanceof AudioAttributes audio) {
                observed.putExtra("usage", audio.getUsage()).putExtra("contentType", audio.getContentType());
            }
        }
        sendBroadcast(observed);
        if (callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
            byte[] silence = new byte[320];
            callback.audioAvailable(silence, 0, silence.length);
        }
        callback.done();
    }
}
