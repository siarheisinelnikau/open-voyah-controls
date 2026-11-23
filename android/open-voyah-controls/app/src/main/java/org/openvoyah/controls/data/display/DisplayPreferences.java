package org.openvoyah.controls.data.display;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.display.DisplaySettings;

public final class DisplayPreferences implements DisplaySettings {
    private static final String CLUSTER_MEDIA = "clusterMedia";
    private static final String DENSITY = "density";
    private final SharedPreferences preferences;

    public DisplayPreferences(Context context) {
        preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE);
    }

    @Override public int density() { return preferences.getInt(DENSITY, 0); }
    @Override public void density(int value) { preferences.edit().putInt(DENSITY, value).apply(); }
    @Override public boolean clusterMedia() { return preferences.getBoolean(CLUSTER_MEDIA, false); }
    @Override public void clusterMedia(boolean enabled) { preferences.edit().putBoolean(CLUSTER_MEDIA, enabled).apply(); }

    public static boolean validDensity(int value) {
        return value == 0 || value == 160 || value == 200 || value == 240 || value == 320;
    }

    public void restore(int density, boolean clusterMedia) throws IOException {
        if (!preferences.edit().putInt(DENSITY, density).putBoolean(CLUSTER_MEDIA, clusterMedia).commit())
            throw new IOException(UiText.get(R.string.settings_archive_save_failed));
    }

    public Runnable observeClusterMedia(Runnable callback) {
        SharedPreferences.OnSharedPreferenceChangeListener listener = (store, key) -> {
            if (CLUSTER_MEDIA.equals(key)) callback.run();
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
        return () -> preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
