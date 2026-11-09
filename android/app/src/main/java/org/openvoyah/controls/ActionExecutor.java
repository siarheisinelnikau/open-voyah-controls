package org.openvoyah.controls;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;
import org.openvoyah.controls.core.Action;

final class ActionExecutor {
    private final Context context;
    ActionExecutor(Context context) { this.context = context; }
    void execute(Action action) {
        if (!action.isAssigned()) return;
        try {
            Intent intent;
            switch (action.kind) {
                case LAUNCH_APP:
                    ComponentName component = ComponentName.unflattenFromString(action.component);
                    if (component == null) throw new ActivityNotFoundException();
                    intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component);
                    break;
                case SYSTEM_SETTINGS: intent = new Intent(Settings.ACTION_SETTINGS); break;
                case CONTROLS_SETTINGS: intent = new Intent(context, MainActivity.class); break;
                default: return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(context, UiText.get(R.string.ui_0) + action.label + UiText.get(R.string.ui_1), Toast.LENGTH_LONG).show();
        }
    }
}
