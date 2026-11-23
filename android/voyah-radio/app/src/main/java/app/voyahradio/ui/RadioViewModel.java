package app.voyahradio.ui;

import app.voyahradio.data.RadioCatalog;
import app.voyahradio.data.RadioPreferences;
import app.voyahradio.domain.CatalogOptions;
import app.voyahradio.domain.Playback;
import app.voyahradio.domain.RadioTab;
import app.voyahradio.domain.Station;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class RadioViewModel {
    interface Controls {
        void play(String id, RadioTab tab);
        void pause();
    }

    static final class State {
        final RadioTab tab;
        final Set<RadioTab> hiddenTabs;
        final Set<String> unavailable;
        final String query;
        final List<Station> stations;
        final Set<String> favorites;
        final Station selected;
        final Playback playback;

        State(RadioTab tab, CatalogOptions options, String query, List<Station> stations, Set<String> favorites,
              Station selected, Playback playback) {
            this.tab = tab;
            this.hiddenTabs = options.hiddenTabs;
            this.unavailable = options.unavailableStations;
            this.query = query;
            this.stations = Collections.unmodifiableList(stations);
            this.favorites = Collections.unmodifiableSet(favorites);
            this.selected = selected;
            this.playback = playback;
        }
    }

    private final RadioCatalog catalog;
    private final RadioPreferences preferences;
    private final Controls controls;
    private final Consumer<State> render;
    private RadioTab tab;
    private String query;
    private Playback playback;

    RadioViewModel(RadioCatalog catalog, RadioPreferences preferences, Controls controls,
                   RadioTab tab, String query, Consumer<State> render) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.controls = controls;
        this.tab = tab;
        this.query = query;
        this.render = render;
        playback = new Playback(preferences.lastStation(), Playback.Status.READY);
    }

    State state() {
        CatalogOptions options = preferences.catalogOptions();
        RadioTab visibleTab = options.visibleTab(tab);
        Set<String> favorites = preferences.favorites();
        List<Station> available = new ArrayList<>();
        String[] words = Station.searchWords(query);
        for (Station station : options.visibleStations(catalog.all(), visibleTab, favorites)) {
            if (station.matches(words)) available.add(station);
        }
        Station selected = catalog.find(playback.stationId);
        if (selected == null || (!options.shows(selected) && !playback.active() && !playback.failed())) {
            selected = available.isEmpty() ? firstVisible(options, favorites) : available.get(0);
        }
        return new State(visibleTab, options, query, available, favorites, selected, playback);
    }

    private Station firstVisible(CatalogOptions options, Set<String> favorites) {
        for (RadioTab category : RadioTab.values()) {
            if (options.hiddenTabs.contains(category)) continue;
            List<Station> visible = options.visibleStations(catalog.all(), category, favorites);
            if (!visible.isEmpty()) return visible.get(0);
        }
        return null;
    }

    void refresh() {
        State state = state();
        tab = state.tab;
        render.accept(state);
    }

    void onPlayback(Playback updated) {
        playback = updated;
        render.accept(state());
    }

    void chooseTab(RadioTab tab) {
        if (this.tab == tab) return;
        this.tab = tab;
        render.accept(state());
    }

    void search(String query) {
        if (this.query.equals(query)) return;
        this.query = query;
        render.accept(state());
    }

    void select(Station station) {
        if (station != null) controls.play(station.id, state().tab);
    }

    void togglePlayback() {
        if (playback.active()) controls.pause();
        else select(state().selected);
    }

    void toggleFavorite(Station station) {
        Set<String> favorites = preferences.favorites();
        if (!favorites.remove(station.id)) favorites.add(station.id);
        preferences.saveFavorites(favorites);
        render.accept(state());
    }

    void step(int direction) {
        State state = state();
        List<Station> stations = state.stations;
        if (stations.isEmpty()) return;
        int current = stations.indexOf(state.selected);
        int index = current < 0
                ? (direction > 0 ? 0 : stations.size() - 1)
                : Math.floorMod(current + direction, stations.size());
        select(stations.get(index));
    }
}
