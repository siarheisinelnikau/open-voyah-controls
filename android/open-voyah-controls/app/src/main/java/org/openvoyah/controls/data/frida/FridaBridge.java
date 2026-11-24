package org.openvoyah.controls.data.frida;

import android.content.Context;
import android.os.Build;
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
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openvoyah.controls.BuildConfig;
import org.openvoyah.controls.ButtonAccessibilityService;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.display.DisplayEventBus.Media;
import org.openvoyah.controls.data.apps.AppGridPreferences;
import org.openvoyah.controls.data.display.AppWindowPreferences;
import org.openvoyah.controls.data.frida.WindowDiagnostics.Operation;
import org.openvoyah.controls.data.frida.WindowDiagnostics.Outcome;
import org.openvoyah.controls.data.frida.WindowDiagnostics.Stage;
import org.openvoyah.controls.domain.overlays.BundledSet;
import org.openvoyah.controls.domain.overlays.TextOverlayCatalog;
import org.openvoyah.controls.domain.display.AppWindowProfile;
import org.openvoyah.controls.domain.display.WindowProfileDelivery;
import org.openvoyah.controls.domain.display.WindowStripState;
import org.openvoyah.controls.data.settings.LocalizationSettings;
import org.openvoyah.controls.data.settings.SettingsStore;
import org.openvoyah.controls.platform.apps.PanelIcons;
import org.openvoyah.controls.domain.permissions.ActionCatalog;
import org.openvoyah.controls.diagnostics.ControlsLog;
import org.openvoyah.controls.platform.boot.RecoveryJobs;

/** Coordinates injected agents, their configuration and recovery. */
public final class FridaBridge {
    private static final String KEY = "org.openvoyah.controls.panel";
    /**
     * Starts a worker in a session of its own (nohup where setsid is missing). A worker started through the root channel
     * otherwise belongs to that telnet session, and a restart of the channel ends its injector while the target keeps
     * the changed visuals, which revokes the activation.
     */
    static final String DETACHED = "$(command -v setsid >/dev/null 2>&1 && echo setsid || echo nohup) ";
    private static final String WINDOW_PROFILES = "org.openvoyah.controls.windowProfiles";
    private static final String HOME_WEATHER = "org.openvoyah.controls.homeWeather";
    /** 0x4f56, the widget host id of the launcher agent's home card. */
    private static final int NAVIGATOR_WIDGET_HOST = 20310;
    private static final String NAVIGATOR_WIDGET_SETTINGS =
        "ru.yandex.yandexnavi/ru.yandex.yandexmaps.widget.traffic.internal.configuration.TrafficWidgetConfigurationActivity";
    private static final String WINDOW_REQUEST = "org.openvoyah.controls.windowRequest";
    private static final String WINDOW_STRIP = "windowSystemInsets";
    /** Set while an update pauses a deployment that was running, so finishing the update starts it again. */
    private static final String RESUME_AFTER_UPDATE = "resumeAfterUpdate";
    private static final String WINDOW_STRIP_ATTEMPT = "windowSystemInsetsAttempt";
    private static final String WINDOW_STRIP_READY = "^OVC_WINDOW_READY protocol=3 .*systemInsets=1( |$)";
    private static final String DISPLAY_MOVE_READY = "^OVC_WINDOW_READY protocol=3 .*displayMove=1( |$)";
    private static final String KEYBOARD_READY = "^OVC_KEYBOARD_READY protocol=1( |$)";
    private static final String FIXTURE_BASE = "/data/local/tmp/open-voyah-controls";
    /** The document the factory assistance agent reads, and the one it reports what it could apply into. */
    private static final String APOLLO_CONFIG = "org.openvoyah.controls.apolloFactory";
    private static final String APOLLO_STATE = "org.openvoyah.controls.apolloFactoryState";
    /** The request id carried by the config document, so a stale agent report is never shown as current. */
    private static final String APOLLO_REQUEST = "apolloRequest";
    private static final String[] CHANNELS = {"", "/locale-setting", "/locale-vehicle", "/locale-bluetooth", "/locale-hiboard", "/window", "/navigation", "/can", "/apollo"};
    private static final String FIXTURE_INJECTOR = "/data/local/tmp/open-voyah-controls-frida-17.26.2";
    private static final Object IO_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService UPDATES = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    private static final RepeatedFailureFilter REVIEW_REFUSALS = new RepeatedFailureFilter();
    private final Context context;
    private final FridaTransport fixture;
    private final ProductionRootAccess production;
    private final AuthorizedRootChannel recoveryChannel;
    private final String base;

    public FridaBridge(Context context) {
        this(context, BuildConfig.OVC_EMULATOR_FIXTURE && FridaTransport.canUseFixture(context)
            ? Integer.valueOf(23) : null);
    }
    public FridaBridge(Context context, int port) {
        this(context, Integer.valueOf(port));
    }
    private FridaBridge(Context context, Integer fixturePort) {
        this(context, fixturePort, null);
    }
    private FridaBridge(Context context, Integer fixturePort, AuthorizedRootChannel recoveryChannel) {
        this.context = context.getApplicationContext();
        this.recoveryChannel = recoveryChannel;
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
                && production.operationAllowed(ReviewedFirmware.WINDOW_SERVICE);
        } catch (Exception unreviewed) {
            // Callers show "unreviewed firmware"; the refusal type tells a changed evidence record apart.
            if (REVIEW_REFUSALS.isRepeat(unreviewed.getClass().getName())) {
                ControlsLog.debug("bridge_window_review", "outcome", "refused", "error", unreviewed);
            } else {
                ControlsLog.warn("bridge_window_review", unreviewed, "outcome", "refused");
            }
            return false;
        }
    }
    boolean passengerWindowReviewed() {
        return production == null || windowReviewed() && production.operationAllowed(ReviewedFirmware.PASSENGER_WINDOW_DPI);
    }
    /** The swipe that moves app windows to the cluster and passenger displays is a separate allowed action. */
    boolean displayMoveReviewed() {
        return production == null || windowReviewed() && production.operationAllowed(ReviewedFirmware.WINDOW_DISPLAY_MOVE);
    }
    // The *Reviewed names are historical: each now means "the APK is pinned for this firmware and the owner has not
    // forbidden the action in Settings → Allowed actions". A review row only marks an action as verified.
    private boolean navigationReviewed() {
        return packageReviewed(ReviewedFirmware.CLUSTER) && (production == null || production.agentAllowed(ActionCatalog.NAVIGATION));
    }
    private boolean localeAllowed(String target) {
        return packageReviewed(ReviewedFirmware.INSTANCE.getLocalizationTargets().get(target))
            && (production == null || production.agentAllowed(ActionCatalog.LOCALE_PREFIX + target));
    }
    /** The CAN key protocol this deployment uses, or null to keep the factory key delivery. */
    private String canSteering() {
        if (production == null || !packageReviewed(ReviewedFirmware.CAN_SERVICE)) return null;
        return ActionCatalog.INSTANCE.canSteeringSource(production.operationPermission(ReviewedFirmware.CAN_STEERING),
            production.operationPermission(ReviewedFirmware.CAN_STEERING_EDGES), production.verifiedOperations());
    }
    private boolean canReviewed() { return canSteering() != null; }
    /** [icons] names the custom panel icons that [publishPanelIcons] wrote just before. */
    private String deploymentConfig(SettingsStore.State state, String icons) {
        try {
            String steering = canSteering();
            String source = steering == null ? "qg" : steering.equals(ReviewedFirmware.CAN_STEERING_EDGES) ? "can-edges" : "can-terminal";
            JSONObject config = new JSONObject(config(state)).put("steeringSource", source).put("customIcons", icons);
            // The app grid's icon style: the agent draws the panel's icons through the same colour matrix.
            float[] matrix = new AppGridPreferences(context).iconStyle().getColorMatrix();
            if (matrix != null) {
                JSONArray values = new JSONArray();
                for (float value : matrix) values.put((double) value);
                config.put("iconMatrix", values);
            }
            return config.toString();
        } catch (JSONException ex) { throw new IllegalArgumentException(ex); }
    }
    public static String config(SettingsStore.State state) {
        try {
            JSONObject root = new JSONObject().put("schema", 1).put("enabled", state.panelEnabled).put("actions", hookActions(state.panelActions));
            // The launcher agent covers the factory navigation card with this app's card; absent keeps the Baidu map.
            if (state.navigatorHomeCard) root.put("homeCard", new JSONObject().put("package", SettingsStore.YANDEX_NAVIGATOR)
                .put("scale", SettingsStore.navigatorWidgetScale(state.navigatorWidgetScale)));
            // The launcher agent shows Controls' weather (HOME_WEATHER) in the factory weather card, or this text without it.
            if (state.weatherCard) root.put("weatherCard", new JSONObject().put("noData", UiText.get(R.string.home_weather_no_data)));
            // The launcher agent adds these apps to the factory media card's sources when they are installed, and
            // hides the factory sources that do not work outside China.
            JSONObject mediaCard = new JSONObject();
            if (state.mediaCardApps) {
                JSONArray sources = new JSONArray();
                for (String[] app : SettingsStore.MEDIA_CARD_APPS) sources.put(new JSONObject().put("package", app[0]).put("service", app[1]));
                mediaCard.put("sources", sources);
            }
            if (state.mediaCardHideOnline) {
                JSONArray hide = new JSONArray();
                for (String source : SettingsStore.MEDIA_CARD_ONLINE_SOURCES) hide.put(source);
                mediaCard.put("hide", hide);
            }
            if (mediaCard.length() > 0) root.put("mediaCard", mediaCard);
            return root
                .put("steeringEnabled", state.enabled).put("steering", hookActions(state.steeringActions))
                .put("steeringHoldMs", state.holdMs).put("steeringDebounceMs", state.debounceMs).toString();
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
    public static String pendingUpdate(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("updatePauseToken", null);
    }
    boolean runtimeConfigured() { return pendingUpdate(context) == null && wasConfigured(context) && knownAuthorization(); }
    private void stopPending(boolean value) throws IOException {
        // This flag is the crash journal that repeats a stop after a reboot. An unsaved change must not pass silently.
        if (!context.getSharedPreferences("bridge",Context.MODE_PRIVATE).edit().putBoolean("stopPending",value).commit()) {
            ControlsLog.error("bridge_journal", "key", "stopPending", "value", value, "outcome", "commit_failed");
            throw new IOException(UiText.get(R.string.ui_152));
        }
    }
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
                    SettingsStore.State state = new SettingsStore(context).load();
                    String icons = publishPanelIcons(state);
                    command(activationGuard() + "settings put global " + KEY + " " + quote(deploymentConfig(state, icons)));
                    if (windowHookEnabled(context) && windowHookSupported() && windowReviewed()) publishWindowProfiles();
                } catch (IOException | RuntimeException ex) {
                    health(UiText.get(R.string.ui_10) + FridaFailureText.message(ex));
                    if (runtimeConfigured() && !(ex instanceof ProductionRootAccess.Refused)) RecoveryJobs.schedule(context);
                    onError.accept(UiText.get(R.string.ui_11) + FridaFailureText.message(ex));
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
    public void connect(SettingsStore.State state) throws IOException { connect(state, DeploymentProgress.NONE); }

    /** [progress] hears each deployment stage on this thread, so the first-run check can show where it is. */
    public void connect(SettingsStore.State state, DeploymentProgress progress) throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("bridge_connect")) {
                try {
                    if (pendingUpdate(context) != null) throw new ProductionRootAccess.Refused(UiText.get(R.string.runtime_update_paused));
                    progress.report(DeploymentProgress.Stage.CHECKING, null, 0, 0);
                    prepareActivation();
                    if (production != null && !context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                            .edit().putBoolean("productionAuthorized", true).commit()) {
                        throw new IOException(UiText.get(R.string.ui_152));
                    }
                    configured(true); // Persist desired state only after production verification.
                    stopPending(false);
                    connectLocked(state, true, progress, span);
                    health(UiText.get(R.string.ui_12));
                    RecoveryJobs.cancel(context);
                    span.outcome("connected");
                }
                catch (ProductionRootAccess.Refused ex) {
                    record(span, ex);
                    health(ex.getMessage());
                    RecoveryJobs.cancel(context);
                    throw ex;
                }
                catch (IOException | RuntimeException ex) {
                    record(span, ex);
                    health(UiText.get(R.string.ui_13) + FridaFailureText.message(ex));
                    if (runtimeConfigured()) RecoveryJobs.schedule(context);
                    throw ex;
                }
            }
        }
    }
    public void reconnectAfterBoot() throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("bridge_reconnect")) {
                if (Thread.currentThread().isInterrupted()) { span.outcome("interrupted"); return; }
                if (pendingUpdate(context) != null) { span.outcome("update_paused"); return; }
                if (!knownAuthorization()) {
                    health(UiText.get(R.string.production_connect_required));
                    span.outcome("connect_required");
                    return;
                }
                String path = "verify";
                try {
                    if (!runtimeConfigured()) {
                        boolean recover = needsRecovery(context);
                        path = recover ? "stop" : "idle";
                        if (recover) stopLocked();
                        span.outcome("completed");
                        return;
                    }
                    if (production != null) {
                        // A revocation of an earlier boot is lifted here when the sealed deployment still verifies;
                        // the reboot itself restored the original visuals. Anything else stays for the owner or the PC.
                        path = "recover";
                        ActivationRecovery.Result recovery = recoverActivationLocked(ActivationRecovery.Mode.AUTO, span);
                        if (recovery.getOutcome() == ActivationRecovery.Outcome.WAITING) {
                            throw new IOException(UiText.get(R.string.activation_restore_waiting));
                        }
                        path = "verify";
                    }
                    path = "resume";
                    resumeAfterRecoveryLocked(span);
                    health(UiText.get(R.string.ui_14));
                    span.outcome("completed");
                }
                catch (ProductionRootAccess.Refused ex) { record(span, ex); health(ex.getMessage()); }
                catch (IOException | RuntimeException ex) {
                    record(span, ex);
                    health(UiText.get(R.string.ui_15) + FridaFailureText.message(ex));
                    throw ex;
                }
                finally { span.field("path", path); }
            }
        }
    }

    /** Starts the sealed workers again, or deploys afresh when the sealed files no longer verify. */
    private void resumeAfterRecoveryLocked(ControlsLog.Span span) throws IOException {
        prepareActivation();
        restoreButtonServiceAfterBoot();
        SettingsStore.State state = new SettingsStore(context).load();
        if (production == null || !resumeVerifiedDeployment(state)) {
            span.field("deploy", true);
            connectLocked(state, false, DeploymentProgress.NONE, span);
        }
    }

    /**
     * The owner's Restore in Settings → About and restart: re-arms the boot activation without the PC when only a
     * revocation blocks it and the sealed deployment still verifies, then starts the workers. See {@link ActivationRecovery}.
     */
    public ActivationRecovery.Result restoreActivation() throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("bridge_restore_activation")) {
                try {
                    if (production == null) return new ActivationRecovery.Result(ActivationRecovery.Outcome.NONE, "fixture",
                        java.util.List.of());
                    if (!knownAuthorization() || !runtimeConfigured()) {
                        throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
                    }
                    ActivationRecovery.Result result = recoverActivationLocked(ActivationRecovery.Mode.MANUAL, span);
                    if (result.getOutcome() == ActivationRecovery.Outcome.RECOVERED
                            || result.getOutcome() == ActivationRecovery.Outcome.NONE) {
                        resumeAfterRecoveryLocked(span);
                        health(UiText.get(R.string.ui_14));
                    }
                    span.outcome(result.getOutcome().getToken());
                    return result;
                } catch (IOException | RuntimeException ex) {
                    record(span, ex);
                    throw ex;
                }
            }
        }
    }

    private ActivationRecovery.Result recoverActivationLocked(ActivationRecovery.Mode mode, ControlsLog.Span span)
            throws IOException {
        requireRoot();
        ActivationRecovery.Result result = ActivationRecovery.parse(command(ActivationRecovery.script(base, mode)));
        span.field("recovery", result.getOutcome().getToken()).field("recovery_reason", result.getReason());
        if (result.getOutcome() != ActivationRecovery.Outcome.NONE) {
            ControlsLog.info("activation_recovery", "mode", mode.getToken(), "outcome", result.getOutcome().getToken(),
                "reason", result.getReason(), "channels", String.join(",", result.getChannels()));
        }
        switch (result.getOutcome()) {
            case RECOVERED, NONE -> recordActivationPaused(false);
            case REBOOT, WAITING -> recordActivationPaused(true);
            default -> { }
        }
        return result;
    }

    private void prepareActivation() throws IOException {
        if (production == null) return;
        production.refresh();
        production.requireActivation();
    }

    /**
     * Some firmware rewrites Android's enabled accessibility services early in every boot, which disconnects the
     * button service setup enabled. Restoring it never blocks the agents: the Buttons screen offers the same restore.
     */
    private void restoreButtonServiceAfterBoot() {
        if (production == null) return;
        try {
            ButtonServiceGrant.Outcome outcome = ButtonServiceGrant.outcome(command(buttonServiceScript(false)));
            if (outcome == ButtonServiceGrant.Outcome.RESTORED) {
                ControlsLog.info("button_service", "path", "boot", "outcome", outcome.getToken());
            } else {
                ControlsLog.debug("button_service", "path", "boot", "outcome", outcome.getToken());
            }
        } catch (IOException | RuntimeException ex) {
            ControlsLog.warn("button_service", ex, "path", "boot", "outcome", "failed");
        }
    }

    /** Enables the button accessibility service on the owner's request through the provisioned root channel. */
    public ButtonServiceGrant.Outcome enableButtonService() throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("button_service", "path", "explicit")) {
                try {
                    if (!knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
                    ButtonServiceGrant.Outcome outcome = ButtonServiceGrant.outcome(command(buttonServiceScript(true)));
                    span.outcome(outcome.getToken());
                    return outcome;
                } catch (IOException | RuntimeException ex) {
                    record(span, ex);
                    throw ex;
                }
            }
        }
    }

    private String buttonServiceScript(boolean explicit) {
        return ButtonServiceGrant.script(context.getPackageName(), ButtonAccessibilityService.class.getName(), explicit);
    }

    /** Whether the window agent in system_server announced the keyboard policy; see {@link KeyboardSelection}. */
    public boolean keyboardPolicyReady() throws IOException {
        synchronized (IO_LOCK) {
            return runtimeConfigured() && windowHookEnabled(context) && windowHookSupported() && windowReviewed()
                && channelReady("/window", KEYBOARD_READY);
        }
    }

    /** Runs a {@link KeyboardSelection} script through the provisioned root channel on the owner's request. */
    public String keyboardCommand(String script) throws IOException {
        synchronized (IO_LOCK) {
            if (!knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
            return command(script);
        }
    }

    /**
     * Starts the sealed workers when every staged script and agent is exactly what this APK carries, so after an
     * update of the app itself (the supervisor keeps a same-signer update active) unchanged agents keep running and
     * changed ones are redeployed by the caller. A receipt sealed by another version of the app is resealed here, so
     * its version floor follows the installed app; nothing else in it changes.
     */
    private boolean resumeVerifiedDeployment(SettingsStore.State state) throws IOException {
        String entry = quote(base + "/boot-start.sh");
        Set<String> channels = enabledChannels();
        Map<String, String> pins;
        try {
            pins = artifactPins(channels);
        } catch (ProductionRootAccess.Refused unreviewed) {
            return false; // A channel this version no longer runs: deploy afresh with the current choice.
        }
        String reply = command(activationGuard() + ResumeCheck.script(base, pins, production.signerRow()));
        ResumeCheck.Result result = ResumeCheck.parse(reply);
        if (result == ResumeCheck.Result.STALE) return false;
        if (result == ResumeCheck.Result.RESEAL) {
            sealDeployment(channels);
            ControlsLog.info("bridge_resume", "outcome", "resealed", "channels", String.join(",", channels));
        }
        String icons = publishPanelIcons(state);
        command(activationGuard() + "settings put global " + KEY + " " + quote(deploymentConfig(state, icons)));
        if (windowHookEnabled(context) && windowHookSupported() && windowReviewed()) publishWindowProfiles();
        command(activationGuard() + "/system/bin/sh " + entry);
        return true;
    }

    private void connectLocked(SettingsStore.State state, boolean resetRetries, DeploymentProgress progress,
                               ControlsLog.Span span) throws IOException {
        requireRoot();
        String version = checkedInjectorVersion();
        String expected = production == null ? "17.26.2" : production.injectorVersion();
        if (!version.equals(expected)) throw new IOException(UiText.get(R.string.ui_16) + version);
        command("test ! -L " + base + " && mkdir -p " + base + " && test \"$(stat -c %u " + base + ")\" = 0 && chmod 700 " + base);
        // Decide the optional channels once, so the upload count shown to the owner matches the work below.
        boolean window = windowHookEnabled(context) && windowHookSupported() && windowReviewed();
        boolean navigation = navigationHookEnabled(context) && navigationReviewed();
        boolean can = canReviewed();
        boolean apollo = apolloHookEnabled(context) && apolloReviewed();
        java.util.List<String> locales = localizationChannels();
        Uploads uploads = new Uploads(progress, 3 + locales.size() + (window ? 1 : 0) + (navigation ? 1 : 0) + (can ? 1 : 0)
            + (apollo ? 1 : 0));
        progress.report(DeploymentProgress.Stage.STOPPING, null, 0, 0);
        // Stop only this project's prior worker. Never kill another Frida instance.
        boolean panelRestored = restorePanelIcons();
        boolean windowRestored = releaseWindowLocked();
        if (!panelRestored || !windowRestored) recordVisualRecovery();
        command(stopCommand(CHANNELS));
        panelRestored &= stoppedVisualSession("");
        windowRestored &= stoppedVisualSession("/window");
        if (!panelRestored || !windowRestored) {
            recordVisualRecovery();
            throw new ProductionRootAccess.Refused(UiText.get(R.string.production_recovery_blocked));
        }
        if (resetRetries) resetRetryBudget();
        command(activationGuard() + "settings put global org.openvoyah.controls.localization "
            + quote(new LocalizationSettings(context).exportJson()));
        uploads.install("hooks/launcher-buttons.bundle.js", base + "/launcher-buttons.js", "600", null);
        uploads.install("hooks/supervise.sh", base + "/supervise.sh", "700", null);
        uploads.install("hooks/boot-start.sh", base + "/boot-start.sh", "700", null);
        String icons = publishPanelIcons(state);
        command(activationGuard() + "chmod 700 " + base + "/supervise.sh " + base + "/boot-start.sh"
            + " && settings put global " + KEY + " " + quote(deploymentConfig(state, icons)));
        Set<String> channels = new LinkedHashSet<>();
        // Every translation worker runs the same bundle: send it once and copy it on the device for the others.
        String localeSource = null;
        for (String channel : locales) {
            prepareChannelDirectory(channel);
            String path = base + "/" + channel + "/launcher-buttons.js";
            uploads.install("hooks/localization.bundle.js", path, "600", localeSource);
            if (localeSource == null) localeSource = path;
            channels.add(channel);
        }
        command("settings delete global " + WINDOW_REQUEST);
        if (window) {
            publishWindowProfiles();
            stageOptionalHook("window", uploads);
            channels.add("window");
        } else {
            command("settings delete global " + WINDOW_PROFILES);
        }
        if (navigation) {
            stageOptionalHook("navigation", uploads);
            channels.add("navigation");
        }
        if (can) {
            stageOptionalHook("can", uploads);
            channels.add("can");
        }
        if (apollo) {
            publishApolloConfig();
            stageOptionalHook("apollo", uploads);
            channels.add("apollo");
        } else {
            command("settings delete global " + APOLLO_CONFIG);
        }
        progress.report(DeploymentProgress.Stage.SEALING, null, 0, 0);
        sealDeployment(channels);
        for (String channel : channels) enableChannel(channel);
        progress.report(DeploymentProgress.Stage.STARTING, null, 0, 0);
        command(activationGuard() + "rm -f " + base + "/hook.log " + base + "/ready && touch " + base + "/enabled"
            + " && " + fixtureEnvironment() + "/system/bin/sh " + base + "/boot-start.sh");
        configured(true);
        span.field("uploaded", uploads.sent).field("reused", uploads.reused).field("copied", uploads.copied)
            .field("sent_kib", uploads.bytes / 1024);
    }

    /** Counts and reports the agent files of one deployment; see {@link #install}. */
    private final class Uploads {
        final DeploymentProgress progress;
        final int count;
        int index, sent, reused, copied;
        long bytes;
        Uploads(DeploymentProgress progress, int count) { this.progress = progress; this.count = count; }

        void install(String asset, String path, String mode, String copyFrom) throws IOException {
            index++;
            String file = asset.substring(asset.lastIndexOf('/') + 1);
            switch (FridaBridge.this.install(asset, path, mode, copyFrom, this, file)) {
                case REUSING -> reused++;
                case COPYING -> copied++;
                default -> sent++;
            }
        }
    }

    /** Only a new user connection may renew the bounded budget after verified restoration and stop. */
    private void resetRetryBudget() throws IOException {
        verifyUpdateQuiescence();
        StringBuilder script = new StringBuilder(activationGuard());
        // Validate the complete scope before deleting even one channel's retry evidence.
        for (String suffix : CHANNELS) {
            String dir = quote(base + suffix);
            script.append("if [ -e ").append(dir).append(" ] || [ -L ").append(dir).append(" ]; then\n")
                .append("test ! -L ").append(dir).append(" && test -d ").append(dir)
                .append(" && test \"$(stat -c '%u:%a' ").append(dir).append(")\" = 0:700 || exit 77\n");
            for (String file : new String[]{"failure.boot", "failure.count"}) {
                String path = quote(base + suffix + "/" + file);
                script.append("if [ -e ").append(path).append(" ] || [ -L ").append(path).append(" ]; then\n")
                    .append("test ! -L ").append(path).append(" && test -f ").append(path)
                    .append(" && test \"$(stat -c '%u:%a:%h' ").append(path).append(")\" = 0:600:1 || exit 77\nfi\n");
            }
            script.append("fi\n");
        }
        for (String suffix : CHANNELS) {
            script.append("rm -f ").append(quote(base + suffix + "/failure.boot")).append(" ")
                .append(quote(base + suffix + "/failure.count")).append(" || exit 77\n");
        }
        command(script.append("sync\n").toString());
    }

    private String checkedInjectorVersion() throws IOException {
        String guard = production == null ? "" : production.injectorGuard();
        return command(guard + quote(injectorPath()) + " --version").trim();
    }

    /** Translation channels whose target APK is pinned for this firmware and not forbidden, in a stable order. */
    private java.util.List<String> localizationChannels() {
        java.util.List<String> channels = new java.util.ArrayList<>();
        if (!new LocalizationSettings(context).enabled()) return channels;
        for (var entry : ReviewedFirmware.INSTANCE.getLocalizationTargets().entrySet()) {
            if (localeAllowed(entry.getKey()) && runs64Bit(entry.getValue())) channels.add("locale-" + entry.getKey());
        }
        return channels;
    }

    /**
     * The Frida agent crashes 32-bit ART processes (the 32-bit Settings and Bluetooth phone of a 97C head unit), so a
     * translation worker is staged only for an app that Android runs 64-bit. supervise.sh checks the process as well.
     */
    private boolean runs64Bit(String packageName) {
        try {
            var info = context.getPackageManager().getApplicationInfo(packageName, 0);
            // Hidden but stable since Android 5; null means no native code, so the app runs with the 64-bit zygote.
            Object abi = android.content.pm.ApplicationInfo.class.getField("primaryCpuAbi").get(info);
            return abi == null || String.valueOf(abi).contains("64");
        } catch (ReflectiveOperationException | RuntimeException | android.content.pm.PackageManager.NameNotFoundException unknown) {
            // Leave the decision to the supervisor, which refuses a 32-bit process before attaching.
            return true;
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
        try (var diagnostic = WindowDiagnostics.start(enabled ? Operation.ENABLE_SERVICE : Operation.DISABLE_SERVICE)) {
            if (enabled && !windowHookSupported()) {
                diagnostic.complete(Outcome.ANDROID_UNSUPPORTED);
                throw new IOException(UiText.get(R.string.window_hook_requirement));
            }
            diagnostic.enter(Stage.SERVICE_TRANSITION);
            setOptionalHook("window", enabled);
            diagnostic.complete(Outcome.SERVICE_CONFIGURED);
        }
    }
    public static boolean navigationHookEnabled(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean("navigationHook", false);
    }
    public void setNavigationHook(boolean enabled) throws IOException { setOptionalHook("navigation", enabled); }

    /** The three parts of the factory assistance path the driver can choose independently. */
    public static final java.util.List<String> APOLLO_PARTS = java.util.List.of("subscription", "controller", "equipment");
    private static String apolloKey(String part) {
        return switch (part) {
            case "subscription" -> "apolloSubscription";
            case "controller" -> "apolloController";
            case "equipment" -> "apolloEquipment";
            default -> throw new IllegalArgumentException("Unknown factory assistance part");
        };
    }
    public static boolean apolloPartEnabled(Context context, String part) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean(apolloKey(part), false);
    }
    /** The channel runs while the driver wants any part of the factory assistance path. */
    public static boolean apolloHookEnabled(Context context) {
        for (String part : APOLLO_PARTS) if (apolloPartEnabled(context, part)) return true;
        return false;
    }
    /** The request id of the config the agent is working on, or null before anything was published. */
    public static String apolloRequest(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getString(APOLLO_REQUEST, null);
    }
    /** The document the agent reads. Only the chosen parts are true; nothing is inferred from the vehicle. */
    public static String apolloConfig(Context context) {
        return apolloConfig(context, apolloRequest(context), false, "", 0L, 0L);
    }
    private static String apolloConfig(Context context, String request, boolean send, String boot,
                                       long issued, long expires) {
        var store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        StringBuilder document = new StringBuilder("{\"schema\":1");
        if (request != null) document.append(",\"request\":\"").append(request).append('"');
        // A send permit is written only after a fresh Park and zero-speed check. The boot id is authoritative across
        // a reboot; wall-clock bounds cover a clock adjustment inside one boot. Elapsed real time restarts with the
        // kernel and could make an old document look fresh to the boot agent.
        if (send) document.append(",\"send\":true,\"boot\":\"").append(boot)
            .append("\",\"issued\":").append(issued).append(",\"expires\":").append(expires);
        for (String part : APOLLO_PARTS) document.append(",\"").append(part).append("\":")
            .append(store.getBoolean(apolloKey(part), false));
        return document.append('}').toString();
    }
    /** What the agent reported it could apply, or null when it has not written one yet. */
    public static String apolloState(Context context) {
        try { return android.provider.Settings.Global.getString(context.getContentResolver(), APOLLO_STATE); }
        catch (RuntimeException unreadable) { return null; }
    }
    /** The factory settings package is pinned for this firmware and the owner has not forbidden the agent. */
    private boolean apolloReviewed() {
        return packageReviewed(ReviewedFirmware.VEHICLE_SETTINGS)
            && (production == null || production.agentAllowed(ActionCatalog.APOLLO));
    }
    /**
     * Turns one part of the factory assistance path over to the agent; the agent applies it within a second.
     * The preference is written only after every check that can refuse the request has passed, and anything that
     * fails afterwards puts the position back so the interface cannot show an answer the agent never received.
     */
    public void setApolloPart(String part, boolean enabled) throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("bridge_apollo", "part", part, "enabled", enabled)) {
                String key = apolloKey(part);
                var store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
                boolean previous = store.getBoolean(key, false);
                boolean saved = false;
                try {
                    if (!knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
                    requireRoot();
                    if (enabled && !apolloReviewed()) throw unreviewedOperation();
                    // Only the controller writes to the car. A neighbouring switch never carries a permit.
                    boolean send = enabled && "controller".equals(part);
                    if (!store.edit().putBoolean(key, enabled).commit()) {
                        throw new IOException(UiText.get(R.string.ui_152));
                    }
                    saved = true;
                    if (!apolloHookEnabled(context)) {
                        stopApolloChannel(store);
                    } else {
                        if (!runtimeConfigured()) throw new IOException(UiText.get(R.string.ui_19));
                        if (production != null) production.requireActivation();
                        if (!enabledChannels().contains("apollo")) {
                            stageOptionalHook("apollo");
                            if (production != null) {
                                Set<String> active = enabledChannels();
                                active.add("apollo");
                                sealDeployment(active);
                            }
                            enableChannel("apollo");
                            command(activationGuard() + fixtureEnvironment() + DETACHED + "/system/bin/sh " + base
                                + "/supervise.sh apollo >/dev/null 2>&1 </dev/null &");
                        }
                        // The live Park check and its short-lived permit are written as the last step, so a
                        // stopped agent cannot pick up a permit from long before it attached.
                        if (send) requireApolloParked();
                        publishApolloConfig(send);
                    }
                    span.outcome("applied");
                } catch (IOException | RuntimeException ex) {
                    if (saved) restoreApolloPart(part, previous);
                    record(span, ex);
                    throw ex;
                }
            }
        }
    }

    /** The launcher agent reads live gear and speed again here; no cached reading can open this gate. */
    private void requireApolloParked() throws IOException {
        VoiceReply parking = sendRecognizedVoice("ParkingPreflight", new JSONObject());
        if (!parking.accepted() || !"OVC_PARKING_READY".equals(parking.message())) {
            throw new IOException(UiText.get(R.string.factory_assistance_park_required));
        }
    }

    private void stopApolloChannel(android.content.SharedPreferences store) throws IOException {
        command(stopCommand("/apollo"));
        command(activationGuard() + "settings delete global " + APOLLO_CONFIG);
        store.edit().remove(APOLLO_REQUEST).commit();
    }

    /** Puts the switch and the document back after a failed command, so the agent drops the request too. */
    private void restoreApolloPart(String part, boolean previous) {
        try {
            var store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
            if (!store.edit().putBoolean(apolloKey(part), previous).commit()) {
                throw new IOException(UiText.get(R.string.ui_152));
            }
            if (apolloHookEnabled(context)) publishApolloConfig(false);
            else stopApolloChannel(store);
        } catch (IOException | RuntimeException rollback) {
            ControlsLog.warn("bridge_apollo_rollback", rollback, "part", part);
        }
    }

    private void publishApolloConfig() throws IOException { publishApolloConfig(false); }

    /** A new request id each time ties the agent's report to this write, not to an earlier config it may still hold. */
    private void publishApolloConfig(boolean send) throws IOException {
        String request = UUID.randomUUID().toString().replace("-", "");
        String boot = send ? currentBootId() : "";
        long issued = send ? System.currentTimeMillis() : 0L;
        long expires = send ? issued + 5000L : 0L;
        var store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        if (!store.edit().putString(APOLLO_REQUEST, request).commit()) {
            throw new IOException(UiText.get(R.string.ui_152));
        }
        command(activationGuard() + "settings put global " + APOLLO_CONFIG + " "
            + quote(apolloConfig(context, request, send, boot, issued, expires)));
    }

    /** The kernel boot id is the only clock-independent proof that a permit belongs to this boot. */
    private static String currentBootId() throws IOException {
        try (var reader = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            String boot = value == null ? "" : value.trim();
            if (!boot.matches("[a-f0-9-]{8,64}")) throw new IOException("Boot identity is unavailable.");
            return boot;
        }
    }

    /** Whether the pinned firmware and the owner's allowed actions let one optional channel run. */
    private boolean channelReviewed(String channel) {
        return switch (channel) {
            case "window" -> windowReviewed();
            case "navigation" -> navigationReviewed();
            case "can" -> canReviewed();
            case "apollo" -> apolloReviewed();
            default -> false;
        };
    }

    private void setOptionalHook(String channel, boolean enabled) throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("bridge_optional_hook", "channel", channel, "enabled", enabled)) {
                try {
                    if (!knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
                    requireRoot();
                    boolean restored = true;
                    if (enabled) {
                        if (!runtimeConfigured()) throw new IOException(UiText.get(R.string.ui_19));
                        if (!channelReviewed(channel)) throw unreviewedOperation();
                        if (production != null) production.requireActivation();
                        boolean reuseWindow = channel.equals("window") && visualSessionState("/window").state().equals("current")
                            && liveWindowReady("3");
                        if (channel.equals("window") && !reuseWindow) {
                            boolean previousRestored = releaseWindowLocked();
                            if (!previousRestored) recordVisualRecovery();
                            command(stopCommand("/window"));
                            previousRestored &= stoppedVisualSession("/window");
                            if (!previousRestored) {
                                recordVisualRecovery();
                                throw new ProductionRootAccess.Refused(UiText.get(R.string.window_restore_unconfirmed));
                            }
                            clearWindowSettings();
                        }
                        if (channel.equals("window")) publishWindowProfiles();
                        if (!reuseWindow) {
                            stageOptionalHook(channel);
                            if (production != null) {
                                Set<String> active = enabledChannels();
                                active.add(channel);
                                sealDeployment(active);
                            }
                            enableChannel(channel);
                            command(activationGuard() + fixtureEnvironment() + DETACHED + "/system/bin/sh " + base + "/supervise.sh "
                                + channel + " >/dev/null 2>&1 </dev/null &");
                        }
                    } else {
                        if (channel.equals("window")) restored = releaseWindowLocked();
                        if (!restored) recordVisualRecovery();
                        command(stopCommand("/" + channel));
                        if (channel.equals("window")) {
                            restored &= stoppedVisualSession("/window");
                            if (!restored) recordVisualRecovery();
                            clearWindowSettings();
                        }
                    }
                    span.field("restored", restored);
                    if (!context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putBoolean(channel + "Hook", enabled).commit()) {
                        throw new IOException(UiText.get(R.string.ui_152));
                    }
                    if (!restored) throw new IOException(UiText.get(R.string.window_restore_unconfirmed));
                    span.outcome("applied");
                } catch (IOException | RuntimeException ex) {
                    record(span, ex);
                    throw ex;
                }
            }
        }
    }
    private void stageOptionalHook(String channel) throws IOException { stageOptionalHook(channel, null); }

    private void stageOptionalHook(String channel, Uploads uploads) throws IOException {
        String dir = base + "/" + channel;
        prepareChannelDirectory(channel);
        String asset = "hooks/" + channel + ".bundle.js";
        if (uploads == null) upload(asset, dir + "/launcher-buttons.js");
        else uploads.install(asset, dir + "/launcher-buttons.js", "600", null);
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
        production.sealDeployment(artifactPins(channels));
    }

    /** The staged file of each sealed artifact and the SHA-256 of the asset it comes from in this APK. */
    private Map<String, String> artifactPins(Set<String> channels) throws IOException {
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("boot-start.sh", assetDigest("hooks/boot-start.sh"));
        artifacts.put("supervise.sh", assetDigest("hooks/supervise.sh"));
        artifacts.put("launcher-buttons.js", assetDigest("hooks/launcher-buttons.bundle.js"));
        for (String channel : channels) {
            if (channel.startsWith("locale-")) {
                if (!localeAllowed(channel.substring(7))) throw unreviewedOperation();
            } else if (!channelReviewed(channel)) throw unreviewedOperation();
            String script = channel.startsWith("locale-") ? "localization" : channel;
            artifacts.put(channel + "/launcher-buttons.js", assetDigest("hooks/" + script + ".bundle.js"));
        }
        return artifacts;
    }

    private String assetDigest(String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            return ReviewedFirmware.INSTANCE.digest(input);
        }
    }
    private void upload(String name, String path) throws IOException {
        command(uploadScript(name, path, asset(name), assetDigest(name)));
    }

    /**
     * Puts one agent file in place and says how. A file the device already holds with the same SHA-256, a single
     * link and the expected owner and [mode] is kept: the seal checks exactly these facts, and sending megabytes of
     * base64 through the Telnet PTY was most of a connection's time. [copyFrom] names a file of this deployment
     * that is already in place, so the same bundle is copied on the device instead of being sent again.
     */
    private DeploymentProgress.Stage install(String name, String path, String mode, String copyFrom, Uploads uploads,
                                             String file) throws IOException {
        String digest = assetDigest(name);
        String target = quote(path);
        String current = "[ ! -L " + target + " ] && [ -f " + target + " ] && [ \"$(stat -c '%u:%a:%h' " + target
            + " 2>/dev/null)\" = 0:" + mode + ":1 ] && ovc_hash=$(sha256sum " + target + " 2>/dev/null) && [ \"${ovc_hash%% *}\" = '"
            + digest + "' ]";
        String check = activationGuard() + "if " + current + "; then echo OVC_ASSET_CURRENT; exit 0; fi\n";
        uploads.progress.report(DeploymentProgress.Stage.VERIFYING, file, uploads.index, uploads.count);
        if (copyFrom != null) {
            String source = quote(copyFrom);
            String fresh = quote(path + ".new");
            String reply = command(check + "[ ! -L " + source + " ] && ovc_hash=$(sha256sum " + source + ") && [ \"${ovc_hash%% *}\" = '"
                + digest + "' ] || { echo OVC_ASSET_SEND; exit 0; }\n"
                + "test ! -L " + fresh + " && cp " + source + " " + fresh + " || exit 1\n"
                + "ovc_hash=$(sha256sum " + fresh + ") && [ \"${ovc_hash%% *}\" = '" + digest + "' ] || { rm -f " + fresh + "; exit 1; }\n"
                + "test ! -L " + target + " && chmod 600 " + fresh + " && mv " + fresh + " " + target + " && echo OVC_ASSET_COPIED");
            if (reply.equals("OVC_ASSET_CURRENT")) return report(uploads, DeploymentProgress.Stage.REUSING, file);
            if (reply.equals("OVC_ASSET_COPIED")) return report(uploads, DeploymentProgress.Stage.COPYING, file);
        } else if (command(check + "echo OVC_ASSET_SEND").equals("OVC_ASSET_CURRENT")) {
            return report(uploads, DeploymentProgress.Stage.REUSING, file);
        }
        uploads.progress.report(DeploymentProgress.Stage.UPLOADING, file, uploads.index, uploads.count);
        String encoded = asset(name);
        uploads.bytes += encoded.length();
        command(uploadScript(name, path, encoded, digest));
        return DeploymentProgress.Stage.UPLOADING;
    }

    private static DeploymentProgress.Stage report(Uploads uploads, DeploymentProgress.Stage stage, String file) {
        uploads.progress.report(stage, file, uploads.index, uploads.count);
        return stage;
    }

    /** One root command: decode into a fresh file, check its SHA-256, then replace the target. */
    private String uploadScript(String name, String path, String encoded, String digest) {
        String fresh = quote(path + ".new");
        StringBuilder lines = new StringBuilder(activationGuard() + "test ! -L " + fresh
            + " && base64 -d > " + fresh + " <<'OVC_ASSET' || exit 1\n");
        // Emulator terminals may truncate long lines. Keep each heredoc line below 1 KiB.
        for (int i = 0; i < encoded.length(); i += 768)
            lines.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        lines.append("OVC_ASSET\n");
        lines.append("ovc_hash=$(sha256sum ").append(fresh).append(") && [ \"${ovc_hash%% *}\" = '").append(digest)
            .append("' ] || { rm -f ").append(fresh).append("; exit 1; }\n");
        lines.append("test ! -L ").append(quote(path)).append(" && chmod 600 ").append(fresh)
            .append(" && mv ").append(fresh).append(" ").append(quote(path));
        return lines.toString();
    }
    /** Experimental: Android itself keeps the launcher panel strip clear. Device-only, like the service switch. */
    public static boolean windowStripEnabled(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean(WINDOW_STRIP, false);
    }

    /**
     * Saves the choice and hands it to a running window agent. Returns whether the agent received it; a choice that
     * was only saved takes effect with the next publication of the window profiles.
     */
    public boolean setWindowStrip(boolean enabled) throws IOException {
        synchronized (IO_LOCK) {
            try (ControlsLog.Span span = ControlsLog.span("window_strip", "enabled", enabled)) {
                boolean live = runtimeConfigured() && windowHookEnabled(context) && windowHookSupported() && windowReviewed();
                if (enabled && !live) {
                    span.outcome("service_off");
                    throw new IOException(UiText.get(R.string.window_hook_requirement));
                }
                if (live) {
                    requireRoot();
                    // An agent deployed before the option existed would ignore it and never answer for it.
                    live = channelReady("/window", WINDOW_STRIP_READY);
                    if (enabled && !live) {
                        span.outcome("agent_unavailable");
                        throw new IOException(UiText.get(R.string.window_strip_agent_required));
                    }
                }
                var store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
                int attempt = store.getInt(WINDOW_STRIP_ATTEMPT, 0);
                // Every time the driver turns the option on is a new attempt. The agent blocks an attempt that may
                // have ended the system process, and only a new number lets it try again.
                if (enabled) attempt = attempt >= 1_000_000 ? 1 : attempt + 1;
                if (!store.edit().putBoolean(WINDOW_STRIP, enabled).putInt(WINDOW_STRIP_ATTEMPT, attempt).commit()) {
                    span.outcome("not_saved");
                    throw new IOException(UiText.get(R.string.ui_152));
                }
                if (live) publishWindowProfiles();
                span.outcome(live ? "published" : "saved");
                return live;
            }
        }
    }

    /** Asks the window agent what it knows about the reserved strip. Changes nothing. */
    public WindowStripState windowStripState() throws IOException {
        String reply;
        synchronized (IO_LOCK) {
            if (!runtimeConfigured() || !windowHookEnabled(context) || !windowHookSupported() || !windowReviewed()) {
                return new WindowStripState(WindowStripState.Phase.SERVICE_OFF, 0, null);
            }
            requireRoot();
            if (!channelReady("/window", WINDOW_STRIP_READY)) {
                return new WindowStripState(WindowStripState.Phase.AGENT_UNAVAILABLE, 0, null);
            }
            try {
                reply = windowRequest("insets", "");
            } catch (IOException unanswered) {
                return new WindowStripState(WindowStripState.Phase.AGENT_UNAVAILABLE, 0, null);
            }
        }
        return WindowStripReceipt.parse(reply);
    }

    /**
     * Writes the panel apps' custom icons before the configuration that names them, so the agent never looks
     * for icons that have not arrived. Returns the name the configuration carries.
     */
    private String publishPanelIcons(SettingsStore.State state) throws IOException {
        Set<String> packages = new LinkedHashSet<>();
        for (Action action : state.panelActions.values()) {
            switch (action.kind) {
                case LAUNCH_APP -> packages.add(action.component.substring(0, Math.max(0, action.component.indexOf('/'))));
                case CONTROLS_APPS, CONTROLS_SETTINGS, START_VOICE -> packages.add(context.getPackageName());
                default -> { }
            }
        }
        String document = PanelIcons.document(context, packages);
        String encoded = Base64.encodeToString(document.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        StringBuilder script = new StringBuilder(activationGuard() + "settings put global " + PanelIcons.KEY
            + " \"$(base64 -d <<'OVC_ICONS'\n");
        for (int i = 0; i < encoded.length(); i += 768) {
            script.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        }
        command(script.append("OVC_ICONS\n)\"").toString());
        return PanelIcons.version(document);
    }

    private void publishWindowProfiles() throws IOException {
        var store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        Integer stripAttempt = store.getBoolean(WINDOW_STRIP, false) ? Integer.valueOf(store.getInt(WINDOW_STRIP_ATTEMPT, 0)) : null;
        String profiles = new AppWindowPreferences(context).agentConfig(true, passengerWindowReviewed(),
            production != null ? Integer.valueOf(1) : null, stripAttempt, displayMoveReviewed());
        String encoded = Base64.encodeToString(profiles.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        StringBuilder script = new StringBuilder(activationGuard() + "settings put global " + WINDOW_PROFILES
            + " \"$(base64 -d <<'OVC_WINDOWS'\n");
        for (int i = 0; i < encoded.length(); i += 768) {
            script.append(encoded, i, Math.min(i + 768, encoded.length())).append('\n');
        }
        command(script.append("OVC_WINDOWS\n)\"").toString());
    }

    public WindowProfileDelivery applyWindowProfile(String component, boolean reset) throws IOException {
        try (var diagnostic = WindowDiagnostics.start(reset ? Operation.RESET_PROFILE : Operation.APPLY_PROFILE)) {
            String target = AppWindowProfile.Companion.key(component);
            if (target == null) {
                diagnostic.complete(Outcome.INVALID_TARGET);
                throw new IllegalArgumentException("Invalid window target");
            }
            String reply;
            synchronized (IO_LOCK) {
                if (!checkWindowPrerequisites(diagnostic)) {
                    throw new IOException(UiText.get(R.string.window_hook_requirement));
                }
                diagnostic.enter(Stage.ACCESS_CHECK);
                requireRoot();
                diagnostic.enter(Stage.READINESS_CHECK);
                if (!liveWindowReady("3")) {
                    diagnostic.complete(Outcome.AGENT_NOT_READY);
                    throw new IOException(UiText.get(R.string.window_service_unavailable));
                }
                diagnostic.enter(Stage.PUBLISH_PROFILES);
                publishWindowProfiles();
                diagnostic.enter(Stage.REQUEST_ROUND_TRIP);
                reply = windowRequest(reset ? "reset" : "apply", target);
            }
            WindowProfileReceipt receipt = WindowProfileReceipt.parse(reply);
            diagnostic.received(receipt.getDiagnosticOutcome());
            return receipt.getDelivery();
        }
    }

    public boolean syncWindowProfiles() throws IOException {
        try (var diagnostic = WindowDiagnostics.start(Operation.SYNC_DEFAULTS)) {
            synchronized (IO_LOCK) {
                if (!checkWindowPrerequisites(diagnostic)) return false;
                diagnostic.enter(Stage.READINESS_CHECK);
                if (!channelReady("/window", "^OVC_WINDOW_READY protocol=3 .*displayDpi=1( |$)")) {
                    diagnostic.complete(Outcome.DISPLAY_DPI_UNAVAILABLE);
                    return false;
                }
                diagnostic.enter(Stage.PUBLISH_PROFILES);
                publishWindowProfiles();
            }
            diagnostic.complete(Outcome.PROFILES_PUBLISHED);
            return true;
        }
    }

    /** Whether the running window agent offers the swipe between screens on this reviewed firmware. */
    public boolean displayMoveReady() throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured() || !windowHookEnabled(context) || !windowHookSupported() || !displayMoveReviewed()) {
                return false;
            }
            requireRoot();
            return channelReady("/window", DISPLAY_MOVE_READY);
        }
    }

    /** Asks the window agent to bring every window it moved to another screen back to the central display. */
    public boolean recallMovedWindows() throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured() || !windowHookEnabled(context) || !windowHookSupported() || !windowReviewed()) {
                return false;
            }
            requireRoot();
            if (!channelReady("/window", DISPLAY_MOVE_READY)) return false;
            try {
                return "applied".equals(new JSONObject(windowRequest("recall", "")).optString("status"));
            } catch (JSONException invalid) {
                throw new IOException(UiText.get(R.string.window_service_unavailable), invalid);
            }
        }
    }

    private boolean checkWindowPrerequisites(WindowDiagnostics.Attempt diagnostic) {
        Outcome blocked;
        if (!runtimeConfigured()) blocked = Outcome.RUNTIME_UNAVAILABLE;
        else if (!windowHookEnabled(context)) blocked = Outcome.SERVICE_DISABLED;
        else if (!windowHookSupported()) blocked = Outcome.ANDROID_UNSUPPORTED;
        else if (!windowReviewed()) blocked = Outcome.FIRMWARE_UNREVIEWED;
        else return true;
        diagnostic.complete(blocked);
        return false;
    }

    private boolean liveWindowReady(String protocolPattern) throws IOException {
        return channelReady("/window", "^OVC_WINDOW_READY protocol=" + protocolPattern + " ");
    }

    private boolean channelReady(String suffix, String marker) throws IOException {
        return "ready".equals(command(channelProbe() + "if " + readyCondition(suffix, marker) + "; then echo ready; fi"));
    }

    private String channelProbe() { return production == null ? "" : workerIdentityScript(); }

    /** A shell condition, true when the agent of [suffix] is live and has printed [marker]; needs {@link #channelProbe}. */
    private String readyCondition(String suffix, String marker) {
        String dir = base + suffix;
        String worker = production == null ? "" : " && ovc_worker_live " + dir + " && ovc_child_live " + dir
            + " && ovc_ready_fresh " + dir;
        String evidence = dir + "/ready" + (production == null ? " " + dir + "/hook.log" : "");
        return "[ -f " + dir + "/enabled ]" + worker + " && grep -Eqs " + quote(marker) + " " + evidence;
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
            VisualSession session = visualSessionState("/window");
            if (session.state().equals("uncertain")) return false;
            if (!liveWindowReady("[23]")) {
                if (session.state().equals("changed")) return clearVisualSession("/window", false, session);
                return session.state().equals("absent") && !channelMayHaveMutated("/window");
            }
            return "applied".equals(new JSONObject(windowRequest("release", "")).getString("status"))
                && clearVisualSession("/window", true, session);
        } catch (IOException | JSONException ex) {
            ControlsLog.warn("bridge_visual_session", ex, "scope", "window", "action", "release");
            return false;
        }
    }

    private void clearWindowSettings() throws IOException {
        command("settings delete global " + WINDOW_REQUEST + "; settings delete global " + WINDOW_PROFILES);
    }

    public String status() throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            String version = checkedInjectorVersion();
            StringBuilder script = new StringBuilder(production == null ? "echo 'Emulator root fixture'\n" : workerIdentityScript() + "echo 'Authenticated local root channel'\n");
            script.append("echo ").append(quote("Injector: " + version)).append("\n")
                .append("printf 'Android system process: '; pidof system_server || echo 'unavailable'\n");
            for (String suffix : CHANNELS) {
                String dir=base+suffix;
                String channel = switch (suffix) {
                    case "" -> "Launcher / panel / voice";
                    case "/window" -> "Window service";
                    case "/navigation" -> "Navigation feed";
                    case "/can" -> "CAN terminal key events";
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
    /** Whether the last check found the activation paused by the supervisor; see {@link #checkActivationPaused()}. */
    public static boolean activationPaused(Context context) {
        return context.getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean("activation_paused", false);
    }

    private void recordActivationPaused(boolean paused) {
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putBoolean("activation_paused", paused).apply();
    }

    /**
     * Whether the supervisor revoked the activation in this boot (recovery markers present). Its boot job re-arms it after
     * the next restart, so the owner is told to restart rather than to call for the PC.
     */
    public boolean checkActivationPaused() throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            String reply = command("if [ -e " + base + "/recovery.blocked ] || [ -e " + base + "/recovery.visual-pending ]; "
                + "then echo OVC_ACTIVATION=paused; else echo OVC_ACTIVATION=active; fi");
            boolean paused = reply.contains("OVC_ACTIVATION=paused");
            recordActivationPaused(paused);
            return paused;
        }
    }

    public static String lastHealth(Context context) {
        return FridaFailureText.recordedStatus(context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .getString("health", UiText.get(R.string.ui_18)));
    }

    /**
     * One `OVC_AGENT <channel> <phase> <detail>` line per running channel, read from the supervisor's own files; see
     * {@link org.openvoyah.controls.domain.setup.AgentReadiness}. Ready needs a live worker and injector, a fresh
     * heartbeat and the channel's protocol markers. The detail is the latest `OVC_*_ERROR` line, which carries codes
     * only; a stopped worker reports the last line it wrote.
     */
    public String setupReadiness() throws IOException {
        synchronized (IO_LOCK) {
            StringBuilder script = new StringBuilder("test \"$(id -u)\" = 0 || exit 1\n").append(workerIdentityScript())
                .append("ovc_agent() { printf 'OVC_AGENT\\t%s\\t%s\\t%s\\n' \"$1\" \"$2\" \"$3\"; }\n")
                .append("ovc_line() { tr -d '\\t\\r' | cut -c1-160; }\n");
            SettingsStore.State assignments = new SettingsStore(context).load();
            boolean steering = assignments.enabled && assignments.steeringActions.entrySet().stream().anyMatch(entry ->
                entry.getValue().isAssigned() && !SettingsStore.isDefaultSteering(entry.getKey(), entry.getValue()));
            for (String suffix : CHANNELS) {
                String dir = base + suffix;
                String name = suffix.isEmpty() ? "main" : suffix.substring(1);
                String pattern = switch (suffix) {
                    case "" -> "^OVC_READY NavigationBar click\\+hold ";
                    case "/window" -> "^OVC_WINDOW_READY protocol=3 ";
                    case "/navigation" -> "^OVC_NAVIGATION_READY";
                    case "/can" -> "^OVC_CAN_READY protocol=2 terminal edges ";
                    default -> "^OVC_LOCALIZATION_READY";
                };
                StringBuilder markers = new StringBuilder("grep -Eq ").append(quote(pattern)).append(" ").append(dir).append("/ready");
                if (suffix.isEmpty()) {
                    markers.append(" && grep -Eq '^OVC_VOICE_READY protocol=3( |$)' ").append(dir).append("/ready");
                    if (steering) markers.append(" && grep -Eq '^OVC_STEERING_READY protocol=2( |$)' ").append(dir).append("/ready");
                }
                script.append("if ").append(suffix.isEmpty() ? "true" : "test -f " + dir + "/enabled").append("; then\n")
                    .append("if ! ovc_worker_live ").append(dir).append("; then ovc_agent ").append(name)
                    .append(" stopped \"$(tail -n 1 ").append(dir).append("/worker.log 2>/dev/null | grep -E '^OVC_WORKER_' | ovc_line)\"\n")
                    .append("else ovc_error=$(grep -E '^OVC_[A-Z_]+_ERROR' ").append(dir).append("/hook.log 2>/dev/null | tail -n 1 | ovc_line)\n")
                    .append("ovc_target=$(cat ").append(dir).append("/target.pid 2>/dev/null)\n")
                    .append("if ! ovc_number \"$ovc_target\" || [ ! -d \"/proc/$ovc_target\" ]; then ovc_agent ").append(name).append(" waiting_target ''\n")
                    .append("elif ! ovc_child_live ").append(dir).append("; then ovc_agent ").append(name).append(" attaching \"$ovc_error\"\n")
                    .append("elif ovc_ready_fresh ").append(dir).append(" && ").append(markers).append("; then ovc_agent ").append(name).append(" ready ''\n")
                    .append("else ovc_agent ").append(name).append(" starting \"$ovc_error\"; fi\nfi\nfi\n");
            }
            return command(script.append("true\n").toString());
        }
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

    /**
     * Proves the installed APKs while the voice popup is still listening, so the first command after a start does not
     * wait for it. Read-only; a failure here is reported by the command itself.
     */
    void prepareVoice() {
        if (production == null || !runtimeConfigured()) return;
        try {
            production.prepare();
        } catch (IOException | RuntimeException ex) {
            ControlsLog.debug("bridge_voice_prepare", "outcome", "failed", "error", ex);
        }
    }

    public VoiceReply sendRecognizedVoice(String id, JSONObject options) throws IOException {
        return sendRecognizedVoice(id, options, () -> true);
    }

    VoiceReply sendRecognizedVoice(String id, JSONObject options, BooleanSupplier stillAllowed) throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured()) throw new IOException(UiText.get(R.string.ui_19));
            String token = UUID.randomUUID().toString().replace("-", "");
            String request;
            try { request = new JSONObject().put("schema",3).put("id",token).put("command",id)
                .put("confirmed",true).put("expires",SystemClock.elapsedRealtime()+12000).put("options",options).toString(); }
            catch (JSONException ex) { throw new IOException(ex); }
            if (!stillAllowed.getAsBoolean()) return new VoiceReply("blocked", UiText.get(R.string.voice_vehicle_blocked));
            // Root, activation, the live agent, the request and its reply share one root round trip and one guard.
            String script = activationGuard() + VoiceRequestScript.INSTANCE.build(channelProbe(),
                readyCondition("", "^OVC_VOICE_READY protocol=3( |$)"),
                "settings put global org.openvoyah.controls.voice " + quote(request), base + "/hook.log", token);
            String output;
            try {
                output = command(script);
            } catch (ProductionRootAccess.Refused refused) {
                // Only the guards in front of the script refuse, so nothing was published.
                throw refused;
            } catch (IOException | RuntimeException ex) {
                ControlsLog.warn("bridge_voice_request", ex, "command", id, "phase", "request");
                return unknownVoiceOutcome();
            }
            VoiceRequestScript.Outcome outcome = VoiceRequestScript.INSTANCE.outcome(output);
            if (outcome instanceof VoiceRequestScript.Outcome.NoRoot) {
                throw new IOException(UiText.get(R.string.production_broker_unavailable));
            }
            if (outcome instanceof VoiceRequestScript.Outcome.NotReady) {
                throw new IOException(UiText.get(R.string.voice_refresh_bridge));
            }
            if (outcome instanceof VoiceRequestScript.Outcome.Reply reply) return parseVoiceReply(reply.getLine(), token);
            return rejectedVoiceReply("no_result_marker");
        }
    }

    static VoiceReply parseVoiceReply(String reply, String token) {
        final String marker = "OVC_VOICE_RESULT ";
        if (!reply.startsWith(marker)) return rejectedVoiceReply("no_result_marker");
        try {
            JSONObject response = new JSONObject(reply.substring(marker.length()));
            String status = response.getString("status");
            String message = response.getString("message");
            if (!token.equals(response.getString("id"))) return rejectedVoiceReply("request_mismatch");
            if (message.length() > 512) return rejectedVoiceReply("message_too_long");
            return switch (status) {
                case "accepted", "verified", "partial", "unknown", "blocked" -> new VoiceReply(status, message);
                default -> rejectedVoiceReply("unknown_status");
            };
        } catch (JSONException ex) { return rejectedVoiceReply("malformed"); }
    }

    private static VoiceReply rejectedVoiceReply(String reason) {
        ControlsLog.warn("bridge_voice_reply", "outcome", "rejected", "reason", reason);
        return unknownVoiceOutcome();
    }

    private static VoiceReply unknownVoiceOutcome() {
        return new VoiceReply("unknown", UiText.get(R.string.production_voice_outcome_unknown));
    }

    private static ProductionRootAccess.Refused unreviewedOperation() {
        return new ProductionRootAccess.Refused(UiText.get(R.string.production_operation_unreviewed));
    }

    private static ProductionRootAccess.Refused forbiddenOperation() {
        return new ProductionRootAccess.Refused(UiText.get(R.string.action_forbidden));
    }

    /** Refuses an operation the owner forbade in Settings → Allowed actions; an unverified one is journaled. */
    private void requireReviewedOperation(String operation) throws IOException {
        if (production == null) throw unreviewedOperation();
        if (!production.operationAllowed(operation)) {
            ControlsLog.warn("action_forbidden", "action", "operation." + operation);
            throw forbiddenOperation();
        }
        production.requireActivation();
        production.recordOperationUse(operation);
    }

    public String switchDriver(String driver) throws Exception {
        synchronized (IO_LOCK) {
            OverseaProfiles.requireSlot(driver);
            if (OverseaProfiles.isProduct(android.os.Build.PRODUCT)) {
                // Inspect only. A boolean OEM return does not prove a recoverable account transition.
                throw inspectOverseaProfilesLocked();
            }
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
            if (OverseaProfiles.isProduct(android.os.Build.PRODUCT)) throw OverseaProfiles.recoveryUnavailable();
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
    private OverseaProfiles.Refused inspectOverseaProfilesLocked() throws IOException {
        requireRoot();
        if (production == null || !packageReviewed(ReviewedFirmware.LOGIN)) throw unreviewedOperation();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String asset = "hooks/oversea-profiles.bundle.js";
        upload(asset, base + "/profile-probe.template.js");
        String reply = command(activationGuard() + production.injectorGuard()
            + OverseaProfiles.probeCommand(base, injectorPath(), assetDigest(asset), nonce));
        return OverseaProfiles.refusal(reply, nonce);
    }
    public String rebootTbox() throws IOException {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.TBOX_REBOOT);
            VoiceReply parking = sendRecognizedVoice("ParkingPreflight", new JSONObject());
            if (!parking.accepted()) throw new IOException(parking.message());
            return command(activationGuard() + "dumpsys dls_tbox_service reboot");
        }
    }
    /** Settings → Restart multimedia. Touches only the packages in SystemRestartScripts.MEDIA_PACKAGES. */
    public SystemRestartScripts.MediaRestart restartMultimedia() throws IOException {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.MEDIA_RESTART);
            try (ControlsLog.Span span = ControlsLog.span("media_restart")) {
                try {
                    SystemRestartScripts.MediaRestart result = SystemRestartScripts.INSTANCE.parseMediaRestart(
                        command(activationGuard() + SystemRestartScripts.INSTANCE.mediaRestart(SystemRestartScripts.INSTANCE.getMEDIA_PACKAGES())));
                    span.field("stopped", result.getStopped().size()).field("failed", result.getFailed().size())
                        .field("center", result.getCenterBack() ? "back" : "missing");
                    if (result.getSuccessful()) span.outcome("completed"); else span.warning("incomplete");
                    return result;
                } catch (IOException | RuntimeException ex) {
                    record(span, ex);
                    throw ex;
                }
            }
        }
    }
    /** Settings → Restart the system. Refused unless the CAN agent confirms Park, like the T-Box restart. */
    public void rebootSystem() throws IOException {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.SYSTEM_REBOOT);
            VoiceReply parking = sendRecognizedVoice("ParkingPreflight", new JSONObject());
            if (!parking.accepted()) {
                ControlsLog.warn("system_reboot", "outcome", "refused", "reason", "not_parked");
                throw new ProductionRootAccess.Refused(parking.message());
            }
            String reply = command(activationGuard() + SystemRestartScripts.REBOOT);
            if (!reply.contains(SystemRestartScripts.REBOOT_SCHEDULED)) {
                ControlsLog.warn("system_reboot", "outcome", "failed", "reason", "not_scheduled");
                throw new IOException(UiText.get(R.string.ui_84));
            }
            ControlsLog.info("system_reboot", "outcome", "requested");
        }
    }
    /*
     * Settings → Factory apps language (TextOverlayManager). Only these fixed requests reach the root channel, built
     * here from the bundled text-overlays.sh: reading the state and a running job's answer needs the channel only;
     * staging a set and starting a change in /vendor/overlay is the text-overlays operation.
     */
    public String textOverlayStatus() throws IOException {
        synchronized (IO_LOCK) {
            requireTextOverlayChannel();
            return command(activationGuard() + TextOverlayCatalog.INSTANCE.status(textOverlayScript(), Build.FINGERPRINT));
        }
    }
    public String textOverlayPoll(String job) throws IOException {
        synchronized (IO_LOCK) {
            requireTextOverlayChannel();
            return command(activationGuard() + TextOverlayCatalog.INSTANCE.poll(job));
        }
    }
    public String textOverlayStage(BundledSet set, String folder) throws IOException {
        synchronized (IO_LOCK) {
            if (production == null) throw unreviewedOperation();
            requireReviewedOperation(ReviewedFirmware.TEXT_OVERLAYS);
            return command(activationGuard() + TextOverlayCatalog.INSTANCE.stage(set, folder));
        }
    }
    public String textOverlayStart(String mode, String job, java.util.List<String> packages) throws IOException {
        synchronized (IO_LOCK) {
            if (production == null) throw unreviewedOperation();
            requireReviewedOperation(ReviewedFirmware.TEXT_OVERLAYS);
            return command(activationGuard()
                + TextOverlayCatalog.INSTANCE.start(textOverlayScript(), mode, Build.FINGERPRINT, job, packages));
        }
    }
    private void requireTextOverlayChannel() throws IOException {
        if (production == null) throw unreviewedOperation();
        requireRoot();
    }
    private String textOverlayScript() throws IOException {
        try (InputStream input = context.getAssets().open("text-overlays/text-overlays.sh")) {
            // InputStream.readAllBytes needs API 33; the head units run Android 9 and 11.
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) output.write(buffer, 0, read);
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    public String clusterRoot(String script) throws IOException {
        synchronized (IO_LOCK) {
            requireReviewedOperation(ReviewedFirmware.CLUSTER_WRITE);
            if (!packageReviewed(ReviewedFirmware.CLUSTER)) throw unreviewedOperation();
            return command(activationGuard() + "set -e\n" + script);
        }
    }
    /**
     * Opens Yandex Navigator's settings screen for the traffic widget the launcher agent hosts in the home card
     * (colour theme, map scale, traffic, update period). Navigator keeps them per widget. The activity is not
     * exported, so root starts it; the widget is the one bound to the agent's host (home-navi-card.js WIDGET_HOST).
     */
    public void openNavigatorWidgetSettings() throws IOException {
        synchronized (IO_LOCK) {
            requireRoot();
            String id = command("dumpsys appwidget | awk '/^  \\[[0-9]+\\] id=/{sub(/.*id=/, \"\"); id=$0}"
                + " /hostId:" + NAVIGATOR_WIDGET_HOST + "[,}]/{print id; exit}'").trim();
            if (!id.matches("[0-9]{1,9}")) throw new IOException(UiText.get(R.string.home_navigator_widget_missing));
            command("am start --user 0 -n " + NAVIGATOR_WIDGET_SETTINGS + " --ei appWidgetId " + id + " >/dev/null");
        }
    }
    /** Weather for the launcher's home card (see HomeWeatherSource.payload); kept across reboots, aged out by the agent. */
    /** False while the agents are not configured, so the caller can try again later. */
    public boolean publishHomeWeather(String payload) throws IOException {
        synchronized (IO_LOCK) {
            if (!runtimeConfigured()) return false;
            requireRoot();
            command(activationGuard() + "settings put global " + HOME_WEATHER + " " + quote(payload));
            return true;
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
            // The journal lets a restart repeat a stop that was interrupted. A stop that cannot be journaled is
            // still carried out: giving up here would leave the agents running behind a bridge that already reads
            // as disconnected, with no way to stop them while storage keeps failing.
            if (!context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                    .putBoolean("configured", false).putBoolean("stopPending", true).commit()) {
                ControlsLog.error("bridge_journal", "key", "stopPending", "value", true, "outcome", "commit_failed");
            }
            RecoveryJobs.cancel(context);
            REVISION.incrementAndGet();
            try { stopLocked(); }
            catch(IOException | RuntimeException ex) {
                if (ex instanceof ProductionRootAccess.Refused) health(ex.getMessage());
                else {
                    health(UiText.get(R.string.ui_21) + FridaFailureText.message(ex));
                    RecoveryJobs.schedule(context);
                }
                throw ex;
            }
        }
    }
    private void stopLocked() throws IOException {
        stopLocked(true);
    }
    private void stopLocked(boolean requireAuthorization) throws IOException {
        try (ControlsLog.Span span = ControlsLog.span("bridge_stop", "for_update", !requireAuthorization)) {
            try {
                if (requireAuthorization && !knownAuthorization()) throw new ProductionRootAccess.Refused(UiText.get(R.string.production_connect_required));
                requireRoot();
                boolean iconsRestored = restorePanelIcons();
                boolean windowsRestored = releaseWindowLocked();
                if (!iconsRestored || !windowsRestored) recordVisualRecovery();
                command(stopCommand(CHANNELS));
                iconsRestored &= stoppedVisualSession("");
                windowsRestored &= stoppedVisualSession("/window");
                span.field("icons_restored", iconsRestored).field("windows_restored", windowsRestored);
                if (!iconsRestored || !windowsRestored) recordVisualRecovery();
                clearWindowSettings();
                command("settings delete global " + KEY + "; settings delete global " + PanelIcons.KEY
                    + "; settings delete global org.openvoyah.controls.voice; "
                    + "settings delete global org.openvoyah.controls.localization; settings delete global org.openvoyah.controls.clusterMedia; settings delete global " + HOME_WEATHER);
                String outcome = UiText.get(windowsRestored ? (iconsRestored ? R.string.ui_22 : R.string.panel_restore_unconfirmed)
                    : R.string.window_restore_unconfirmed);
                // The result of the stop is recorded before its journal entry is cleared, so a failure to clear
                // the entry cannot replace what the driver needs to know about the panel and the windows.
                health(outcome);
                IOException unsaved = null;
                try { stopPending(false); } catch (IOException ex) { unsaved = ex; }
                // Stopping workers does not prove that their visible changes were restored.
                if (!iconsRestored || !windowsRestored) {
                    ProductionRootAccess.Refused refused = new ProductionRootAccess.Refused(outcome);
                    if (unsaved != null) refused.addSuppressed(unsaved);
                    throw refused;
                }
                // The stop itself is complete; a restart only repeats it, which is harmless.
                if (unsaved != null) throw unsaved;
                span.outcome("stopped");
            } catch (IOException | RuntimeException ex) {
                record(span, ex);
                throw ex;
            }
        }
    }

    /** Only shutdown uses this channel without firmware activation; it cannot deploy or enable agents. */
    public static void pauseRuntimeForUpdate(Context context, String token) throws IOException {
        try (ControlsLog.Span span = ControlsLog.span("bridge_update_gate", "step", "pause")) {
            try {
                requireUpdateToken(token);
                synchronized (IO_LOCK) {
                    FridaBridge bridge = new FridaBridge(context, null, AuthorizedRootChannel.Companion.existing(context));
                    bridge.requireRoot();
                    String pending = pendingUpdate(context);
                    if (pending != null && !pending.equals(token)) throw new IOException(UiText.get(R.string.runtime_update_paused));
                    // A deployment that ran before the update starts again by itself once it is finished.
                    android.content.SharedPreferences bridgePrefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
                    boolean resume = pending != null ? bridgePrefs.getBoolean(RESUME_AFTER_UPDATE, false)
                        : wasConfigured(context) && bridgePrefs.getBoolean("productionAuthorized", false);
                    if (!bridgePrefs.edit().putBoolean(RESUME_AFTER_UPDATE, resume)
                            .putString("updatePauseToken", token).putBoolean("configured", false)
                            .putBoolean("stopPending", true).commit()) throw new IOException(UiText.get(R.string.ui_152));
                    RecoveryJobs.cancel(context);
                    REVISION.incrementAndGet();
                    bridge.stopLocked(false);
                    bridge.verifyUpdateQuiescence();
                    bridge.writeUpdateMarker(token);
                }
                span.outcome("paused");
            } catch (IOException | RuntimeException ex) {
                record(span, ex);
                throw ex;
            }
        }
    }

    /** Completing or cancelling an install leaves an explicit, freshly verified reconnect to the user. */
    public static void finishRuntimeUpdate(Context context, String token) throws IOException {
        try (ControlsLog.Span span = ControlsLog.span("bridge_update_gate", "step", "finish")) {
            try {
                requireUpdateToken(token);
                synchronized (IO_LOCK) {
                    String pending = pendingUpdate(context);
                    if (pending == null) {
                        String finished = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                            .getString("finishedUpdateToken", null);
                        if (token.equals(finished)) { span.outcome("already_finished"); return; }
                        // A journal may precede preparation. Prove shutdown before resolving that ambiguity.
                        pauseRuntimeForUpdate(context, token);
                    } else if (!token.equals(pending)) throw new IOException(UiText.get(R.string.runtime_update_paused));
                    FridaBridge bridge = new FridaBridge(context, null, AuthorizedRootChannel.Companion.existing(context));
                    bridge.requireRoot();
                    bridge.stopLocked(false);
                    bridge.verifyUpdateQuiescence();
                    String path = quote(ProductionRootAccess.BASE + "/update.pending");
                    bridge.command("set -e\nif [ -e " + path + " ] || [ -L " + path + " ]; then\n"
                        + "test ! -L " + path + " && test -f " + path
                        + " && test \"$(stat -c '%u:%a:%h' " + path + ")\" = 0:600:1"
                        + " && test \"$(cat " + path + ")\" = " + quote(token) + " || exit 77\nrm -f " + path + "\nsync\nfi");
                    boolean resume = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                        .getBoolean(RESUME_AFTER_UPDATE, false);
                    if (!context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                            .remove("updatePauseToken").remove(RESUME_AFTER_UPDATE).putString("finishedUpdateToken", token)
                            .putBoolean("configured", resume).putBoolean("stopPending", false).commit())
                        throw new IOException(UiText.get(R.string.ui_152));
                    if (resume) {
                        // The boot job verifies the deployment against this APK and redeploys what changed.
                        RecoveryJobs.schedule(context);
                        span.field("resume", true);
                    } else {
                        bridge.health(UiText.get(R.string.runtime_update_reconnect));
                    }
                }
                span.outcome("finished");
            } catch (IOException | RuntimeException ex) {
                record(span, ex);
                throw ex;
            }
        }
    }

    private static void requireUpdateToken(String token) {
        if (token == null || !token.matches("[a-f0-9-]{32,64}")) throw new IllegalArgumentException("Invalid update token");
    }

    private void writeUpdateMarker(String token) throws IOException {
        String path = quote(base + "/update.pending");
        command("set -e\ntest ! -L /data/adb && test -d /data/adb"
            + " && test \"$(stat -c '%u:%a' /data/adb)\" = 0:700 || exit 77\n"
            + "test ! -L " + base + " && test -d " + base
            + " && test \"$(stat -c '%u:%a' " + base + ")\" = 0:700 || exit 77\n"
            + "if [ -e " + path + " ] || [ -L " + path + " ]; then\n"
            + "test ! -L " + path + " && test -f " + path
            + " && test \"$(stat -c '%u:%a:%h' " + path + ")\" = 0:600:1"
            + " && test \"$(cat " + path + ")\" = " + quote(token) + " || exit 77\nelse\n"
            + "stage=$(mktemp '" + base + "/.update.XXXXXXXX')\ntrap 'rm -f \"$stage\"' EXIT\n"
            + "printf '%s\\n' " + quote(token) + " > \"$stage\"\nchmod 600 \"$stage\"\nsync\n"
            + "mv -f \"$stage\" " + path + "\nsync\nfi");
    }

    private void verifyUpdateQuiescence() throws IOException {
        StringBuilder check = new StringBuilder(workerIdentityScript());
        check.append("if ovc_scoped_process_live; then exit 78; fi\n");
        for (String suffix : CHANNELS) {
            String legacy = quote(FIXTURE_BASE + suffix + "/enabled");
            check.append("test ! -e ").append(legacy).append(" && test ! -L ").append(legacy).append(" || exit 78\n");
        }
        command(check + "test ! -e " + base + "/recovery.blocked && test ! -L " + base + "/recovery.blocked"
            + " && test ! -e " + base + "/recovery.visual-pending && test ! -L " + base + "/recovery.visual-pending || exit 78\nsync");
    }

    private boolean restorePanelIcons() throws IOException {
        VisualSession session = visualSessionState("");
        if (session.state().equals("uncertain")) return false;
        if (!channelReady("", "^OVC_PANEL_ICONS_READY( |$)")) {
            if (session.state().equals("changed")) return clearVisualSession("", false, session);
            return session.state().equals("absent") && !channelMayHaveMutated("");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String disabled = "{\"schema\":1,\"enabled\":false,\"restoreIcons\":\"" + token + "\"}";
        String log = base + "/hook.log";
        String response = command("settings put global " + KEY + " " + quote(disabled)
            + " || exit 1; for i in 0 1 2 3 4; do "
            + "if grep -qs " + quote("OVC_PANEL_ICONS_RESTORED " + token) + " " + log
            + "; then echo restored; exit 0; fi; [ \"$i\" = 4 ] || sleep 1; done; echo unconfirmed");
        return response.equals("restored") && clearVisualSession("", true, session);
    }

    private record VisualSession(String state, String proof) { }

    private VisualSession visualSessionState(String suffix) {
        String scope = visualSessionScope(suffix);
        String path = quote(base + suffix + "/visual.session");
        try {
            String state = command("if [ ! -e " + path + " ] && [ ! -L " + path + " ]; then echo absent; else "
                + fixtureEnvironment() + "/system/bin/sh " + quote(base + "/supervise.sh") + " --visual-session-state " + scope + "; fi").trim();
            if (state.equals("absent")) return new VisualSession("absent", "");
            int separator = state.indexOf(':');
            if (separator > 0) {
                String status = state.substring(0, separator), proof = state.substring(separator + 1);
                if ((status.equals("current") || status.equals("changed")) && proof.matches("[0-9a-f]{64}"))
                    return new VisualSession(status, proof);
            }
        } catch (IOException unavailable) {
            // A missing receipt cannot prove restoration.
            ControlsLog.warn("bridge_visual_session", unavailable, "scope", scope, "action", "read_state");
        }
        return new VisualSession("uncertain", "");
    }

    private boolean clearVisualSession(String suffix, boolean restored, VisualSession expected) {
        if (expected.state().equals("absent")) return true;
        String scope = visualSessionScope(suffix);
        String mode = restored ? "--visual-session-clear-restored" : "--visual-session-clear-changed";
        try {
            return command(fixtureEnvironment() + "/system/bin/sh " + quote(base + "/supervise.sh") + " " + mode + " " + scope
                + " " + quote(expected.proof()) + " && echo cleared").equals("cleared");
        } catch (IOException unavailable) {
            ControlsLog.warn("bridge_visual_session", unavailable, "scope", scope, "action",
                restored ? "clear_restored" : "clear_changed");
            return false;
        }
    }

    private static String visualSessionScope(String suffix) {
        return switch (suffix) {
            case "" -> "main";
            case "/window" -> "window";
            default -> throw new IllegalArgumentException("Unknown visual session scope");
        };
    }

    private boolean stoppedVisualSession(String suffix) {
        VisualSession session = visualSessionState(suffix);
        return session.state().equals("absent") || session.state().equals("changed")
            && clearVisualSession(suffix, false, session);
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
        script.append("if [ \"$ovc_busy\" = 0 ]; then\n");
        // A full shutdown must also account for an injector orphaned before its PID/start receipt
        // was persisted. Partial optional-channel stops must not reject the other active channels.
        if (java.util.Arrays.equals(channels, CHANNELS)) {
            script.append("ovc_scoped_process_live && exit 78\n");
        }
        return script.append("exit 0\nfi\nsleep 1\ndone\nexit 78\n").toString();
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
            ovc_owned_injector() {
                case "$1" in
                    /data/adb/open-voyah-controls/open-voyah-controls-frida-*|/data/local/tmp/open-voyah-controls-frida-*) ;;
                    *) return 1;;
                esac
                ovc_version=${1##*open-voyah-controls-frida-}
                case "$ovc_version" in ''|*[!0-9.]*|.*|*..*|*.) return 1;; esac
                return 0
            }
            ovc_scoped_process_live() {
                # Snapshot before spawning probes. A diagnostic shell's inline command is never
                # treated as a script argument, and unrelated Frida executables are outside scope.
                set -- /proc/[0-9]*
                ovc_scan_count=0
                ovc_prod_supervise=$(printf '%s' /data/adb/open-voyah-controls/supervise.sh | od -An -v -tx1 | tr -d ' \\n') || return 0
                ovc_prod_boot=$(printf '%s' /data/adb/open-voyah-controls/boot-start.sh | od -An -v -tx1 | tr -d ' \\n') || return 0
                ovc_old_supervise=$(printf '%s' /data/local/tmp/open-voyah-controls/supervise.sh | od -An -v -tx1 | tr -d ' \\n') || return 0
                ovc_old_boot=$(printf '%s' /data/local/tmp/open-voyah-controls/boot-start.sh | od -An -v -tx1 | tr -d ' \\n') || return 0
                [ -n "$ovc_prod_supervise" ] && [ -n "$ovc_prod_boot" ] &&
                    [ -n "$ovc_old_supervise" ] && [ -n "$ovc_old_boot" ] || return 0
                for ovc_scan_proc do
                    ovc_scan_pid=${ovc_scan_proc##*/}
                    ovc_number "$ovc_scan_pid" && [ -d "$ovc_scan_proc" ] || continue
                    ovc_scan_count=$((ovc_scan_count+1))
                    ovc_scan_stat=$(cat "$ovc_scan_proc/stat" 2>/dev/null) || {
                        [ ! -d "$ovc_scan_proc" ] && continue
                        return 0
                    }
                    ovc_scan_fields=$(printf '%s\\n' "${ovc_scan_stat##*) }" | awk 'NF>=20 {print $1, $7, $20}') || return 0
                    ovc_scan_state=${ovc_scan_fields%% *}
                    ovc_scan_fields=${ovc_scan_fields#* }
                    ovc_scan_flags=${ovc_scan_fields%% *}
                    ovc_scan_since=${ovc_scan_fields#* }
                    ovc_number "$ovc_scan_flags" && ovc_number "$ovc_scan_since" || return 0
                    case "$ovc_scan_state" in Z|X) continue;; esac
                    # Linux PF_KTHREAD: kernel threads have no userspace executable or argv.
                    [ "$((ovc_scan_flags & 2097152))" != 0 ] && continue
                    ovc_scan_exe=$(readlink "$ovc_scan_proc/exe" 2>/dev/null) || {
                        [ ! -d "$ovc_scan_proc" ] && continue
                        return 0
                    }
                    ovc_scan_exe=${ovc_scan_exe%" (deleted)"}
                    ovc_owned_injector "$ovc_scan_exe" && return 0
                    case "$ovc_scan_exe" in /system/bin/sh|/system/bin/mksh) ;; *) continue;; esac
                    ovc_scan_argv=$(od -An -v -tx1 "$ovc_scan_proc/cmdline" 2>/dev/null) || {
                        [ ! -d "$ovc_scan_proc" ] && continue
                        return 0
                    }
                    # Android 9 awk cannot safely evaluate the former multi-value argv matcher.
                    # Compare exact bytes only through the second NUL-delimited shell argument.
                    ovc_scan_arg=0
                    ovc_scan_value=
                    for ovc_scan_byte in $ovc_scan_argv; do
                        if [ "$ovc_scan_byte" = 00 ]; then
                            ovc_scan_arg=$((ovc_scan_arg+1))
                            if [ "$ovc_scan_arg" = 2 ]; then
                                case "$ovc_scan_value" in
                                    "$ovc_prod_supervise"|"$ovc_prod_boot"|"$ovc_old_supervise"|"$ovc_old_boot") return 0;;
                                esac
                                break
                            fi
                            ovc_scan_value=
                        elif [ "$ovc_scan_arg" = 1 ]; then
                            ovc_scan_value=$ovc_scan_value$ovc_scan_byte
                        fi
                    done
                done
                [ "$ovc_scan_count" -gt 0 ] || return 0
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
        if (recoveryChannel != null) return recoveryChannel.execute(script, "update_runtime");
        return production == null ? fixture.execute(script) : production.execute(script);
    }

    /** A refusal is a deliberate, explained stop; anything else is reported as a failure with its type. */
    private static void record(ControlsLog.Span span, Exception failure) {
        span.failed(failure);
        if (failure instanceof ProductionRootAccess.Refused) span.warning("refused");
    }
}
