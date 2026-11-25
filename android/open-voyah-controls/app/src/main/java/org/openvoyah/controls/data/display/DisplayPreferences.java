package org.openvoyah.controls.data.display;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.openvoyah.controls.core.display.DisplaySettings;

public final class DisplayPreferences implements DisplaySettings {
    /**
     * The retired "Show current track on the instrument cluster" switch. No Voyah cluster service forwards the track
     * it sent (see docs/CLUSTER.md); a saved value is dropped when settings are restored.
     */
    private static final String RETIRED_CLUSTER_MEDIA = "clusterMedia";
    private static final String DENSITY = "density";
    private final SharedPreferences preferences;

    public DisplayPreferences(Context context) {
        preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE);
    }

    @Override public int density() { return preferences.getInt(DENSITY, 0); }
    @Override public void density(int value) { preferences.edit().putInt(DENSITY, value).apply(); }

    public static boolean validDensity(int value) {
        return value == 0 || value == 160 || value == 200 || value == 240 || value == 320;
    }

    public void restore(int density) throws IOException {
        if (!preferences.edit().putInt(DENSITY, density).remove(RETIRED_CLUSTER_MEDIA).commit())
            throw new IOException(UiText.get(R.string.settings_archive_save_failed));
    }
}
