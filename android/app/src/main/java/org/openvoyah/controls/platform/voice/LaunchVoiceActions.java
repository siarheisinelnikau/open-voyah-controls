package org.openvoyah.controls.platform.voice;

import android.app.Activity;
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
    private final Function<String, String> selectedApp;
    private final BooleanSupplier split;
    private final DisplayEventBus events;

    LaunchVoiceActions(Function<String, String> selectedApp, BooleanSupplier split, DisplayEventBus events) {
        this.selectedApp = selectedApp;
        this.split = split;
        this.events = events;
    }
    boolean supports(String id) {
        if (!selectedApp.apply(id).isEmpty()) return true;
        return switch (id) {
            case "ControlsSettings", "SystemSettings", "Assistant", "Split", "Music", "Navigator", "Radio", "Camera",
                "RestartMusic", "RestartNavigator" -> true;
            default -> false;
        };
    }
    String execute(Activity activity, String id) {
        if (id.equals("RestartMusic")) return restart(activity, "Music");
        if (id.equals("RestartNavigator")) return restart(activity, "Navigator");
        String component=selectedApp.apply(id);
        if(!component.isEmpty()) {
            ComponentName name=ComponentName.unflattenFromString(component);
            if(name==null)throw new IllegalArgumentException(UiText.get(R.string.ui_26));
            activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (id.equals("Navigator")) events.publishNavigation(new Navigation(name.getPackageName(), "", -1));
            return UiText.get(R.string.ui_27);
        }
        switch(id) {
            case "ControlsSettings":activity.startActivity(new Intent(activity,MainActivity.class));return UiText.get(R.string.ui_28);
            case "SystemSettings":activity.startActivity(new Intent(Settings.ACTION_SETTINGS));return UiText.get(R.string.ui_28);
            case "Assistant":activity.startActivity(new Intent(Intent.ACTION_ASSIST));return UiText.get(R.string.ui_29);
            case "Split":
                if (!split.getAsBoolean())
                    throw new IllegalStateException(UiText.get(R.string.ui_30));
                return UiText.get(R.string.ui_31);
            default:return UiText.get(R.string.ui_32);
        }
    }

    String restart(Activity activity, String role) {
        ComponentName name = ComponentName.unflattenFromString(selectedApp.apply(role));
        if (name == null || name.getPackageName().equals(activity.getPackageName())) {
            throw new IllegalStateException(UiText.get(R.string.voice_restart_assignment));
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        var target = activity.getPackageManager().resolveActivity(intent, 0);
        if (target == null || !target.activityInfo.exported || !target.activityInfo.enabled) {
            throw new IllegalStateException(UiText.get(R.string.voice_restart_assignment));
        }
        activity.startActivity(intent);
        if (role.equals("Navigator")) events.publishNavigation(new Navigation(name.getPackageName(), "", -1));
        return UiText.get(R.string.voice_restarted);
    }
}
