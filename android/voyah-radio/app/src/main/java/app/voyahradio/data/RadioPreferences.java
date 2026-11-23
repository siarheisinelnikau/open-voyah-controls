package app.voyahradio.data;

import android.content.Context;
import android.content.SharedPreferences;

import app.voyahradio.domain.Availability;
import app.voyahradio.domain.CatalogOptions;
import app.voyahradio.domain.RadioTab;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class RadioPreferences {
    private static final String AVAILABILITY_PREFIX = "availability.";
    private static final long AVAILABILITY_MAX_AGE = TimeUnit.HOURS.toMillis(24);
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

    public String language() {
        return prefs.getString("language", "ru");
    }

    public void setLanguage(String language) {
        prefs.edit().putString("language", language).apply();
    }

    public String theme() {
        return prefs.getString("theme", "graphite");
    }

    public void setTheme(String theme) {
        prefs.edit().putString("theme", theme).apply();
    }

    public String visualization() {
        return prefs.getString("visualization", "spectrum");
    }

    public void setVisualization(String mode) {
        prefs.edit().putString("visualization", mode).apply();
    }

    public CatalogOptions catalogOptions() {
        Set<RadioTab> hiddenTabs = EnumSet.noneOf(RadioTab.class);
        for (String key : prefs.getStringSet("hidden_tabs", Collections.emptySet())) {
            RadioTab tab = RadioTab.parse(key, null);
            if (tab != null) hiddenTabs.add(tab);
        }
        Set<String> hiddenStations = prefs.getStringSet("hidden_stations", Collections.emptySet());
        Set<String> unavailableStations = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(AVAILABILITY_PREFIX) || !(entry.getValue() instanceof Long value)) {
                continue;
            }
            long age = now + value;
            if (value < 0 && age >= 0 && age < AVAILABILITY_MAX_AGE) {
                unavailableStations.add(entry.getKey().substring(AVAILABILITY_PREFIX.length()));
            }
        }
        String storedOrder = prefs.getString("station_order", "");
        List<String> order = storedOrder.isEmpty() ? Collections.emptyList() : Arrays.asList(storedOrder.split(","));
        return new CatalogOptions(hiddenTabs, hiddenStations, unavailableStations,
                prefs.getBoolean("hide_unavailable", false), order);
    }

    public void saveHiddenTabs(Set<RadioTab> tabs) {
        Set<String> keys = new HashSet<>();
        for (RadioTab tab : tabs) keys.add(tab.name());
        prefs.edit().putStringSet("hidden_tabs", keys).apply();
    }

    public void saveHiddenStations(Set<String> ids) {
        prefs.edit().putStringSet("hidden_stations", new HashSet<>(ids)).apply();
    }

    public void saveOrder(List<String> ids) {
        prefs.edit().putString("station_order", String.join(",", ids)).apply();
    }

    public void setHideUnavailable(boolean enabled) {
        prefs.edit().putBoolean("hide_unavailable", enabled).apply();
    }

    public void recordAvailability(String id, Availability availability, long observedAt) {
        if (availability == Availability.UNKNOWN) return;
        String key = AVAILABILITY_PREFIX + id;
        if (observedAt < Math.abs(prefs.getLong(key, 0))) return;
        prefs.edit().putLong(key, availability == Availability.AVAILABLE ? observedAt : -observedAt).apply();
    }
}
