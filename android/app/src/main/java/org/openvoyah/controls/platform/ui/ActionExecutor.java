package org.openvoyah.controls.platform.ui;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;
import org.openvoyah.controls.MainActivity;
import org.openvoyah.controls.QuickVoiceActivity;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.ButtonEngine;

public final class ActionExecutor implements ButtonEngine.Sink {
    private final Context context;
    public ActionExecutor(Context context) { this.context = context; }
    @Override public boolean canExecute(Action action) {
        if (!action.isAssigned()) return false;
        Intent intent;
        switch (action.kind) {
            case START_VOICE: intent = QuickVoiceActivity.intent(context); break;
            case CONTROLS_SETTINGS: intent = MainActivity.settingsIntent(context); break;
            case CONTROLS_APPS: intent = MainActivity.appsIntent(context); break;
            case SYSTEM_SETTINGS: intent = new Intent(Settings.ACTION_SETTINGS); break;
            case LAUNCH_APP:
                ComponentName component = ComponentName.unflattenFromString(action.component);
                if (component == null) return false;
                intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component);
                break;
            default: return false;
        }
        try {
            var resolved = context.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null || resolved.activityInfo == null) return false;
            var info = resolved.activityInfo;
            return info.enabled && info.applicationInfo.enabled
                && (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) == 0
                && (info.packageName.equals(context.getPackageName()) || info.exported)
                && (info.permission == null || context.checkSelfPermission(info.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        } catch (RuntimeException ex) { return false; }
    }
    public void execute(Action action) {
        if (!action.isAssigned()) return;
        try {
            Intent intent;
            switch (action.kind) {
                case START_VOICE:
                    context.startActivity(QuickVoiceActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                case LAUNCH_APP:
                    ComponentName component = ComponentName.unflattenFromString(action.component);
                    if (component == null) throw new ActivityNotFoundException();
                    intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component);
                    break;
                case SYSTEM_SETTINGS: intent = new Intent(Settings.ACTION_SETTINGS); break;
                case CONTROLS_SETTINGS: intent = MainActivity.settingsIntent(context); break;
                case CONTROLS_APPS: intent = MainActivity.appsIntent(context); break;
                default: return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(context, UiText.get(R.string.ui_0) + action.label + UiText.get(R.string.ui_1), Toast.LENGTH_LONG).show();
        }
    }
}
