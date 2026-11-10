package org.openvoyah.controls;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.openvoyah.controls.core.Action;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InstalledApps {
    public record Entry(ComponentName component, String label) {
        public Intent intent() { return launchIntent().setComponent(component); }
        Action binding() { return new Action(Action.Kind.LAUNCH_APP, component.flattenToString(), label); }
    }

    private InstalledApps() { }

    static Intent launchIntent() { return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER); }

    public static List<Entry> list(Context context) {
        PackageManager packages = context.getPackageManager();
        List<Entry> apps = new ArrayList<>();
        for (ResolveInfo match : packages.queryIntentActivities(launchIntent(), 0)) {
            if (match.activityInfo == null || !match.activityInfo.exported || !match.activityInfo.enabled) continue;
            ComponentName component = new ComponentName(match.activityInfo.packageName, match.activityInfo.name);
            apps.add(new Entry(component, match.loadLabel(packages).toString()));
        }
        apps.sort(Comparator.comparing(Entry::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(app -> app.component().flattenToString()));
        return apps;
    }
}
