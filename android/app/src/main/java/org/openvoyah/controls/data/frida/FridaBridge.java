package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.BuildConfig;
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
    private static final String FIXTURE_BASE = "/data/local/tmp/open-voyah-controls";
    private static final String[] CHANNELS = {"", "/locale-setting", "/locale-vehicle", "/locale-bluetooth", "/window", "/navigation"};
    private static final String FIXTURE_INJECTOR = "/data/local/tmp/open-voyah-controls-frida-17.18.0";
    private static final Object IO_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService UPDATES = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    private final Context context;
    private final FridaTransport fixture;
    private final ProductionRootAccess production;
    private final String base;

    public FridaBridge(Context context) {
        this(context, BuildConfig.OVC_EMULATOR_FIXTURE && FridaTransport.canUseFixture(context)
            ? Integer.valueOf(23) : null);
    }
    public FridaBridge(Context context, int port) {
        this(context, Integer.valueOf(port));
    }
    private FridaBridge(Context context, Integer fixturePort) {
        this.context = context.getApplicationContext();
        fixture = fixturePort == null ? null : FridaTransport.emulatorFixture(this.context, fixturePort,
            () -> UiText.get(R.string.ui_24), () -> UiText.get(R.string.ui_25));
        production = fixture == null ? new ProductionRootAccess(this.context) : null;
        base = production == null ? FIXTURE_BASE : ProductionRootAccess.BASE;
    }

    private String injectorPath() { return production == null ? FIXTURE_INJECTOR : production.injectorPath(); }
    private String fixtureEnvironment() { return fixture == null ? "" : "OVC_EMULATOR_FIXTURE=1 "; }
    private String activationGuard() { return production == null ? "" : ProductionRootAccess.ACTIVATION_GUARD; }
    private boolean packageReviewed(String name) { return production == null || production.packageReviewed(name); }
    boolean windowReviewed() {
        try {
            return production == null || production.packageReviewed(ReviewedFirmware.FRAMEWORK)
                && production.operationReviewed(ReviewedFirmware.WINDOW_SERVICE);
        } catch (Exception unreviewed) { return false; }
    }
    boolean passengerWindowReviewed() {
        return production == null || windowReviewed() && production.operationReviewed(ReviewedFirmware.PASSENGER_WINDOW_DPI);
    }
    private boolean navigationReviewed() { return packageReviewed(ReviewedFirmware.CLUSTER); }
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
    private boolean knownAuthorization() {
        return production == null || context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .getBoolean("productionAuthorized", false);
    }
    boolean runtimeConfigured() { return wasConfigured(context) && knownAuthorization(); }
    private void stopPending(boolean value) { context.getSharedPreferences("bridge",Context.MODE_PRIVATE).edit().putBoolean("stopPending",value).commit(); }
    private void configured(boolean value) {
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putBoolean("configured", value).apply();
    }
    public void publishAsync(java.util.function.Consumer<String> onError) {
        if (!runtimeConfigured()) return;
        long revision = REVISION.incrementAndGet();
        UPDATES.execute(() -> {
            synchronized (IO_LOCK) {
                if (revision != REVISION.get() || !runtimeConfigured()) return;
                try {
                    requireRoot();
                    // Read the latest local state after obtaining the lock. Never replay an old UI snapshot.
                    command(activationGuard() + "settings put global " + KEY + " " + quote(config(new SettingsStore(context).load())));
                    if (windowHookEnabled(context) && windowHookSupported() && windowReviewed()) publishWindowProfiles();
                } catch (IOException | RuntimeException ex) {
                    health(UiText.get(R.string.ui_10) + ex.getMessage());
                    if (runtimeConfigured() && !(ex instanceof ProductionRootAccess.Refused)) RecoveryJobs.schedule(context);
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
            try {
                prepareActivation();
                if (production != null && !context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                        .edit().putBoolean("productionAuthorized", true).commit()) {
                    throw new IOException(UiText.get(R.string.ui_152));
                }
                configured(true); // Persist desired state only after production verification.
                stopPending(false);
                connectLocked(state);
                health(UiText.get(R.string.ui_12));
                RecoveryJobs.cancel(context);
            }
            catch (ProductionRootAccess.Refused ex) {
                health(ex.getMessage());
                RecoveryJobs.cancel(context);
                throw ex;
            }
            catch (IOException | RuntimeException ex) {
                health(UiText.get(R.string.ui_13) + ex.getMessage());
                if (runtimeConfigured()) RecoveryJobs.schedule(context);
                throw ex;
            }
        }
    }
    public void reconnectAfterBoot() throws IOException {
        synchronized (IO_LOCK) {
            if(Thread.currentThread().isInterrupted())return;
            if (!knownAuthorization()) {
                health(UiText.get(R.string.production_connect_required));
                return;
            }
            try {
                if (!runtimeConfigured()) {
                    if (needsRecovery(context)) stopLocked();
                    return;
                }
                prepareActivation();
                SettingsStore.State state = new SettingsStore(context).load();
                if (production == null || !resumeVerifiedDeployment(state)) connectLocked(state);
                health(UiText.get(R.string.ui_14));
            }
            catch (ProductionRootAccess.Refused ex) { health(ex.getMessage()); }
            catch (IOException | RuntimeException ex) { health(UiText.get(R.string.ui_15) + ex.getMessage()); throw ex; }
        }
    }

    private void prepareActivation() throws IOException {
        if (production == null) return;
        production.refresh();
        production.requireActivation();
    }

    private boolean resumeVerifiedDeployment(SettingsStore.State state) throws IOException {
        String entry = quote(base + "/boot-start.sh");
        String digest = assetDigest("hooks/boot-start.sh");
        String verified = command(activationGuard() + "if [ -f " + base + "/enabled ] && [ ! -L " + entry
            + " ] && [ \"$(sha256sum " + entry + " | cut -d ' ' -f 1)\" = '" + digest + "' ]; then "
            + "/system/bin/sh " + entry + " --verify 2>/dev/null || true; fi");
        if (!"OVC_BOOT_VERIFIED".equals(verified)) return false;
        command(activationGuard() + "settings put global " + KEY + " " + quote(config(state)));
        if (windowHookEnabled(context) && windowHookSupported() && windowReviewed()) publishWindowProfiles();
        command(activationGuard() + "/system/bin/sh " + entry);
        return true;
    }

    private void connectLocked(SettingsStore.State state) throws IOException {
        requireRoot();
        String version = checkedInjectorVersion();
        String expected = production == null ? "17.18.0" : production.injectorVersion();
        if (!version.equals(expected)) throw new IOException(UiText.get(R.string.ui_16) + version);
        command("test ! -L " + base + " && mkdir -p " + base + " && test \"$(stat -c %u " + base + ")\" = 0 && chmod 700 " + base);
        // Stop only this project's prior worker. Never kill another Frida instance.
        boolean panelRestored = restorePanelIcons();
        boolean windowRestored = releaseWindowLocked();
        if (!panelRestored || !windowRestored) recordVisualRecovery();
        command(stopCommand(CHANNELS));
        if (production != null && (!panelRestored || !windowRestored)) {
            throw new ProductionRootAccess.Refused(UiText.get(R.string.production_recovery_blocked));
        }
        command(activationGuard() + "settings put global org.openvoyah.controls.localization "
            + quote(new LocalizationSettings(context).exportJson()));
        upload("hooks/launcher-buttons.bundle.js", base + "/launcher-buttons.js");
        upload("hooks/supervise.sh", base + "/supervise.sh");
        upload("hooks/boot-start.sh", base + "/boot-start.sh");
        command(activationGuard() + "chmod 700 " + base + "/supervise.sh " + base + "/boot-start.sh"
            + " && settings put global " + KEY + " " + quote(config(state)));
        Set<String> channels = new LinkedHashSet<>();
        stageLocalizationWorkers(channels);
        command("settings delete global " + WINDOW_REQUEST);
        if (windowHookEnabled(context) && windowHookSupported() && windowReviewed()) {
            publishWindowProfiles();
            stageOptionalHook("window");
            channels.add("window");
        } else {
            command("settings delete global " + WINDOW_PROFILES);
        }
        if (navigationHookEnabled(context) && navigationReviewed()) {
            stageOptionalHook("navigation");
            channels.add("navigation");
        }
        sealDeployment(channels);
        for (String channel : channels) enableChannel(channel);
        command(activationGuard() + "rm -f " + base + "/hook.log " + base + "/ready && touch " + base + "/enabled"
            + " && " + fixtureEnvironment() + "/system/bin/sh " + base + "/boot-start.sh");
        configured(true);
    }

    private String checkedInjectorVersion() throws IOException {
        String guard = production == null ? "" : production.injectorGuard();
        return command(guard + quote(injectorPath()) + " --version").trim();
    }

    private void stageLocalizationWorkers(Set<String> channels) throws IOException {
        if (!new LocalizationSettings(context).enabled()) return;
        for (var entry : ReviewedFirmware.INSTANCE.getLocalizationTargets().entrySet()) {
            if (!packageReviewed(entry.getValue())) continue;
            String channel = "locale-" + entry.getKey();
            prepareChannelDirectory(channel);
            upload("hooks/localization.bundle.js", base + "/" + channel + "/launcher-buttons.js");
            channels.add(channel);
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
            if (!knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
            requireRoot();
            boolean restored = true;
            if (enabled) {
                if (!runtimeConfigured()) throw new IOException(UiText.get(R.string.ui_19));
                if (channel.equals("window") ? !windowReviewed() : !navigationReviewed()) throw unreviewedOperation();
                if (production != null) production.requireActivation();
                if (channel.equals("window")) publishWindowProfiles();
                stageOptionalHook(channel);
                if (production != null) {
                    Set<String> active = enabledChannels();
                    active.add(channel);
                    sealDeployment(active);
                }
                enableChannel(channel);
                command(activationGuard() + fixtureEnvironment() + "nohup /system/bin/sh " + base + "/supervise.sh "
                    + channel + " >/dev/null 2>&1 </dev/null &");
            } else {
                if (channel.equals("window")) restored = releaseWindowLocked();
                if (!restored) recordVisualRecovery();
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
        String dir = base + "/" + channel;
        prepareChannelDirectory(channel);
        upload("hooks/" + channel + ".bundle.js", dir + "/launcher-buttons.js");
        if (channel.equals("window")) command("rm -f " + dir + "/ready " + dir + "/hook.log");
    }

    private void prepareChannelDirectory(String channel) throws IOException {
        String dir = base + "/" + channel;
        command(activationGuard() + "test ! -L " + dir + " && mkdir -p " + dir
            + " && test \"$(stat -c %u " + dir + ")\" = 0 && chmod 700 " + dir);
    }

    private void enableChannel(String channel) throws IOException {
        String path = base + "/" + channel + "/enabled";
        command(activationGuard() + "test ! -L " + path + " && touch " + path);
    }

    private Set<String> enabledChannels() throws IOException {
        StringBuilder script = new StringBuilder();
        for (String suffix : CHANNELS) {
            if (!suffix.isEmpty()) script.append("if test -f ").append(base).append(suffix)
                .append("/enabled; then echo ").append(suffix.substring(1)).append("; fi\n");
        }
        var reported = java.util.Arrays.asList(command(script.toString()).split("\\n"));
        Set<String> result = new LinkedHashSet<>();
        for (String suffix : CHANNELS) {
            if (!suffix.isEmpty() && reported.contains(suffix.substring(1))) {
                result.add(suffix.substring(1));
            }
        }
        return result;
    }

    private void sealDeployment(Set<String> channels) throws IOException {
        if (production == null) return;
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("boot-start.sh", assetDigest("hooks/boot-start.sh"));
        artifacts.put("supervise.sh", assetDigest("hooks/supervise.sh"));
        artifacts.put("launcher-buttons.js", assetDigest("hooks/launcher-buttons.bundle.js"));
        for (String channel : channels) {
            if (channel.equals("window") && !windowReviewed() || channel.equals("navigation") && !navigationReviewed()
                || channel.startsWith("locale-") && !packageReviewed(ReviewedFirmware.INSTANCE.getLocalizationTargets()
                    .get(channel.substring(7)))) throw unreviewedOperation();
            String script = channel.startsWith("locale-") ? "localization" : channel;
            artifacts.put(channel + "/launcher-buttons.js", assetDigest("hooks/" + script + ".bundle.js"));
        }
        production.sealDeployment(artifacts);
    }

    private String assetDigest(String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            return ReviewedFirmware.INSTANCE.digest(input);
        }
    }
    private void upload(String name, String path) throws IOException {
        String encoded = asset(name);
        StringBuilder lines = new StringBuilder(activationGuard() + "test ! -L " + quote(path + ".new")
            + " && base64 -d > " + quote(path + ".new") + " <<'OVC_ASSET'\n");
        // Emulator terminals may truncate long lines. Keep each heredoc line below 1 KiB.
        for (int i = 0; i < encoded.length(); i += 768)
            lines.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        lines.append("OVC_ASSET\n");
        command(lines.toString());
        command(activationGuard() + "test ! -L " + quote(path) + " && chmod 600 " + quote(path + ".new")
            + " && mv " + quote(path + ".new") + " " + quote(path));
    }
    private void publishWindowProfiles() throws IOException {
        String profiles = new AppWindowPreferences(context).agentConfig(true, passengerWindowReviewed(),
            production != null ? Integer.valueOf(1) : null);
        String encoded = Base64.encodeToString(profiles.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        StringBuilder script = new StringBuilder(activationGuard() + "settings put global " + WINDOW_PROFILES
            + " \"$(base64 -d <<'OVC_WINDOWS'\n");
        for (int i = 0; i < encoded.length(); i += 768) {
            script.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        }
        command(script.append("OVC_WINDOWS\n)\"").toString());
    }

    public String applyWindowProfile(String component, boolean reset) throws IOException {
        String target = AppWindowProfile.Companion.key(component);
        if (target == null) throw new IllegalArgumentException("Invalid window target");
        synchronized (IO_LOCK) {
            if (!runtimeConfigured() || !windowHookEnabled(context) || !windowHookSupported() || !windowReviewed()) {
                throw new IOException(UiText.get(R.string.window_hook_requirement));
            }
            requireRoot();
            if (!liveWindowReady("3")) throw new IOException(UiText.get(R.string.window_service_unavailable));
            publishWindowProfiles();
            return windowRequest(reset ? "reset" : "apply", target);
        }
    }

    public boolean syncWindowProfiles() throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured() || !windowHookEnabled(context) || !windowHookSupported() || !windowReviewed()
                || !channelReady("/window", "^OVC_WINDOW_READY protocol=3 .*displayDpi=1( |$)")) return false;
            publishWindowProfiles();
            return true;
        }
    }

    private boolean liveWindowReady(String protocolPattern) throws IOException {
        return channelReady("/window", "^OVC_WINDOW_READY protocol=" + protocolPattern + " ");
    }

    private boolean channelReady(String suffix, String marker) throws IOException {
        String dir = base + suffix;
        String probe = production == null ? "" : workerIdentityScript();
        String worker = production == null ? "" : " && ovc_worker_live " + dir + " && ovc_child_live " + dir
            + " && ovc_ready_fresh " + dir;
        String evidence = dir + "/ready" + (production == null ? " " + dir + "/hook.log" : "");
        return "ready".equals(command(probe + "if [ -f " + dir + "/enabled ]" + worker
            + " && grep -Eqs " + quote(marker) + " " + evidence + "; then echo ready; fi"));
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
        command((operation.equals("release") ? "" : activationGuard()) + "settings put global " + WINDOW_REQUEST + " " + quote(json));
        String prefix = "OVC_WINDOW_RESULT " + token + " ";
        String log = base + "/window/hook.log";
        String reply = command("for i in 0 1 2 3 4 5; do r=$(grep -F " + quote(prefix) + " " + log
            + " 2>/dev/null | tail -n 1); if [ -n \"$r\" ]; then printf '%s\\n' \"$r\"; exit 0; fi; "
            + "[ \"$i\" = 5 ] || sleep 1; done; exit 1");
        int start = reply.indexOf(prefix);
        if (start < 0) throw new IOException(UiText.get(R.string.window_service_unavailable));
        return reply.substring(start + prefix.length());
    }

    private boolean releaseWindowLocked() {
        try {
            if (!liveWindowReady("[23]")) return !channelMayHaveMutated("/window");
            return "applied".equals(new JSONObject(windowRequest("release", "")).getString("status"));
        } catch (IOException | JSONException ex) { return false; }
    }

    private void clearWindowSettings() throws IOException {
        command("settings delete global " + WINDOW_REQUEST + "; settings delete global " + WINDOW_PROFILES);
    }

    public String status() throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            String version = checkedInjectorVersion();
            StringBuilder script = new StringBuilder(production == null ? "echo 'Emulator root fixture'\n" : workerIdentityScript() + "echo 'Authorized local root manager'\n");
            script.append("echo ").append(quote("Injector: " + version)).append("\n")
                .append("printf 'Android system process: '; pidof system_server || echo 'unavailable'\n");
            for (String suffix : CHANNELS) {
                String dir=base+suffix;
                String channel = switch (suffix) {
                    case "" -> "Launcher / panel / voice";
                    case "/window" -> "Window service";
                    case "/navigation" -> "Navigation feed";
                    default -> "Localization " + suffix;
                };
                String live = production == null ? "" : " && ovc_worker_live " + dir + " && ovc_child_live " + dir
                    + " && ovc_ready_fresh " + dir;
                script.append("printf '%s\\n' "+quote(channel)+"\n")
                    .append("if [ -f "+dir+"/enabled ]; then echo 'Desired: running'; else echo 'Desired: stopped'; fi\n")
                    .append("target=$(cat "+dir+"/target.pid 2>/dev/null); if [ -n \"$target\" ] && [ -d /proc/\"$target\" ]" + live + "; then echo \"Attached target: $target\"; ")
                    .append("cat "+dir+"/ready 2>/dev/null || echo 'Waiting for hook readiness'; else echo 'Waiting for target'; fi\n");
                if (suffix.isEmpty()) script.append("grep -E '^OVC_(STEERING_UNSUPPORTED|STEERING_ERROR)' " + dir + "/hook.log 2>/dev/null | tail -n 2\n");
                script.append("grep -E '^OVC_[A-Z_]+_ERROR' "+dir+"/hook.log 2>/dev/null | tail -n 4\n")
                    .append("tail -c 500 "+dir+"/worker.log 2>/dev/null\n");
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
    public record VoiceReply(String status, String message) {
        public boolean accepted() { return status.equals("accepted") || status.equals("verified"); }
    }

    private String sendVoice(String id, JSONObject options) throws IOException {
        return sendRecognizedVoice(id, options).message();
    }

    public VoiceReply sendRecognizedVoice(String id, JSONObject options) throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured()) throw new IOException(UiText.get(R.string.ui_19));
            requireRoot();
            if (production != null) production.requireActivation();
            if (!channelReady("", "^OVC_VOICE_READY protocol=3( |$)")) {
                throw new IOException(UiText.get(R.string.voice_refresh_bridge));
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            String request;
            try { request = new JSONObject().put("schema",3).put("id",token).put("command",id)
                .put("confirmed",true).put("expires",SystemClock.elapsedRealtime()+12000).put("options",options).toString(); }
            catch (JSONException ex) { throw new IOException(ex); }
            try {
                command(activationGuard() + "settings put global org.openvoyah.controls.voice " + quote(request));
                // A lost response can be ambiguous even when publishing failed locally. Do not resend.
                String reply = command("i=0; while [ $i -lt 10 ]; do r=$(grep 'OVC_VOICE_RESULT.*" + token + "' "
                    + base + "/hook.log 2>/dev/null | tail -n 1); if [ -n \"$r\" ]; then printf '%s\\n' \"$r\"; exit 0; fi; "
                    + "sleep 1; i=$((i+1)); done; exit 1");
                return parseVoiceReply(reply, token);
            }
            catch (IOException | RuntimeException ex) { return unknownVoiceOutcome(); }
        }
    }

    static VoiceReply parseVoiceReply(String reply, String token) {
        final String marker = "OVC_VOICE_RESULT ";
        if (!reply.startsWith(marker)) return unknownVoiceOutcome();
        try {
            JSONObject response = new JSONObject(reply.substring(marker.length()));
            String status = response.getString("status");
            String message = response.getString("message");
            if (!token.equals(response.getString("id")) || message.length() > 512) return unknownVoiceOutcome();
            return switch (status) {
                case "accepted", "verified", "partial", "unknown", "blocked" -> new VoiceReply(status, message);
                default -> unknownVoiceOutcome();
            };
        } catch (JSONException ex) { return unknownVoiceOutcome(); }
    }

    private static VoiceReply unknownVoiceOutcome() {
        return new VoiceReply("unknown", UiText.get(R.string.production_voice_outcome_unknown));
    }

    private static ProductionRootAccess.Refused unreviewedOperation() {
        return new ProductionRootAccess.Refused(UiText.get(R.string.production_operation_unreviewed));
    }

    private void requireReviewedOperation(String operation) throws IOException {
        if (production == null || !production.operationReviewed(operation)) throw unreviewedOperation();
        production.requireActivation();
    }

    public String switchDriver(String driver) throws Exception {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.OEM_PROFILES);
            if (!packageReviewed(ReviewedFirmware.INSTANCE.getLocalizationTargets().get("vehicle"))) throw unreviewedOperation();
            requireRoot();
            if (!runtimeConfigured()) throw new IOException("Connect Frida before switching OEM profiles");
            // Preflight Park and the OEM event API before changing the account.
            VoiceReply ready = sendRecognizedVoice("ProfileSyncPreflight", new JSONObject());
            if (!ready.accepted() || !ready.message().equals("OVC_PROFILE_SYNC_READY")) throw new IOException(ready.message());
            String result = OemProfiles.switchDriver(script -> command(activationGuard() + script), driver, base);
            Thread.sleep(2200);
            return result + ". " + sendVoice("ProfileSync", new JSONObject());
        }
    }
    public String restoreProfile() throws Exception {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.OEM_PROFILES);
            if (!packageReviewed(ReviewedFirmware.INSTANCE.getLocalizationTargets().get("vehicle"))) throw unreviewedOperation();
            requireRoot();
            if (production != null) {
                VoiceReply ready = sendRecognizedVoice("ProfileSyncPreflight", new JSONObject());
                if (!ready.accepted() || !ready.message().equals("OVC_PROFILE_SYNC_READY")) throw new IOException(ready.message());
            }
            String result = OemProfiles.restore(script -> command(activationGuard() + script), base);
            if (runtimeConfigured()) { Thread.sleep(2200); result += ". " + sendVoice("ProfileSync", new JSONObject()); }
            return result;
        }
    }
    public String rebootTbox() throws IOException {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.TBOX_REBOOT);
            VoiceReply parking = sendRecognizedVoice("ParkingPreflight", new JSONObject());
            if (!parking.accepted()) throw new IOException(parking.message());
            return command(activationGuard() + "dumpsys dls_tbox_service reboot");
        }
    }
    public String clusterRoot(String script) throws IOException {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.CLUSTER_WRITE);
            if (!packageReviewed(ReviewedFirmware.CLUSTER)) throw unreviewedOperation();
            return command(activationGuard() + "set -e\n" + script);
        }
    }
    public void publishClusterMedia(Media media) throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured()) return;
            if (!navigationReviewed()) throw unreviewedOperation();
            requireRoot();
            try {
                String data = new JSONObject().put("title", media.title()).put("artist", media.artist())
                    .put("album", media.album()).put("playing", media.playing())
                    .put("expires", System.currentTimeMillis() + 5000).toString();
                String key = "org.openvoyah.controls.clusterMedia";
                command(activationGuard() + "grep -q OVC_CLUSTER_READY " + base + "/ready && settings put global " + key + " " + quote(data)
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
                if (!(ex instanceof ProductionRootAccess.Refused)) RecoveryJobs.schedule(context);
                throw ex;
            }
        }
    }
    private void stopLocked() throws IOException {
        if (!knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
        requireRoot();
        boolean iconsRestored = restorePanelIcons();
        boolean windowsRestored = releaseWindowLocked();
        if (!iconsRestored || !windowsRestored) recordVisualRecovery();
        command(stopCommand(CHANNELS));
        clearWindowSettings();
        command("settings delete global " + KEY + "; settings delete global org.openvoyah.controls.voice; "
            + "settings delete global org.openvoyah.controls.localization; settings delete global org.openvoyah.controls.clusterMedia");
        stopPending(false);
        health(UiText.get(windowsRestored ? (iconsRestored ? R.string.ui_22 : R.string.panel_restore_unconfirmed)
            : R.string.window_restore_unconfirmed));
    }

    private boolean restorePanelIcons() throws IOException {
        if (!channelReady("", "^OVC_PANEL_ICONS_READY( |$)")) return !channelMayHaveMutated("");
        String token = UUID.randomUUID().toString().replace("-", "");
        String disabled = "{\"schema\":1,\"enabled\":false,\"restoreIcons\":\"" + token + "\"}";
        String log = base + "/hook.log";
        String response = command("settings put global " + KEY + " " + quote(disabled)
            + " || exit 1; for i in 0 1 2 3 4; do "
            + "if grep -qs " + quote("OVC_PANEL_ICONS_RESTORED " + token) + " " + log
            + "; then echo restored; exit 0; fi; [ \"$i\" = 4 ] || sleep 1; done; echo unconfirmed");
        return response.equals("restored");
    }

    private void recordVisualRecovery() throws IOException {
        if (production == null) return;
        command("set -e\novc_boot=$(cat /proc/sys/kernel/random/boot_id)\n"
            + "case \"$ovc_boot\" in ''|*[!a-f0-9-]*) exit 77;; esac\n"
            + "for ovc_marker in recovery.visual-pending recovery.blocked; do\n"
            + "test ! -L " + base + "/\"$ovc_marker\" || exit 77\n"
            + "printf '%s\\n' \"$ovc_boot\" > " + base + "/\"$ovc_marker\"\n"
            + "chmod 600 " + base + "/\"$ovc_marker\"\nsync\ndone");
    }

    private boolean channelMayHaveMutated(String suffix) throws IOException {
        if (production == null) return false;
        String dir = base + suffix;
        return "active".equals(command(workerIdentityScript() + "if ovc_child_live " + dir
            + " || ovc_worker_unobservable " + dir
            + " || { [ -f " + dir + "/enabled ] && [ \"$(cat " + dir + "/worker.boot 2>/dev/null)\" = \"$ovc_boot\" ]"
            + " && [ -s " + dir + "/hook.log ]; }; then echo active; fi"));
    }

    private String stopCommand(String... channels) {
        StringBuilder script = new StringBuilder(workerIdentityScript());
        for (String suffix : channels) {
            String dir = base + suffix;
            script.append("if [ -e ").append(dir).append(" ] || [ -L ").append(dir).append(" ]; then\n")
                .append("test ! -L ").append(dir).append(" && test -d ").append(dir)
                .append(" && test \"$(stat -c '%u:%a' ").append(dir).append(")\" = 0:700 || exit 77\n")
                .append("rm -f ").append(dir).append("/enabled || exit 77\nfi\n");
        }
        script.append("for ovc_wait in 1 2 3 4 5 6 7 8 9 10; do\novc_busy=0\n");
        for (String suffix : channels) {
            String dir = base + suffix;
            script.append("if ovc_worker_live ").append(dir).append(" || ovc_child_live ").append(dir)
                .append(" || ovc_worker_unobservable ").append(dir)
                .append("; then ovc_busy=1; fi\n");
        }
        return script.append("[ \"$ovc_busy\" = 0 ] && exit 0\nsleep 1\ndone\nexit 78\n").toString();
    }

    private static String workerIdentityScript() {
        return """
            ovc_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
            ovc_number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
            ovc_start() {
                ovc_number "$1" && [ "$1" -gt 1 ] || return 1
                ovc_stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
                printf '%s\\n' "${ovc_stat##*) }" | awk 'NF>=20 && $1!="Z" && $1!="X" {print $20}'
            }
            ovc_worker_live() {
                ovc_owner_file="$1/worker.owner"
                [ ! -L "$ovc_owner_file" ] && [ -f "$ovc_owner_file" ] &&
                    [ "$(stat -c '%u:%a' "$ovc_owner_file")" = 0:600 ] || return 1
                ovc_owner=$(cat "$ovc_owner_file" 2>/dev/null) || return 1
                [ -n "$ovc_boot" ] && [ "${ovc_owner%%:*}" = "$ovc_boot" ] || return 1
                ovc_pid=${ovc_owner#*:}; ovc_pid=${ovc_pid%%:*}; ovc_since=${ovc_owner##*:}
                ovc_number "$ovc_since" && [ "$(ovc_start "$ovc_pid")" = "$ovc_since" ]
            }
            ovc_child_live() {
                [ -n "$ovc_boot" ] && [ "$(cat "$1/worker.boot" 2>/dev/null)" = "$ovc_boot" ] || return 1
                ovc_pid=$(cat "$1/child.pid" 2>/dev/null) && ovc_since=$(cat "$1/child.start" 2>/dev/null) || return 1
                ovc_number "$ovc_since" && [ "$(ovc_start "$ovc_pid")" = "$ovc_since" ]
            }
            ovc_worker_unobservable() {
                for ovc_file in worker.pid child.pid; do
                    ovc_pid=$(cat "$1/$ovc_file" 2>/dev/null) || continue
                    ovc_number "$ovc_pid" && [ "$ovc_pid" -gt 1 ] && [ -d "/proc/$ovc_pid" ] || continue
                    ovc_seen=$(ovc_start "$ovc_pid")
                    if ! ovc_number "$ovc_seen" && [ -d "/proc/$ovc_pid" ]; then return 0; fi
                    if [ "$ovc_file" = child.pid ]; then
                        ovc_recorded=$(cat "$1/child.start" 2>/dev/null) || return 0
                    else
                        [ ! -L "$1/worker.owner" ] && [ -f "$1/worker.owner" ] &&
                            [ "$(stat -c '%u:%a' "$1/worker.owner")" = 0:600 ] || return 0
                        ovc_recorded=$(cat "$1/worker.owner" 2>/dev/null) || return 0
                        ovc_recorded=${ovc_recorded##*:}
                    fi
                    ovc_number "$ovc_recorded" || return 0
                done
                return 1
            }
            ovc_ready_fresh() {
                [ ! -L "$1/ready" ] && [ -f "$1/ready" ] || return 1
                ovc_ready=$(stat -c '%Y' "$1/ready" 2>/dev/null) && ovc_now=$(date +%s) || return 1
                ovc_number "$ovc_ready" && ovc_number "$ovc_now" || return 1
                ovc_age=$((ovc_now-ovc_ready))
                [ "$ovc_age" -ge 0 ] && [ "$ovc_age" -le 32 ]
            }
            """;
    }

    private void requireRoot() throws IOException {
        String result = command("id -u");
        if (!result.trim().equals("0")) throw new IOException(UiText.get(R.string.production_broker_unavailable));
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    private String command(String script) throws IOException {
        return production == null ? fixture.execute(script) : production.execute(script);
    }
}
