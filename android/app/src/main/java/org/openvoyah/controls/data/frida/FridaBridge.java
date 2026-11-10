package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.display.DisplayEventBus.Media;
import org.openvoyah.controls.data.display.AppWindowPreferences;
import org.openvoyah.controls.domain.display.AppWindowProfile;
import org.openvoyah.controls.data.settings.LocalizationSettings;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.boot.RecoveryJobs;

/** Coordinates injected agents, their configuration and recovery. */
public final class FridaBridge {
    private static final String KEY = "org.openvoyah.controls.panel";
    private static final String WINDOW_PROFILES = "org.openvoyah.controls.windowProfiles";
    private static final String WINDOW_REQUEST = "org.openvoyah.controls.windowRequest";
    private static final String BASE = "/data/local/tmp/open-voyah-controls";
    private static final String[] CHANNELS = {"", "/locale-setting", "/locale-vehicle", "/locale-bluetooth", "/window", "/navigation"};
    private static final String INJECTOR = "/data/local/tmp/open-voyah-controls-frida-17.18.0";
    private static final Object IO_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService UPDATES = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    private final Context context;
    private final FridaTransport transport;
    public FridaBridge(Context context) { this(context, 23); }
    public FridaBridge(Context context, int port) {
        this.context = context.getApplicationContext();
        transport = new FridaTransport(port, () -> UiText.get(R.string.ui_24), () -> UiText.get(R.string.ui_25));
    }
    public static String config(SettingsStore.State state) {
        try {
            return new JSONObject().put("schema", 1).put("enabled", state.panelEnabled).put("actions", hookActions(state.panelActions))
                .put("steeringEnabled", state.enabled).put("steering", hookActions(state.steeringActions)).toString();
        } catch (JSONException ex) { throw new IllegalArgumentException(ex); }
    }
    private static JSONObject hookActions(java.util.Map<String, Action> assignments) throws JSONException {
        JSONObject result = new JSONObject();
        for (var entry : assignments.entrySet()) {
            Action action = entry.getValue();
            if (action.isAssigned()) result.put(entry.getKey(), new JSONObject().put("kind", action.kind.name()).put("component", action.component));
        }
        return result;
    }
    public static boolean wasConfigured(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean("configured", false);
    }
    public static boolean needsRecovery(Context context) {
        return wasConfigured(context) || context.getSharedPreferences("bridge",Context.MODE_PRIVATE).getBoolean("stopPending",false);
    }
    private void stopPending(boolean value) { context.getSharedPreferences("bridge",Context.MODE_PRIVATE).edit().putBoolean("stopPending",value).commit(); }
    private void configured(boolean value) {
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putBoolean("configured", value).apply();
    }
    public void publishAsync(java.util.function.Consumer<String> onError) {
        if (!wasConfigured(context)) return;
        long revision = REVISION.incrementAndGet();
        UPDATES.execute(() -> {
            synchronized (IO_LOCK) {
                if (revision != REVISION.get() || !wasConfigured(context)) return;
                try {
                    requireRoot();
                    // Read the latest local state after obtaining the lock. Never replay an old UI snapshot.
                    command("settings put global " + KEY + " " + quote(config(new SettingsStore(context).load())));
                    if (windowHookEnabled(context) && windowHookSupported()) publishWindowProfiles();
                } catch (IOException | RuntimeException ex) {
                    health(UiText.get(R.string.ui_10) + ex.getMessage());
                    if(wasConfigured(context)) RecoveryJobs.schedule(context);
                    onError.accept(UiText.get(R.string.ui_11) + ex.getMessage());
                }
            }
        });
    }
    private String asset(String path) throws IOException {
        try (InputStream input = context.getAssets().open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int n; while ((n = input.read(bytes)) >= 0) output.write(bytes, 0, n);
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        }
    }
    public void connect(SettingsStore.State state) throws IOException {
        synchronized (IO_LOCK) {
            configured(true); // Desired state survives even the first failed connection.
            stopPending(false);
            try { connectLocked(state); health(UiText.get(R.string.ui_12)); RecoveryJobs.cancel(context); }
            catch (IOException | RuntimeException ex) {
                health(UiText.get(R.string.ui_13) + ex.getMessage());
                RecoveryJobs.schedule(context);
                throw ex;
            }
        }
    }
    public void reconnectAfterBoot() throws IOException {
        synchronized (IO_LOCK) {
            if(Thread.currentThread().isInterrupted())return;
            if(!wasConfigured(context)) {
                if(needsRecovery(context)) stopLocked();
                return;
            }
            try { connectLocked(new SettingsStore(context).load()); health(UiText.get(R.string.ui_14)); }
            catch (IOException | RuntimeException ex) { health(UiText.get(R.string.ui_15) + ex.getMessage()); throw ex; }
        }
    }
    private void connectLocked(SettingsStore.State state) throws IOException {
        requireRoot();
        String version = command(quote(INJECTOR) + " --version");
        if (!version.trim().equals("17.18.0")) throw new IOException(UiText.get(R.string.ui_16) + version);
        command("test ! -L " + BASE + " && mkdir -p " + BASE + " && test \"$(stat -c %u " + BASE + ")\" = 0 && chmod 700 " + BASE);
        // Stop only this project's prior worker. Never kill another Frida instance.
        restorePanelIcons();
        releaseWindowLocked();
        command(stopCommand(CHANNELS));
        command("settings put global org.openvoyah.controls.localization " + quote(new LocalizationSettings(context).exportJson()));
        upload("hooks/launcher-buttons.bundle.js", BASE + "/launcher-buttons.js");
        upload("hooks/supervise.sh", BASE + "/supervise.sh");
        upload("hooks/boot-start.sh", BASE + "/boot-start.sh");
        command("chmod 700 " + BASE + "/supervise.sh " + BASE + "/boot-start.sh"
            + " && settings put global " + KEY + " " + quote(config(state)));
        startLocalizationWorkers();
        command("settings delete global " + WINDOW_REQUEST);
        if (windowHookEnabled(context) && windowHookSupported()) {
            publishWindowProfiles();
            stageOptionalHook("window");
        } else {
            command("settings delete global " + WINDOW_PROFILES);
        }
        if (navigationHookEnabled(context)) stageOptionalHook("navigation");
        command("rm -f " + BASE + "/hook.log " + BASE + "/ready && touch " + BASE + "/enabled"
            + " && /system/bin/sh " + BASE + "/boot-start.sh");
        configured(true);
    }
    private void startLocalizationWorkers() throws IOException {
        String config = new LocalizationSettings(context).exportJson();
        command("settings put global org.openvoyah.controls.localization " + quote(config));
        if (!new LocalizationSettings(context).enabled()) return;
        upload("hooks/localization.bundle.js", BASE + "/localization.js");
        for (String channel : new String[]{"setting", "vehicle", "bluetooth"}) {
            String dir = BASE + "/locale-" + channel;
            command("mkdir -p " + dir + " && chmod 700 " + dir + " && cp " + BASE + "/localization.js " + dir + "/launcher-buttons.js && touch " + dir + "/enabled");
        }
    }
    public static boolean windowHookSupported() {
        int sdk = android.os.Build.VERSION.SDK_INT;
        return sdk == 28 || sdk == 30;
    }
    public static boolean windowHookEnabled(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean("windowHook", windowHookSupported());
    }
    public void setWindowHook(boolean enabled) throws IOException {
        if (enabled && !windowHookSupported()) throw new IOException(UiText.get(R.string.window_hook_requirement));
        setOptionalHook("window", enabled);
    }
    public static boolean navigationHookEnabled(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean("navigationHook", false);
    }
    public void setNavigationHook(boolean enabled) throws IOException { setOptionalHook("navigation", enabled); }

    private void setOptionalHook(String channel, boolean enabled) throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            boolean restored = true;
            if (enabled) {
                if (!wasConfigured(context)) throw new IOException(UiText.get(R.string.ui_19));
                if (channel.equals("window")) publishWindowProfiles();
                stageOptionalHook(channel);
                command("nohup /system/bin/sh " + BASE + "/supervise.sh " + channel + " >> " + BASE + "/" + channel
                    + "/worker.log 2>&1 </dev/null &");
            } else {
                if (channel.equals("window")) restored = releaseWindowLocked();
                command(stopCommand("/" + channel));
                if (channel.equals("window")) clearWindowSettings();
            }
            if (!context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putBoolean(channel + "Hook", enabled).commit()) {
                throw new IOException(UiText.get(R.string.ui_152));
            }
            if (!restored) throw new IOException(UiText.get(R.string.window_restore_unconfirmed));
        }
    }
    private void stageOptionalHook(String channel) throws IOException {
        String dir = BASE + "/" + channel;
        command("test -x " + BASE + "/supervise.sh && mkdir -p " + dir + " && chmod 700 " + dir);
        upload("hooks/" + channel + ".bundle.js", dir + "/launcher-buttons.js");
        String clearReadiness = channel.equals("window") ? "rm -f " + dir + "/ready " + dir + "/hook.log && " : "";
        command(clearReadiness + "touch " + dir + "/enabled");
    }
    private void upload(String name, String path) throws IOException {
        String encoded = asset(name);
        StringBuilder lines = new StringBuilder("base64 -d > " + quote(path + ".new") + " <<'OVC_ASSET'\n");
        // Canonical Telnet terminals truncate long lines. Each heredoc line stays below 1 KiB.
        for (int i = 0; i < encoded.length(); i += 768)
            lines.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        lines.append("OVC_ASSET\n");
        command(lines.toString());
        command("mv " + quote(path + ".new") + " " + quote(path));
    }
    private void publishWindowProfiles() throws IOException {
        String profiles = new AppWindowPreferences(context).agentConfig(true);
        String encoded = Base64.encodeToString(profiles.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        StringBuilder script = new StringBuilder("settings put global " + WINDOW_PROFILES + " \"$(base64 -d <<'OVC_WINDOWS'\n");
        for (int i = 0; i < encoded.length(); i += 768) {
            script.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        }
        command(script.append("OVC_WINDOWS\n)\"").toString());
    }

    public String applyWindowProfile(String component, boolean reset) throws IOException {
        String target = AppWindowProfile.Companion.key(component);
        if (target == null) throw new IllegalArgumentException("Invalid window target");
        synchronized (IO_LOCK) {
            if (!wasConfigured(context) || !windowHookEnabled(context) || !windowHookSupported()) {
                throw new IOException(UiText.get(R.string.window_hook_requirement));
            }
            requireRoot();
            if (!liveWindowReady("3")) throw new IOException(UiText.get(R.string.window_service_unavailable));
            publishWindowProfiles();
            return windowRequest(reset ? "reset" : "apply", target);
        }
    }

    private boolean liveWindowReady(String protocolPattern) throws IOException {
        String dir = BASE + "/window";
        return "ready".equals(command("if [ -f " + dir + "/enabled ] && grep -qs 'OVC_WINDOW_READY protocol=" + protocolPattern + " ' "
            + dir + "/ready " + dir + "/hook.log; then echo ready; fi"));
    }

    private String windowRequest(String operation, String component) throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        String json;
        try {
            json = new JSONObject().put("schema", 1).put("id", token).put("operation", operation)
                .put("user", new AppWindowPreferences(context).getUserId()).put("component", component)
                .put("expires", System.currentTimeMillis() + 6000).toString();
        } catch (JSONException ex) {
            throw new IOException("Could not encode the window request", ex);
        }
        command("settings put global " + WINDOW_REQUEST + " " + quote(json));
        String prefix = "OVC_WINDOW_RESULT " + token + " ";
        String log = BASE + "/window/hook.log";
        String reply = command("for i in 0 1 2 3 4 5; do r=$(grep -F " + quote(prefix) + " " + log
            + " 2>/dev/null | tail -n 1); if [ -n \"$r\" ]; then printf '%s\\n' \"$r\"; exit 0; fi; "
            + "[ \"$i\" = 5 ] || sleep 1; done; exit 1");
        int start = reply.indexOf(prefix);
        if (start < 0) throw new IOException(UiText.get(R.string.window_service_unavailable));
        return reply.substring(start + prefix.length());
    }

    private boolean releaseWindowLocked() {
        try {
            return !liveWindowReady("[23]") || "applied".equals(new JSONObject(windowRequest("release", "")).getString("status"));
        } catch (IOException | JSONException ex) { return false; }
    }

    private void clearWindowSettings() throws IOException {
        command("settings delete global " + WINDOW_REQUEST + "; settings delete global " + WINDOW_PROFILES);
    }

    public String status() throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            StringBuilder script = new StringBuilder("echo 'Loopback shell: root'; ");
            script.append("printf 'Injector: '; ").append(quote(INJECTOR)).append(" --version 2>/dev/null || echo 'unavailable'; ")
                .append("printf 'Android system process: '; pidof system_server || echo 'unavailable'; ");
            for (String suffix : CHANNELS) {
                String dir=BASE+suffix;
                String channel = switch (suffix) {
                    case "" -> "Launcher / panel / voice";
                    case "/window" -> "Window service";
                    case "/navigation" -> "Navigation feed";
                    default -> "Localization " + suffix;
                };
                if (suffix.isEmpty()) script.append("grep -E '^OVC_PANEL_(ICONS|SELECTION)_READY' " + dir + "/hook.log 2>/dev/null; ");
                script.append("printf '%s\\n' "+quote(channel)+"; ")
                    .append("if [ -f "+dir+"/enabled ]; then echo 'Desired: running'; else echo 'Desired: stopped'; fi; ")
                    .append("target=$(cat "+dir+"/target.pid 2>/dev/null); if [ -n \"$target\" ] && [ -d /proc/\"$target\" ]; then echo \"Attached target: $target\"; ")
                    .append("cat "+dir+"/ready 2>/dev/null || echo 'Waiting for hook readiness'; else echo 'Waiting for target'; fi; ")
                    .append("grep -E '^OVC_[A-Z_]+_ERROR' "+dir+"/hook.log 2>/dev/null | tail -n 4; ")
                    .append("tail -c 500 "+dir+"/worker.log 2>/dev/null; ");
            }
            return command(script+"true");
        }
    }
    public static String lastHealth(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("health", UiText.get(R.string.ui_18));
    }
    private void health(String value) {
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putString("health",value).apply();
    }
    public record VoiceReply(boolean accepted, String message) { }

    private String sendVoice(String id, JSONObject options) throws IOException {
        return sendVoiceRequest(id, options, 1).message();
    }

    public VoiceReply sendRecognizedVoice(String id, JSONObject options) throws IOException {
        return sendVoiceRequest(id, options, 2);
    }

    private VoiceReply sendVoiceRequest(String id, JSONObject options, int schema) throws IOException {
        synchronized (IO_LOCK) {
            if (!wasConfigured(context)) throw new IOException(UiText.get(R.string.ui_19));
            requireRoot();
            if (schema == 2 && !"ready".equals(command("if grep -qs 'OVC_VOICE_READY protocol=2' "
                    + BASE + "/ready " + BASE + "/hook.log; then echo ready; fi"))) {
                throw new IOException(UiText.get(R.string.voice_refresh_bridge));
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            String request;
            try { request = new JSONObject().put("schema",schema).put("id",token).put("command",id)
                .put("confirmed",true).put("expires",System.currentTimeMillis()+12000).put("options",options).toString(); }
            catch (JSONException ex) { throw new IOException(ex); }
            command("settings put global org.openvoyah.controls.voice " + quote(request));
            // No resend: an absent reply may mean that a physical action already happened.
            String reply = command("i=0; while [ $i -lt 10 ]; do r=$(grep 'OVC_VOICE_RESULT.*" + token + "' " + BASE + "/hook.log 2>/dev/null | tail -n 1); if [ -n \"$r\" ]; then printf '%s\\n' \"$r\"; exit 0; fi; echo .; sleep 1; i=$((i+1)); done; exit 1");
            try {
                JSONObject response = new JSONObject(reply.substring(reply.indexOf('{')));
                return new VoiceReply("sent".equals(response.getString("status")), response.getString("message"));
            }
            catch (RuntimeException | JSONException ex) { throw new IOException(UiText.get(R.string.ui_20),ex); }
        }
    }
    public String switchDriver(String driver) throws Exception {
        synchronized (IO_LOCK) {
            requireRoot();
            if (!wasConfigured(context)) throw new IOException("Connect Frida before switching OEM profiles");
            // Preflight Park and the OEM event API before changing the account.
            VoiceReply ready = sendRecognizedVoice("ProfileSyncPreflight", new JSONObject());
            if (!ready.accepted() || !ready.message().equals("OVC_PROFILE_SYNC_READY")) throw new IOException(ready.message());
            String result = OemProfiles.switchDriver(this::command, driver);
            Thread.sleep(2200);
            return result + ". " + sendVoice("ProfileSync", new JSONObject());
        }
    }
    public String restoreProfile() throws Exception {
        synchronized (IO_LOCK) {
            requireRoot();
            String result = OemProfiles.restore(this::command);
            if (wasConfigured(context)) { Thread.sleep(2200); result += ". " + sendVoice("ProfileSync", new JSONObject()); }
            return result;
        }
    }
    public String rebootTbox() throws IOException {
        synchronized (IO_LOCK) {
            VoiceReply parking = sendRecognizedVoice("ParkingPreflight", new JSONObject());
            if (!parking.accepted()) throw new IOException(parking.message());
            return command("dumpsys dls_tbox_service reboot");
        }
    }
    public String clusterRoot(String script) throws IOException {
        synchronized (IO_LOCK) { requireRoot(); return command("set -e\n" + script); }
    }
    public void publishClusterMedia(Media media) throws IOException {
        synchronized (IO_LOCK) {
            if (!wasConfigured(context)) return;
            requireRoot();
            try {
                String data = new JSONObject().put("title", media.title()).put("artist", media.artist())
                    .put("album", media.album()).put("playing", media.playing())
                    .put("expires", System.currentTimeMillis() + 5000).toString();
                String key = "org.openvoyah.controls.clusterMedia";
                command("grep -q OVC_CLUSTER_READY " + BASE + "/ready && settings put global " + key + " " + quote(data)
                    + " && { (sleep 5; if [ \"$(settings get global " + key + ")\" = " + quote(data)
                    + " ]; then settings delete global " + key + "; fi) </dev/null >/dev/null 2>&1 & }");
            } catch (JSONException ex) { throw new IOException("Could not encode media state", ex); }
        }
    }
    public void disconnect() throws IOException {
        synchronized (IO_LOCK) {
            configured(false);
            stopPending(true);
            RecoveryJobs.cancel(context);
            REVISION.incrementAndGet();
            try { stopLocked(); }
            catch(IOException | RuntimeException ex) {
                health(UiText.get(R.string.ui_21)+ex.getMessage());
                RecoveryJobs.schedule(context); throw ex;
            }
        }
    }
    private void stopLocked() throws IOException {
        requireRoot();
        boolean iconsRestored = restorePanelIcons();
        boolean windowsRestored = releaseWindowLocked();
        command(stopCommand(CHANNELS));
        clearWindowSettings();
        command("settings delete global " + KEY + "; settings delete global org.openvoyah.controls.voice; "
            + "settings delete global org.openvoyah.controls.localization; settings delete global org.openvoyah.controls.clusterMedia");
        stopPending(false);
        health(UiText.get(windowsRestored ? (iconsRestored ? R.string.ui_22 : R.string.panel_restore_unconfirmed)
            : R.string.window_restore_unconfirmed));
    }

    private boolean restorePanelIcons() throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        String disabled = "{\"schema\":1,\"enabled\":false,\"restoreIcons\":\"" + token + "\"}";
        String ready = BASE + "/ready";
        String log = BASE + "/hook.log";
        String response = command("if [ -f " + BASE + "/enabled ] && grep -qs 'OVC_PANEL_ICONS_READY' "
            + ready + " " + log + "; then settings put global " + KEY + " " + quote(disabled)
            + " || exit 1; for i in 0 1 2 3 4; do "
            + "if grep -qs " + quote("OVC_PANEL_ICONS_RESTORED " + token) + " " + log
            + "; then echo restored; exit 0; fi; [ \"$i\" = 4 ] || sleep 1; done; echo unconfirmed; "
            + "else echo inactive; fi");
        return !response.trim().equals("unconfirmed");
    }
    private String stopCommand(String... channels) {
        StringBuilder script = new StringBuilder();
        for (String suffix : channels) {
            String dir = BASE + suffix;
            script.append("rm -f " + dir + "/enabled; c=$(cat " + dir + "/child.pid 2>/dev/null); ")
                .append("case \"$c\" in ''|0|*[!0-9]*) ;; *) a=$(tr '\\000' ' ' < /proc/$c/cmdline 2>/dev/null); ")
                .append("case \"$a\" in *'" + dir + "/launcher-buttons.js'*) kill \"$c\" 2>/dev/null ;; esac ;; esac; ")
                .append("p=$(cat " + dir + "/worker.pid 2>/dev/null); ")
                .append("case \"$p\" in ''|0|*[!0-9]*) ;; *) a=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); ")
                .append("case \"$a\" in *" + quote(workerCommand(suffix)) + ") kill \"$p\" 2>/dev/null ;; esac ;; esac;\n");
        }
        script.append("sleep 3; b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null);\n");
        for (String suffix : channels) {
            String dir = BASE + suffix;
            script.append("p=$(cat " + dir + "/worker.pid 2>/dev/null); ")
                .append("case \"$p\" in ''|0|*[!0-9]*) ;; *) a=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); ")
                .append("case \"$a\" in *" + quote(workerCommand(suffix)) + ") echo 'Worker has not stopped'; exit 1 ;; esac ;; esac; ")
                .append("rmdir " + dir + "/worker.lock 2>/dev/null; ")
                .append("if [ -n \"$b\" ]; then rmdir " + dir + "/worker.lock-\"$b\" 2>/dev/null; fi;\n");
        }
        return script.append("true").toString();
    }
    private static String workerCommand(String suffix) {
        if (suffix.isEmpty()) return BASE + "/supervise.sh ";
        String argument = suffix.startsWith("/locale-") ? suffix.substring(8) : suffix.substring(1);
        return BASE + "/supervise.sh " + argument + " ";
    }
    private void requireRoot() throws IOException {
        String result = command("id -u");
        if (!result.trim().equals("0")) throw new IOException(UiText.get(R.string.ui_23));
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    private String command(String script) throws IOException {
        return transport.execute(script);
    }
}
