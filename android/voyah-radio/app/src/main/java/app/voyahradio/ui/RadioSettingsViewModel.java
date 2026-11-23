package app.voyahradio.ui;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import app.voyahradio.data.RadioCatalog;
import app.voyahradio.data.RadioPreferences;
import app.voyahradio.data.StreamProbe;
import app.voyahradio.domain.Availability;
import app.voyahradio.domain.CatalogOptions;
import app.voyahradio.domain.RadioTab;
import app.voyahradio.domain.Station;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class RadioSettingsViewModel {
    enum ScanStatus { IDLE, RUNNING, DONE, NO_NETWORK }

    static final class State {
        final RadioTab group;
        final String language;
        final String theme;
        final String visualization;
        final boolean smoothVisualization;
        final CatalogOptions options;
        final List<Station> stations;
        final ScanStatus scan;
        final int checked;
        final int total;
        final int unavailable;
        final int unknown;

        State(RadioTab group, String language, String theme, String visualization,
              boolean smoothVisualization, CatalogOptions options, List<Station> stations,
              ScanStatus scan, int checked, int total, int unavailable, int unknown) {
            this.group = group;
            this.language = language;
            this.theme = theme;
            this.visualization = visualization;
            this.smoothVisualization = smoothVisualization;
            this.options = options;
            this.stations = Collections.unmodifiableList(stations);
            this.scan = scan;
            this.checked = checked;
            this.total = total;
            this.unavailable = unavailable;
            this.unknown = unknown;
        }
    }

    private final RadioCatalog catalog;
    private final RadioPreferences preferences;
    private final StreamProbe probe;
    private final Consumer<State> render;
    private final Handler main = new Handler(Looper.getMainLooper());
    private RadioTab group;
    private ExecutorService executor;
    private ScanStatus scan = ScanStatus.IDLE;
    private int generation;
    private int checked;
    private int unavailable;
    private int unknown;

    RadioSettingsViewModel(RadioCatalog catalog, RadioPreferences preferences, StreamProbe probe,
                           RadioTab group, Consumer<State> render) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.probe = probe;
        this.group = group;
        this.render = render;
    }

    State state() {
        CatalogOptions options = preferences.catalogOptions();
        List<Station> stations = options.stationsForTab(catalog.all(), group, preferences.favorites());
        return new State(group, preferences.language(), preferences.theme(), preferences.visualization(),
                preferences.smoothVisualization(), options, stations, scan, checked, catalog.all().size(),
                unavailable, unknown);
    }

    void selectGroup(RadioTab selected) {
        if (group == selected) return;
        group = selected;
        publish();
    }

    boolean setLanguage(String language) {
        if (preferences.language().equals(language)) return false;
        preferences.setLanguage(language);
        return true;
    }

    boolean setTheme(String theme) {
        if (preferences.theme().equals(theme)) return false;
        preferences.setTheme(theme);
        return true;
    }

    void setVisualization(String mode) {
        preferences.setVisualization(mode);
        publish();
    }

    void setSmoothVisualization(boolean enabled) {
        preferences.setSmoothVisualization(enabled);
        publish();
    }

    boolean showTab(RadioTab tab, boolean visible) {
        Set<RadioTab> hidden = EnumSet.noneOf(RadioTab.class);
        hidden.addAll(preferences.catalogOptions().hiddenTabs);
        if (visible) hidden.remove(tab);
        else hidden.add(tab);
        if (hidden.size() == RadioTab.values().length) {
            publish();
            return false;
        }
        preferences.saveHiddenTabs(hidden);
        publish();
        return true;
    }

    void showStation(Station station, boolean visible) {
        Set<String> hidden = new HashSet<>(preferences.catalogOptions().hiddenStations);
        if (visible) hidden.remove(station.id);
        else hidden.add(station.id);
        preferences.saveHiddenStations(hidden);
        publish();
    }

    void move(Station station, int direction) {
        CatalogOptions options = preferences.catalogOptions();
        if (group == RadioTab.FAVORITES) {
            List<Station> stations = options.stationsForTab(catalog.all(), group, preferences.favorites());
            int index = stations.indexOf(station);
            int destination = index + direction;
            if (index < 0 || destination < 0 || destination >= stations.size()) return;
            Collections.swap(stations, index, destination);
            List<String> order = new ArrayList<>(stations.size());
            for (Station favorite : stations) order.add(favorite.id);
            preferences.saveFavoriteOrder(order);
        } else {
            preferences.saveOrder(options.move(catalog.all(), station, direction));
        }
        publish();
    }

    void hideUnavailable(boolean enabled) {
        preferences.setHideUnavailable(enabled);
        publish();
    }

    void toggleCheck() {
        if (scan == ScanStatus.RUNNING) {
            cancelCheck();
            scan = ScanStatus.IDLE;
            publish();
            return;
        }
        Network network = probe.validatedNetwork();
        if (network == null) {
            scan = ScanStatus.NO_NETWORK;
            publish();
            return;
        }
        int token = ++generation;
        checked = 0;
        unavailable = 0;
        unknown = 0;
        scan = ScanStatus.RUNNING;
        executor = Executors.newFixedThreadPool(4);
        for (Station station : catalog.all()) {
            executor.execute(() -> {
                long observed = System.currentTimeMillis();
                Availability result = probe.check(station, network);
                main.post(() -> acceptCheck(token, station, result, observed, network));
            });
        }
        publish();
    }

    private void acceptCheck(int token, Station station, Availability result, long observed, Network network) {
        if (token != generation) return;
        if (!probe.isCurrent(network)) {
            cancelCheck();
            scan = ScanStatus.NO_NETWORK;
            publish();
            return;
        }
        preferences.recordAvailability(station.id, result, observed);
        if (result == Availability.UNAVAILABLE) unavailable++;
        else if (result == Availability.UNKNOWN) unknown++;
        if (++checked == catalog.all().size()) {
            cancelCheck();
            scan = ScanStatus.DONE;
        }
        publish();
    }

    private void publish() {
        render.accept(state());
    }

    private void cancelCheck() {
        generation++;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    void close() {
        cancelCheck();
        main.removeCallbacksAndMessages(null);
    }
}
