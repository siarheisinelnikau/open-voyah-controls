package app.voyahradio.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class RadioPreferences {
    private final SharedPreferences prefs;

    public RadioPreferences(Context context) {
        prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE);
    }

    public String lastStation() {
        return prefs.getString("last_station", "");
    }

    public void rememberStation(String id) {
        prefs.edit().putString("last_station", id).apply();
    }

    public Set<String> favorites() {
        return new HashSet<>(prefs.getStringSet("favorites", Collections.emptySet()));
    }

    public void saveFavorites(Set<String> ids) {
        prefs.edit().putStringSet("favorites", new HashSet<>(ids)).apply();
    }
}
