package org.openvoyah.controls.data.settings;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.VoiceCatalog;
import org.openvoyah.controls.core.voice.SpokenValueCommands;
import org.openvoyah.controls.core.voice.VoiceAudioOptions;
import org.openvoyah.controls.diagnostics.ControlsLog;
import org.openvoyah.controls.domain.audio.DuckSettings;
import org.openvoyah.controls.domain.audio.StartupVolume;
import org.openvoyah.controls.domain.voice.SpeechEngine;

/** Voice preferences have independent ownership; a panel failure cannot corrupt them. */
public final class VoiceSettings {
    private static final String SPEECH_RATE = "speechRatePercent";
    private static final String SPEECH_VOLUME = "speechVolumePercent";
    private static final String READY_CUE = "readyCue";
    private static final String READY_CUE_VOLUME = "readyCueVolumePercent";
    private static final String READY_CUE_STYLE = "readyCueStyle";
    private static final String SPOKEN_REPLY_CHANNEL = "spokenReplyChannel";
    private static final String FEEDBACK_STYLE = "speechFeedbackStyle";
    private static final String END_CUE = "endListeningCue";
    private static final String END_CUE_VOLUME = "endCueVolumePercent";
    private static final String PRESET_VOLUME = "preset";
    private static final String LISTENING_WINDOW = "listeningWindowSeconds";
    private static final String RESULT_DURATION = "resultDisplaySeconds";
    private static final String SHOW_PHRASE = "showRecognizedPhrase";
    private static final String OFFLINE_VOICE_EN = "offlineVoice.en";
    private static final String OFFLINE_VOICE_RU = "offlineVoice.ru";
    private static final String READY_PROMPT_EN = "readyPrompt.en";
    private static final String READY_PROMPT_RU = "readyPrompt.ru";
    private static final String OFFLINE_VOICE_KK = "offlineVoice.kk";
    private static final String READY_PROMPT_KK = "readyPrompt.kk";
    /** The speech engine the user chose; it applies to the languages it supports, others use Vosk. */
    private static final String ENGINE = "engine";
    /** Media ducking: while listening (the historical key), during phone calls, and the share of media that stays. */
    private static final String DUCK_LISTENING = "duckMedia";
    private static final String DUCK_CALLS = "duckCalls";
    private static final String DUCK_LEVEL = "duckLevelPercent";
    /** The media level set once per head unit boot. */
    private static final String STARTUP_VOLUME = "startupVolume";
    private static final String STARTUP_VOLUME_PERCENT = "startupVolumePercent";
    // Set.of needs API 30; Android 9 head units run API 28.
    private static final Set<String> LANGUAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("ru", "en", "kk")));
    private static final String CONTACT_PREFIX = "contact.";
    private static final Pattern CONTACT_PHONE = Pattern.compile("\\+?[0-9]{3,15}");
    private static final int MAX_VOICE_VALUES = 512 + 16;
    private static final Object WRITE_LOCK = new Object();
    private final SharedPreferences prefs;
    public VoiceSettings(Context context) { prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE); }
    public static VoiceCatalog catalog() throws IOException {
        return new VoiceCatalog(VoiceSettings.class.getResourceAsStream("/voice-catalog.tsv"), SpokenValueCommands.commands("ru"));
    }
    public String language() {
        String stored = prefs.getString("language", "ru");
        return LANGUAGES.contains(stored) ? stored : "ru";
    }
    public void language(String value) { update("language", value); }
    public String engine() { return prefs.getString(ENGINE, SpeechEngine.VOSK.getKey()); }
    public void engine(String value) {
        if (SpeechEngine.Companion.fromKey(value) == null) throw new IllegalArgumentException("Invalid speech engine");
        update(ENGINE, value);
    }
    public int level(String command) { return Integer.parseInt(prefs.getString("level."+command,"2")); }
    public void level(String command,int value) { update("level."+command,Integer.toString(value)); }
    public boolean option(String key) { return prefs.getString(key,"false").equals("true"); }
    public void option(String key,boolean value) { update(key,Boolean.toString(value)); }
    public void windowOptions(boolean doubleSmoke, boolean doubleVentilation) {
        synchronized (WRITE_LOCK) {
            try {
                JSONObject root = new JSONObject(exportJson());
                root.getJSONObject("values").put("doubleSmoke", Boolean.toString(doubleSmoke))
                    .put("doubleVentilation", Boolean.toString(doubleVentilation));
                importJson(root.toString());
            } catch (Exception ex) { throw new IllegalStateException(UiText.get(R.string.ui_219), ex); }
        }
    }
    public void audio(boolean duckMedia, boolean spokenFeedback) {
        synchronized (WRITE_LOCK) { audio(duckMedia, spokenFeedback, audioOptions()); }
    }

    public void audio(boolean duckMedia, boolean spokenFeedback, VoiceAudioOptions options) {
        audio(duckMedia, spokenFeedback, options, null, "");
    }

    public void audio(boolean duckMedia, boolean spokenFeedback, VoiceAudioOptions options, String language, String voiceName) {
        audio(duckMedia, spokenFeedback, options, language, voiceName, null);
    }

    public void audio(boolean duckMedia, boolean spokenFeedback, VoiceAudioOptions options, String language, String voiceName,
                      String readyPrompt) {
        synchronized (WRITE_LOCK) {
            DuckSettings current = mediaDuck();
            audio(new DuckSettings(duckMedia, current.getCalls(), current.getLevelPercent()), spokenFeedback, options, language,
                voiceName, readyPrompt);
        }
    }

    public void audio(DuckSettings duck, boolean spokenFeedback, VoiceAudioOptions options, String language, String voiceName,
                      String readyPrompt) {
        synchronized (WRITE_LOCK) { audio(duck, startupVolume(), spokenFeedback, options, language, voiceName, readyPrompt); }
    }

    public void audio(DuckSettings duck, StartupVolume startup, boolean spokenFeedback, VoiceAudioOptions options, String language,
                      String voiceName, String readyPrompt) {
        if (!DuckSettings.validLevel(duck.getLevelPercent())) throw new IllegalArgumentException("Invalid media duck level");
        if (!StartupVolume.validPercent(startup.getPercent())) throw new IllegalArgumentException("Invalid startup volume");
        synchronized (WRITE_LOCK) {
            try {
                JSONObject root = new JSONObject(exportJson());
                JSONObject values = writeStartup(writeDuck(root.getJSONObject("values"), duck), startup)
                    .put("spokenFeedback", Boolean.toString(spokenFeedback));
                writeAudioOptions(values, options);
                if (language != null) {
                    values.put(languageKey(language, OFFLINE_VOICE_EN, OFFLINE_VOICE_RU, OFFLINE_VOICE_KK), voiceName);
                    if (readyPrompt != null) {
                        values.put(languageKey(language, READY_PROMPT_EN, READY_PROMPT_RU, READY_PROMPT_KK), readyPrompt);
                    }
                }
                importJson(root.toString());
            } catch (Exception ex) { throw new IllegalStateException(UiText.get(R.string.ui_219), ex); }
        }
    }

    /** Settings → Sound and media: media ducking and the volume after the head unit starts, nothing else. */
    public void media(DuckSettings duck, StartupVolume startup) {
        if (!DuckSettings.validLevel(duck.getLevelPercent())) throw new IllegalArgumentException("Invalid media duck level");
        if (!StartupVolume.validPercent(startup.getPercent())) throw new IllegalArgumentException("Invalid startup volume");
        synchronized (WRITE_LOCK) {
            try {
                JSONObject root = new JSONObject(exportJson());
                writeStartup(writeDuck(root.getJSONObject("values"), duck), startup);
                importJson(root.toString());
            } catch (Exception ex) { throw new IllegalStateException(UiText.get(R.string.ui_219), ex); }
        }
    }

    public VoiceAudioOptions audioOptions() { return readAudioOptions(prefs.getAll()); }

    /** Both duck switches are on until the owner turns them off. */
    public DuckSettings mediaDuck() { return readDuck(prefs.getAll()); }

    private static DuckSettings readDuck(Map<String, ?> values) {
        int level = storedPercent(values.get(DUCK_LEVEL), 0, 100, DuckSettings.DEFAULT_LEVEL);
        return new DuckSettings(!"false".equals(values.get(DUCK_LISTENING)), !"false".equals(values.get(DUCK_CALLS)),
            DuckSettings.validLevel(level) ? level : DuckSettings.DEFAULT_LEVEL);
    }

    /** On at 10 % until the owner changes it. */
    public StartupVolume startupVolume() { return readStartup(prefs.getAll()); }

    private static StartupVolume readStartup(Map<String, ?> values) {
        return new StartupVolume(!"false".equals(values.get(STARTUP_VOLUME)), storedPercent(values.get(STARTUP_VOLUME_PERCENT),
            StartupVolume.MIN_PERCENT, StartupVolume.MAX_PERCENT, StartupVolume.DEFAULT_PERCENT));
    }

    private static JSONObject writeStartup(JSONObject values, StartupVolume startup) throws JSONException {
        return values.put(STARTUP_VOLUME, Boolean.toString(startup.getEnabled()))
            .put(STARTUP_VOLUME_PERCENT, Integer.toString(startup.getPercent()));
    }

    private static JSONObject writeDuck(JSONObject values, DuckSettings duck) throws JSONException {
        return values.put(DUCK_LISTENING, Boolean.toString(duck.getListening()))
            .put(DUCK_CALLS, Boolean.toString(duck.getCalls()))
            .put(DUCK_LEVEL, Integer.toString(duck.getLevelPercent()));
    }

    public String offlineVoice(String language) {
        return storedVoice(prefs.getAll().get(languageKey(language, OFFLINE_VOICE_EN, OFFLINE_VOICE_RU, OFFLINE_VOICE_KK)));
    }

    public String readyPrompt(String language) {
        return storedReadyPrompt(prefs.getAll().get(languageKey(language, READY_PROMPT_EN, READY_PROMPT_RU, READY_PROMPT_KK)));
    }

    private static String languageKey(String language, String english, String russian, String kazakh) {
        return switch (language) {
            case "en" -> english;
            case "ru" -> russian;
            case "kk" -> kazakh;
            default -> throw new IllegalArgumentException("Invalid voice language");
        };
    }

    private static String storedVoice(Object value) {
        return value instanceof String name && VoiceAudioOptions.validOfflineVoiceName(name) ? name : "";
    }

    private static String storedReadyPrompt(Object value) {
        return value instanceof String prompt && VoiceAudioOptions.validReadyPrompt(prompt) ? prompt : "";
    }

    private static VoiceAudioOptions readAudioOptions(Map<String, ?> values) {
        var defaults = VoiceAudioOptions.DEFAULTS;
        var style = VoiceAudioOptions.FeedbackStyle.fromKey(values.get(FEEDBACK_STYLE) instanceof String key ? key : null);
        var endCue = VoiceAudioOptions.EndCue.fromKey(values.get(END_CUE) instanceof String key ? key : null);
        var listening = VoiceAudioOptions.ListeningWindow.fromKey(values.get(LISTENING_WINDOW) instanceof String key ? key : null);
        var duration = VoiceAudioOptions.ResultDuration.fromKey(values.get(RESULT_DURATION) instanceof String key ? key : null);
        var readyStyle = VoiceAudioOptions.ReadyCueStyle.fromKey(values.get(READY_CUE_STYLE) instanceof String key ? key : null);
        var channel = VoiceAudioOptions.SpokenReplyChannel.fromKey(values.get(SPOKEN_REPLY_CHANNEL) instanceof String key ? key : null);
        return new VoiceAudioOptions(
            storedPercent(values.get(SPEECH_RATE), VoiceAudioOptions.MIN_RATE, VoiceAudioOptions.MAX_RATE, defaults.speechRatePercent()),
            storedPercent(values.get(SPEECH_VOLUME), VoiceAudioOptions.MIN_VOLUME, VoiceAudioOptions.MAX_VOLUME, defaults.speechVolumePercent()),
            "true".equals(values.get(READY_CUE)),
            storedPercent(values.get(READY_CUE_VOLUME), VoiceAudioOptions.MIN_VOLUME, VoiceAudioOptions.MAX_VOLUME, defaults.readyCueVolumePercent()),
            style == null ? defaults.feedbackStyle() : style,
            endCue == null ? defaults.endCue() : endCue,
            listening == null ? defaults.listeningWindow() : listening,
            duration == null ? defaults.resultDuration() : duration,
            !"false".equals(values.get(SHOW_PHRASE)),
            storedEndCueVolume(values.get(END_CUE_VOLUME)),
            readyStyle == null ? defaults.readyCueStyle() : readyStyle,
            channel == null ? defaults.spokenReplyChannel() : channel);
    }

    private static Integer storedEndCueVolume(Object setting) {
        if (setting == null || PRESET_VOLUME.equals(setting)) return null;
        int volume = storedPercent(setting, VoiceAudioOptions.MIN_VOLUME, VoiceAudioOptions.MAX_VOLUME, -1);
        return volume < 0 ? null : volume;
    }

    private static int storedPercent(Object setting, int minimum, int maximum, int fallback) {
        if (!(setting instanceof String)) return fallback;
        try { return percent(setting, minimum, maximum); }
        catch (IllegalArgumentException ex) { return fallback; }
    }

    private static int percent(Object setting, int minimum, int maximum) {
        if (!(setting instanceof String text) || !text.matches("0|[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("Invalid voice audio percentage");
        }
        int value = Integer.parseInt(text);
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Invalid voice audio percentage");
        return value;
    }

    private static JSONObject writeAudioOptions(JSONObject values, VoiceAudioOptions options) throws JSONException {
        return values.put(SPEECH_RATE, Integer.toString(options.speechRatePercent()))
            .put(SPEECH_VOLUME, Integer.toString(options.speechVolumePercent()))
            .put(READY_CUE, Boolean.toString(options.readyCue()))
            .put(READY_CUE_VOLUME, Integer.toString(options.readyCueVolumePercent()))
            .put(READY_CUE_STYLE, options.readyCueStyle().key())
            .put(SPOKEN_REPLY_CHANNEL, options.spokenReplyChannel().key())
            .put(FEEDBACK_STYLE, options.feedbackStyle().key())
            .put(END_CUE, options.endCue().key())
            .put(END_CUE_VOLUME, options.endCueVolumePercent() == null ? PRESET_VOLUME : options.endCueVolumePercent().toString())
            .put(LISTENING_WINDOW, Integer.toString(options.listeningWindow().seconds()))
            .put(RESULT_DURATION, Integer.toString(options.resultDuration().seconds()))
            .put(SHOW_PHRASE, Boolean.toString(options.showRecognizedPhrase()));
    }

    private static JSONObject writeAudioSettings(JSONObject values, Map<String, ?> snapshot) throws JSONException {
        writeStartup(writeDuck(values, readDuck(snapshot)), readStartup(snapshot));
        return writeAudioOptions(values, readAudioOptions(snapshot))
            .put(OFFLINE_VOICE_EN, storedVoice(snapshot.get(OFFLINE_VOICE_EN)))
            .put(OFFLINE_VOICE_RU, storedVoice(snapshot.get(OFFLINE_VOICE_RU)))
            .put(OFFLINE_VOICE_KK, storedVoice(snapshot.get(OFFLINE_VOICE_KK)))
            .put(READY_PROMPT_EN, storedReadyPrompt(snapshot.get(READY_PROMPT_EN)))
            .put(READY_PROMPT_RU, storedReadyPrompt(snapshot.get(READY_PROMPT_RU)))
            .put(READY_PROMPT_KK, storedReadyPrompt(snapshot.get(READY_PROMPT_KK)));
    }

    public JSONObject options(String phrase) throws Exception {
        JSONObject levels=new JSONObject();
        for(var command:catalog().all()) if(command.id().endsWith("LevelDefault")) levels.put(command.id(),level(command.id()));
        return new JSONObject().put("profile",profile()).put("phrase",phrase).put("levels",levels)
            .put("doubleSmoke",option("doubleSmoke")).put("doubleVentilation",option("doubleVentilation"));
    }
    private String callPrefix() {
        return switch (language()) {
            case "en" -> "call ";
            case "kk" -> "\u049b\u043e\u04a3\u044b\u0440\u0430\u0443 \u0448\u0430\u043b ";
            default -> "\u043f\u043e\u0437\u0432\u043e\u043d\u0438 ";
        };
    }
    private String dialPrefix() {
        return switch (language()) {
            case "en" -> "dial ";
            case "kk" -> "\u0445\u0430\u0431\u0430\u0440\u043b\u0430\u0441 ";
            default -> "\u043d\u0430\u0431\u0435\u0440\u0438 ";
        };
    }
    private String catalogPath() {
        return switch (language()) {
            case "en" -> "/voice-catalog-en.tsv";
            case "kk" -> "/voice-catalog-kk.tsv";
            default -> "/voice-catalog.tsv";
        };
    }

    public record LocalContact(String id, String name, String phone) { }

    List<LocalContact> localContacts() {
        List<LocalContact> contacts = new ArrayList<>();
        for (var entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(CONTACT_PREFIX) || !(entry.getValue() instanceof String raw)) continue;
            try {
                JSONObject contact = new JSONObject(raw);
                String name = VoiceCatalog.normalize(contact.getString("name"));
                String phone = contact.getString("phone");
                if (!VoiceCatalog.isBlank(name) && name.length() <= 60 && CONTACT_PHONE.matcher(phone).matches()) {
                    contacts.add(new LocalContact(entry.getKey().substring(CONTACT_PREFIX.length()), name, phone));
                }
            } catch (JSONException ignored) { }
        }
        contacts.sort(Comparator.comparing(LocalContact::name).thenComparing(LocalContact::id));
        return contacts;
    }

    Runnable observeContactStorage(Runnable changed) {
        // Older Android versions may only notify keys written after a clear during an import.
        SharedPreferences.OnSharedPreferenceChangeListener listener = (store, key) -> changed.run();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        return () -> prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public VoiceCatalog withContacts() throws IOException {
        List<VoiceCatalog.Command> extra = SpokenValueCommands.commands(language());
        for(var entry:prefs.getAll().entrySet()) if(entry.getKey().startsWith(CONTACT_PREFIX)) {
            try {
                JSONObject contact=new JSONObject((String)entry.getValue()); String name=contact.getString("name");
                extra.add(new VoiceCatalog.Command("Call_"+entry.getKey().substring(CONTACT_PREFIX.length()),"Contacts",Arrays.asList(callPrefix()+name,dialPrefix()+name)));
            } catch(Exception ex) { throw new IOException(UiText.get(R.string.ui_210),ex); }
        }
        return new VoiceCatalog(VoiceSettings.class.getResourceAsStream(catalogPath()),extra);
    }
    public String phone(String id) throws JSONException { return new JSONObject(prefs.getString(CONTACT_PREFIX+id.substring(5),"{}")).getString("phone"); }
    public void contact(String name,String phone) throws Exception {
        synchronized (WRITE_LOCK) {
            name=VoiceCatalog.normalize(name);
            if(VoiceCatalog.isBlank(name) || name.length()>60 || !CONTACT_PHONE.matcher(phone).matches()) throw new IllegalArgumentException(UiText.get(R.string.ui_211));
            for(var c:withContacts().all()) if(c.phrases().contains(callPrefix()+name)) throw new IllegalArgumentException(UiText.get(R.string.ui_212));
            JSONObject root=new JSONObject(exportJson());
            root.getJSONObject("values").put(CONTACT_PREFIX+UUID.randomUUID(),new JSONObject().put("name",name).put("phone",phone).toString());
            importJson(root.toString());
        }
    }
    public String exportJson() {
        return encode(prefs.getAll());
    }

    private static String encode(Map<String, ?> snapshot) {
        JSONObject values=new JSONObject();
        try {
            for(var e:snapshot.entrySet()) if(!e.getKey().equals("backup")) values.put(e.getKey(),e.getValue());
            writeAudioSettings(values, snapshot);
            return new JSONObject().put("schema",1).put("values",values).toString(2);
        } catch(JSONException ex) { throw new IllegalStateException(ex); }
    }
    static JSONObject validate(String raw) throws Exception {
        if(raw.length()>1048576) throw new IllegalArgumentException(UiText.get(R.string.ui_154));
        try { return validateValues(raw); }
        catch (JSONException ex) {
            // org.json messages quote the input, and contact entries hold names and phone numbers.
            ControlsLog.warn("settings_import", ex, "store", "voice", "outcome", "invalid_json");
            throw new IllegalArgumentException(UiText.get(R.string.ui_213), ex);
        }
    }
    private static JSONObject validateValues(String raw) throws Exception {
        JSONObject root=new JSONObject(raw), values=root.getJSONObject("values");
        if(root.getInt("schema")!=1 || values.length()>MAX_VOICE_VALUES) throw new IllegalArgumentException(UiText.get(R.string.ui_213));
        Set<String> names=new HashSet<>();
        VoiceCatalog commands=catalog();
        Iterator<String> keys=values.keys();
        while(keys.hasNext()) {
            String key=keys.next(), value=values.getString(key);
            if(value.length()>1024) throw new IllegalArgumentException(UiText.get(R.string.ui_214));
            if(key.equals("language")) { if(!LANGUAGES.contains(value)) throw new IllegalArgumentException("Invalid voice language"); }
            else if(key.equals(ENGINE)) { if(SpeechEngine.Companion.fromKey(value) == null) throw new IllegalArgumentException("Invalid speech engine"); }
            else if(key.startsWith("level.")) {
                if(!key.endsWith("LevelDefault") || commands.byId(key.substring(6))==null || !value.matches("[123]")) throw new IllegalArgumentException("Invalid default level");
            }
            else if(key.equals("doubleSmoke") || key.equals("doubleVentilation") || key.equals(DUCK_LISTENING) || key.equals(DUCK_CALLS) || key.equals(STARTUP_VOLUME)
                || key.equals("spokenFeedback")) {
                if(!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid voice option");
            }
            else if (key.equals(DUCK_LEVEL)) {
                if (!DuckSettings.validLevel(percent(values.get(key), 0, 100))) throw new IllegalArgumentException("Invalid media duck level");
            }
            else if (key.equals(STARTUP_VOLUME_PERCENT)) percent(values.get(key), StartupVolume.MIN_PERCENT, StartupVolume.MAX_PERCENT);
            else if (key.equals(SPEECH_RATE)) percent(values.get(key), VoiceAudioOptions.MIN_RATE, VoiceAudioOptions.MAX_RATE);
            else if (key.equals(SPEECH_VOLUME) || key.equals(READY_CUE_VOLUME)) {
                percent(values.get(key), VoiceAudioOptions.MIN_VOLUME, VoiceAudioOptions.MAX_VOLUME);
            }
            else if (key.equals(END_CUE_VOLUME)) {
                Object volume = values.get(key);
                if (!PRESET_VOLUME.equals(volume)) percent(volume, VoiceAudioOptions.MIN_VOLUME, VoiceAudioOptions.MAX_VOLUME);
            }
            else if (key.equals(READY_CUE) || key.equals(SHOW_PHRASE)) {
                Object setting = values.get(key);
                if (!"true".equals(setting) && !"false".equals(setting)) throw new IllegalArgumentException("Invalid voice option");
            }
            else if (key.equals(FEEDBACK_STYLE)) {
                if (!(values.get(key) instanceof String choice) || VoiceAudioOptions.FeedbackStyle.fromKey(choice) == null) {
                    throw new IllegalArgumentException("Invalid feedback style");
                }
            }
            else if (key.equals(READY_CUE_STYLE)) {
                if (!(values.get(key) instanceof String choice) || VoiceAudioOptions.ReadyCueStyle.fromKey(choice) == null) {
                    throw new IllegalArgumentException("Invalid ready cue style");
                }
            }
            else if (key.equals(SPOKEN_REPLY_CHANNEL)) {
                if (!(values.get(key) instanceof String choice) || VoiceAudioOptions.SpokenReplyChannel.fromKey(choice) == null) {
                    throw new IllegalArgumentException("Invalid spoken reply channel");
                }
            }
            else if (key.equals(END_CUE)) {
                if (!(values.get(key) instanceof String choice) || VoiceAudioOptions.EndCue.fromKey(choice) == null) {
                    throw new IllegalArgumentException("Invalid end cue");
                }
            }
            else if (key.equals(LISTENING_WINDOW)) {
                if (!(values.get(key) instanceof String choice) || VoiceAudioOptions.ListeningWindow.fromKey(choice) == null) {
                    throw new IllegalArgumentException("Invalid listening window");
                }
            }
            else if (key.equals(RESULT_DURATION)) {
                if (!(values.get(key) instanceof String choice) || VoiceAudioOptions.ResultDuration.fromKey(choice) == null) {
                    throw new IllegalArgumentException("Invalid result display time");
                }
            }
            else if (key.equals(OFFLINE_VOICE_EN) || key.equals(OFFLINE_VOICE_RU) || key.equals(OFFLINE_VOICE_KK)) {
                if (!(values.get(key) instanceof String name) || !VoiceAudioOptions.validOfflineVoiceName(name)) {
                    throw new IllegalArgumentException("Invalid offline voice name");
                }
            }
            else if (key.equals(READY_PROMPT_EN) || key.equals(READY_PROMPT_RU) || key.equals(READY_PROMPT_KK)) {
                if (!(values.get(key) instanceof String prompt) || !VoiceAudioOptions.validReadyPrompt(prompt)) {
                    throw new IllegalArgumentException(String.format(Locale.getDefault(), UiText.get(R.string.voice_ready_phrase_invalid),
                        VoiceAudioOptions.MAX_READY_PROMPT_CHARACTERS));
                }
            }
            else if(key.equals("profile")) { if(!value.equals("UNKNOWN") && !org.openvoyah.controls.core.Firmware.PRODUCTS.containsValue(value)) throw new IllegalArgumentException(UiText.get(R.string.ui_215)); }
            else if(key.startsWith("app.")) {
                if(commands.byId(key.substring(4))!=null && !value.isEmpty() && !value.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) throw new IllegalArgumentException(UiText.get(R.string.ui_216));
            } else if(key.startsWith(CONTACT_PREFIX)) {
                JSONObject c=new JSONObject(value); String name=VoiceCatalog.normalize(c.getString("name"));
                if(VoiceCatalog.isBlank(name) || name.length()>60 || !names.add(name) || !CONTACT_PHONE.matcher(c.getString("phone")).matches()) throw new IllegalArgumentException(UiText.get(R.string.ui_217));
            } else throw new IllegalArgumentException(UiText.get(R.string.ui_218));
        }
        return values;
    }
    public void importJson(String raw) throws Exception {
        JSONObject values = validate(raw);
        VoiceCatalog commands = catalog();
        synchronized (WRITE_LOCK) {
            Map<String, ?> snapshot = prefs.getAll();
            JSONObject effectiveAudio = writeAudioSettings(new JSONObject(), snapshot);
            Iterator<String> audio = effectiveAudio.keys();
            while (audio.hasNext()) {
                String key = audio.next();
                if (!values.has(key)) values.put(key, effectiveAudio.getString(key));
            }
            SharedPreferences.Editor editor = prefs.edit().clear().putString("backup", encode(snapshot));
            Iterator<String> keys=values.keys();
            while(keys.hasNext()) {
                String key=keys.next();
                if(key.startsWith("app.") && commands.byId(key.substring(4))==null) continue;
                editor.putString(key,values.getString(key));
            }
            if (!editor.commit()) {
                SharedPreferences.Editor rollback = prefs.edit().clear();
                for (var entry : snapshot.entrySet()) {
                    if (entry.getValue() instanceof String stored) rollback.putString(entry.getKey(), stored);
                }
                rollback.commit();
                throw new IOException(UiText.get(R.string.ui_219));
            }
        }
    }
    public void restore() throws Exception {
        synchronized (WRITE_LOCK) {
            String backup=prefs.getString("backup",null); if(backup==null) throw new IOException(UiText.get(R.string.ui_220)); importJson(backup);
        }
    }
    public String app(String command) { return prefs.getString("app."+command, ""); }
    public void app(String command, String component) { update("app."+command,component); }
    public String profile() { return prefs.getString("profile", org.openvoyah.controls.core.Firmware.identify(android.os.Build.PRODUCT)); }
    public void profile(String value) { update("profile",value); }
    private void update(String key,String value) {
        synchronized (WRITE_LOCK) {
            try { JSONObject root=new JSONObject(exportJson());root.getJSONObject("values").put(key,value);importJson(root.toString()); }
            catch(Exception ex) { throw new IllegalStateException(UiText.get(R.string.ui_219),ex); }
        }
    }
}
