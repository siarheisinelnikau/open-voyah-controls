package org.openvoyah.controls;
import android.app.Activity;
import android.content.*;
import android.provider.Settings;
import java.util.Set;

final class LaunchVoiceActions implements LocalVoiceAction {
    private final Activity activity;
    private final VoiceSettings settings;
    LaunchVoiceActions(Activity activity,VoiceSettings settings) { this.activity=activity;this.settings=settings; }
    @Override public boolean supports(String id) {
        return !settings.app(id).isEmpty() || Set.of("ControlsSettings","SystemSettings","Assistant","Split","Music","Navigator","Radio","Camera").contains(id);
    }
    @Override public String execute(String id) {
        String component=settings.app(id);
        if(!component.isEmpty()) {
            ComponentName name=ComponentName.unflattenFromString(component);
            if(name==null)throw new IllegalArgumentException(UiText.get(R.string.ui_26));
            activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(name));
            return UiText.get(R.string.ui_27);
        }
        switch(id) {
            case "ControlsSettings":activity.startActivity(new Intent(activity,MainActivity.class));return UiText.get(R.string.ui_28);
            case "SystemSettings":activity.startActivity(new Intent(Settings.ACTION_SETTINGS));return UiText.get(R.string.ui_28);
            case "Assistant":activity.startActivity(new Intent(Intent.ACTION_ASSIST));return UiText.get(R.string.ui_29);
            case "Split":
                ButtonAccessibilityService service=ButtonAccessibilityService.connected();
                if(service==null || !service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN))
                    throw new IllegalStateException(UiText.get(R.string.ui_30));
                return UiText.get(R.string.ui_31);
            default:return UiText.get(R.string.ui_32);
        }
    }
}
