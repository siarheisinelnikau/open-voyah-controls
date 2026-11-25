package org.openvoyah.controls.platform.voice;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.*;
import android.provider.Settings;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.openvoyah.controls.MainActivity;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.display.DisplayEventBus.Navigation;
import org.openvoyah.controls.core.display.DisplayEventBus;

final class LaunchVoiceActions {
    record Result(String message, boolean requested) { }

    static final class AssignmentRequired extends IllegalStateException {
        AssignmentRequired() { super(UiText.get(R.string.voice_restart_assignment)); }
    }

    private final Function<String, String> selectedApp;
    private final BooleanSupplier split;
    private final DisplayEventBus events;
    private final Function<Activity, Context> origin;

    LaunchVoiceActions(Function<String, String> selectedApp, BooleanSupplier split, DisplayEventBus events) {
        this(selectedApp, split, events, Activity::getApplicationContext);
    }

    /** {@code origin} gives the context that starts other apps for a screen: its application outside tests. */
    LaunchVoiceActions(Function<String, String> selectedApp, BooleanSupplier split, DisplayEventBus events,
                       Function<Activity, Context> origin) {
        this.selectedApp = selectedApp;
        this.split = split;
        this.events = events;
        this.origin = origin;
    }
    boolean supports(String id) {
        if (!selectedApp.apply(id).isEmpty()) return true;
        return switch (id) {
            case "ControlsSettings", "SystemSettings", "Assistant", "Split", "Music", "Navigator", "Radio", "Camera",
                "RestartMusic", "RestartNavigator" -> true;
            default -> false;
        };
    }
    Result execute(Activity activity, String id) {
        if (id.equals("RestartMusic")) return new Result(restart(activity, "Music"), true);
        if (id.equals("RestartNavigator")) return new Result(restart(activity, "Navigator"), true);
        String component=selectedApp.apply(id);
        if(!component.isEmpty()) {
            ComponentName name=ComponentName.unflattenFromString(component);
            if(name==null)throw new IllegalArgumentException(UiText.get(R.string.ui_26));
            startApp(activity, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (id.equals("Navigator")) events.publishNavigation(Navigation.opened(name.getPackageName()));
            return requested(R.string.ui_27);
        }
        switch(id) {
            case "ControlsSettings":activity.startActivity(MainActivity.settingsIntent(activity));return requested(R.string.ui_28);
            case "SystemSettings":activity.startActivity(new Intent(Settings.ACTION_SETTINGS));return requested(R.string.ui_28);
            case "Assistant":activity.startActivity(new Intent(Intent.ACTION_ASSIST));return requested(R.string.ui_29);
            case "Split":
                if (!split.getAsBoolean())
                    throw new IllegalStateException(UiText.get(R.string.ui_30));
                return requested(R.string.ui_31);
            default:return new Result(UiText.get(R.string.ui_32), false);
        }
    }

    String restart(Activity activity, String role) {
        ComponentName name = ComponentName.unflattenFromString(selectedApp.apply(role));
        if (name == null || name.getPackageName().equals(activity.getPackageName())) {
            throw new AssignmentRequired();
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        var target = activity.getPackageManager().resolveActivity(intent, 0);
        if (target == null || !target.activityInfo.exported || !target.activityInfo.enabled) {
            throw new AssignmentRequired();
        }
        startApp(activity, intent);
        if (role.equals("Navigator")) events.publishNavigation(Navigation.opened(name.getPackageName()));
        return UiText.get(R.string.voice_restarted);
    }

    /**
     * Starts another app's task the way the launcher panel does: from the application, on the screen's display.
     *
     * Started by the voice popup itself, the app is the popup's launch. The window service gives such a launch
     * freeform bounds beside the panel, and the popup's task stays visible and resumed under a freeform task, so
     * Android 11 does not wait for a pause: an app whose process already runs (Voyah Music, kept alive by the media
     * card) starts at once in freeform and draws Android's caption bar (maximize and close). The bar stays after
     * the service moves the task to multi-window with the same resource sizes. Without a calling activity Android
     * opens the task full screen, pauses the popup under it first, and the service places the task beside the
     * panel, normally before the app is started in it.
     */
    private void startApp(Activity screen, Intent intent) {
        @SuppressWarnings("deprecation")
        int display = screen.getWindowManager().getDefaultDisplay().getDisplayId();
        origin.apply(screen).startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
    }

    private static Result requested(int label) { return new Result(UiText.get(label), true); }
}
