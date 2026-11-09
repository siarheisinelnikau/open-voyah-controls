package org.openvoyah.controls;

import android.content.Context;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.core.Action;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Local privileged transport, matching the existing installer's Telnet setup. */
final class FridaBridge {
    static final String KEY = "org.openvoyah.controls.panel";
    static final String[] SLOTS = {"navbar01", "navbar02", "navbar11", "navbar12", "navbar13", "navbar14", "navbar15"};
    static String[] labels() { return new String[]{UiText.get(R.string.ui_3), UiText.get(R.string.ui_4), UiText.get(R.string.ui_5), UiText.get(R.string.ui_6), UiText.get(R.string.ui_7), UiText.get(R.string.ui_8), UiText.get(R.string.ui_9)}; }
    private static final String BASE = "/data/local/tmp/open-voyah-controls";
    private static final String INJECTOR = "/data/local/tmp/open-voyah-controls-frida-17.18.0";
    private static final Object IO_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService UPDATES = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    private final Context context;
    private final int port;
    FridaBridge(Context context) { this(context, 23); }
    FridaBridge(Context context, int port) { this.context = context.getApplicationContext(); this.port = port; }
    static String config(SettingsStore.State state) {
        try {
            JSONObject actions = new JSONObject();
            for (String slot : SLOTS) {
                Action action = state.panelActions.get(slot);
                if (action != null) actions.put(slot, new JSONObject().put("kind", action.kind.name()).put("component", action.component));
            }
            return new JSONObject().put("schema", 1).put("enabled", state.panelEnabled).put("actions", actions).toString();
        } catch (JSONException ex) { throw new IllegalArgumentException(ex); }
    }
    static boolean wasConfigured(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean("configured", false);
    }
    static boolean needsRecovery(Context context) {
        return wasConfigured(context) || context.getSharedPreferences("bridge",Context.MODE_PRIVATE).getBoolean("stopPending",false);
    }
    private void stopPending(boolean value) { context.getSharedPreferences("bridge",Context.MODE_PRIVATE).edit().putBoolean("stopPending",value).commit(); }
    private void configured(boolean value) {
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putBoolean("configured", value).apply();
    }
    void publishAsync(java.util.function.Consumer<String> onError) {
        if (!wasConfigured(context)) return;
        long revision = REVISION.incrementAndGet();
        UPDATES.execute(() -> {
            synchronized (IO_LOCK) {
                if (revision != REVISION.get() || !wasConfigured(context)) return;
                try {
                    requireRoot();
                    // Read the latest local state after obtaining the lock. Never replay an old UI snapshot.
                    command("settings put global " + KEY + " " + quote(config(new SettingsStore(context).load())));
                } catch (IOException | RuntimeException ex) {
                    health(UiText.get(R.string.ui_10) + ex.getMessage());
                    if(wasConfigured(context)) BootHookService.schedule(context);
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
    void connect(SettingsStore.State state) throws IOException {
        synchronized (IO_LOCK) {
            configured(true); // Desired state survives even the first failed connection.
            stopPending(false);
            try { connectLocked(state); health(UiText.get(R.string.ui_12)); BootHookService.cancel(context); }
            catch (IOException | RuntimeException ex) {
                health(UiText.get(R.string.ui_13) + ex.getMessage());
                BootHookService.schedule(context);
                throw ex;
            }
        }
    }
    void reconnectAfterBoot() throws IOException {
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
        // Stop only this project's prior worker. Never kill another Frida instance.
        command(stopCommand());
        command("mkdir -p " + BASE + " && chmod 700 " + BASE);
        command("settings put global org.openvoyah.controls.localization " + quote(new LocalizationSettings(context).exportJson()));
        upload("hooks/launcher-buttons.bundle.js", BASE + "/launcher-buttons.js");
        upload("hooks/supervise.sh", BASE + "/supervise.sh");
        command("chmod 700 " + BASE + "/supervise.sh && settings put global " + KEY + " " + quote(config(state))
            + " && rm -f " + BASE + "/hook.log && touch " + BASE + "/enabled");
        command("nohup /system/bin/sh " + BASE + "/supervise.sh > " + BASE + "/worker.log 2>&1 < /dev/null &");
        command("i=0; while [ $i -lt 5 ]; do if grep -qE 'OVC_READY|OVC_VOICE_READY' " + BASE + "/hook.log 2>/dev/null; then exit 0; fi; sleep 1; i=$((i+1)); done; tail -c 2500 " + BASE + "/hook.log 2>/dev/null; exit 1");
        startLocalizationWorkers();
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
            command("nohup /system/bin/sh " + BASE + "/supervise.sh " + channel + " > " + dir + "/worker.log 2>&1 < /dev/null &");
        }
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
    String status() throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            StringBuilder script=new StringBuilder();
            for(String suffix:new String[]{"","/locale-setting","/locale-vehicle","/locale-bluetooth"}) {
                String dir=BASE+suffix;
                script.append("printf '%s\\n' "+quote(suffix.isEmpty()?"Launcher / panel / voice":"Localization "+suffix)+"; ")
                    .append("if [ -f "+dir+"/enabled ]; then echo 'Desired: running'; else echo 'Desired: stopped'; fi; ")
                    .append("tail -c 1200 "+dir+"/hook.log 2>/dev/null; tail -c 300 "+dir+"/worker.log 2>/dev/null; ");
            }
            return command(script+"true");
        }
    }
    static String lastHealth(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("health", UiText.get(R.string.ui_18));
    }
    private void health(String value) {
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putString("health",value).apply();
    }
    String sendVoice(String id, JSONObject options) throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            if (!wasConfigured(context)) throw new IOException(UiText.get(R.string.ui_19));
            String token = UUID.randomUUID().toString().replace("-", "");
            String request;
            try { request = new JSONObject().put("schema",1).put("id",token).put("command",id)
                .put("confirmed",true).put("expires",System.currentTimeMillis()+12000).put("options",options).toString(); }
            catch (JSONException ex) { throw new IOException(ex); }
            command("settings put global org.openvoyah.controls.voice " + quote(request));
            // No resend: an absent reply may mean that a physical action already happened.
            String reply = command("i=0; while [ $i -lt 10 ]; do r=$(grep 'OVC_VOICE_RESULT.*" + token + "' " + BASE + "/hook.log 2>/dev/null | tail -n 1); if [ -n \"$r\" ]; then printf '%s\\n' \"$r\"; exit 0; fi; echo .; sleep 1; i=$((i+1)); done; exit 1");
            try { return new JSONObject(reply.substring(reply.indexOf('{'))).getString("message"); }
            catch (RuntimeException | JSONException ex) { throw new IOException(UiText.get(R.string.ui_20),ex); }
        }
    }
    String switchDriver(String driver) throws Exception {
        synchronized (IO_LOCK) {
            requireRoot();
            if (!wasConfigured(context)) throw new IOException("Connect Frida before switching OEM profiles");
            // Preflight the event API before changing the account.
            String ready = sendVoice("ProfileSyncPreflight", new JSONObject());
            if (!ready.equals("OVC_PROFILE_SYNC_READY")) throw new IOException(ready);
            String result = OemProfiles.switchDriver(this::command, driver);
            Thread.sleep(2200);
            return result + ". " + sendVoice("ProfileSync", new JSONObject());
        }
    }
    String restoreProfile() throws Exception {
        synchronized (IO_LOCK) {
            requireRoot();
            String result = OemProfiles.restore(this::command);
            if (wasConfigured(context)) { Thread.sleep(2200); result += ". " + sendVoice("ProfileSync", new JSONObject()); }
            return result;
        }
    }
    String rebootTbox() throws IOException {
        synchronized (IO_LOCK) { requireRoot(); return command("dumpsys dls_tbox_service reboot"); }
    }
    String clusterRoot(String script) throws IOException {
        synchronized (IO_LOCK) { requireRoot(); return command("set -e\n" + script); }
    }
    void disconnect() throws IOException {
        synchronized (IO_LOCK) {
            configured(false);
            stopPending(true);
            BootHookService.cancel(context);
            REVISION.incrementAndGet();
            try { stopLocked(); }
            catch(IOException | RuntimeException ex) {
                health(UiText.get(R.string.ui_21)+ex.getMessage());
                BootHookService.schedule(context); throw ex;
            }
        }
    }
    private void stopLocked() throws IOException {
        requireRoot(); command(stopCommand());
        command("settings delete global " + KEY + "; settings delete global org.openvoyah.controls.voice; settings delete global org.openvoyah.controls.localization");
        stopPending(false); health(UiText.get(R.string.ui_22));
    }
    private String stopCommand() {
        StringBuilder script = new StringBuilder();
        for (String suffix : new String[]{"", "/locale-setting", "/locale-vehicle", "/locale-bluetooth"}) {
            String dir = BASE + suffix;
            script.append("rm -f " + dir + "/enabled; c=$(cat " + dir + "/child.pid 2>/dev/null); ")
                .append("case \"$c\" in ''|*[!0-9]*) ;; *) a=$(tr '\\000' ' ' < /proc/$c/cmdline 2>/dev/null); ")
                .append("case \"$a\" in *'" + dir + "/launcher-buttons.js'*) kill \"$c\" 2>/dev/null ;; esac ;; esac; ")
                .append("p=$(cat " + dir + "/worker.pid 2>/dev/null); ")
                .append("case \"$p\" in ''|*[!0-9]*) ;; *) a=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); ")
                .append("case \"$a\" in *'" + BASE + "/supervise.sh'*) kill \"$p\" 2>/dev/null ;; esac ;; esac; ");
        }
        script.append("sleep 3; ");
        for (String suffix : new String[]{"", "/locale-setting", "/locale-vehicle", "/locale-bluetooth"})
            script.append("rmdir " + BASE + suffix + "/worker.lock 2>/dev/null; ");
        return script.append("true").toString();
    }
    private void requireRoot() throws IOException {
        String result = command("id -u");
        if (!result.trim().equals("0")) throw new IOException(UiText.get(R.string.ui_23));
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    private String command(String script) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException(UiText.get(R.string.ui_24));
        String marker = "OVC_" + UUID.randomUUID().toString().replace("-", "");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); socket.setSoTimeout(8000);
            InputStream input = socket.getInputStream();
            // Token literals are split so terminal echo cannot masquerade as command completion.
            String prefix = marker.substring(0, 12), suffix = marker.substring(12);
            String handshake = "stty -echo 2>/dev/null; printf '\\n%s%s_START\\n' '" + prefix + "' '" + suffix + "'\n";
            String payload = "( " + script + " ); ovc_rc=$?; printf '\\n%s%s_END:%s\\n' '" + prefix + "' '" + suffix + "' \"$ovc_rc\"\n";
            socket.getOutputStream().write(handshake.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            boolean sent = false;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            long deadline = android.os.SystemClock.uptimeMillis() + 12000;
            while (buffer.size() < 131072 && android.os.SystemClock.uptimeMillis() < deadline) {
                int value = input.read(); if (value < 0) break;
                // Refuse Telnet options; no terminal emulation or remote destination is needed.
                if (value == 255) {
                    int verb = input.read();
                    if (verb == 255) buffer.write(255);
                    else if (verb >= 251 && verb <= 254) {
                        int option = input.read(); if (option < 0) throw new IOException("Telnet negotiation ended");
                        if (verb == 251 || verb == 253) { socket.getOutputStream().write(new byte[]{(byte)255, (byte)(verb == 251 ? 254 : 252), (byte)option}); socket.getOutputStream().flush(); }
                    }
                    continue;
                }
                buffer.write(value);
                if (value != '\n') continue;
                String text = buffer.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
                int start = text.indexOf(marker + "_START\n"), end = text.indexOf(marker + "_END:");
                if (start >= 0 && !sent) {
                    // Wait until echo is actually disabled before a large heredoc.
                    // Putting stty in the same compound command is too late: the shell
                    // reads the entire heredoc before executing that command.
                    socket.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush(); sent = true;
                }
                if (start >= 0 && end > start) {
                    int lineEnd = text.indexOf('\n', end);
                    if (lineEnd < 0) continue;
                    String output = text.substring(start + marker.length() + 7, end).trim();
                    String rc = text.substring(end + marker.length() + 5, lineEnd).trim();
                    if (!rc.equals("0")) throw new IOException("Shell: " + rc + ". " + output);
                    return output;
                }
            }
            throw new IOException(UiText.get(R.string.ui_25));
        }
    }
}
