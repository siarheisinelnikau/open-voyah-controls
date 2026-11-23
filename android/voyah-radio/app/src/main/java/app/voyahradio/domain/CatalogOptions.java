package app.voyahradio.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CatalogOptions {
    public final Set<RadioTab> hiddenTabs;
    public final Set<String> hiddenStations;
    public final Set<String> unavailableStations;
    public final boolean hideUnavailable;
    private final Map<String, Integer> ranks = new HashMap<>();

    public CatalogOptions(Set<RadioTab> hiddenTabs, Set<String> hiddenStations,
                          Set<String> unavailableStations, boolean hideUnavailable,
                          List<String> order) {
        this.hiddenTabs = Collections.unmodifiableSet(new HashSet<>(hiddenTabs));
        this.hiddenStations = Collections.unmodifiableSet(new HashSet<>(hiddenStations));
        this.unavailableStations = Collections.unmodifiableSet(new HashSet<>(unavailableStations));
        this.hideUnavailable = hideUnavailable;
        for (int index = 0; index < order.size(); index++) ranks.putIfAbsent(order.get(index), index);
    }

    public RadioTab visibleTab(RadioTab preferred) {
        if (!hiddenTabs.contains(preferred)) return preferred;
        for (RadioTab tab : RadioTab.values()) {
            if (!hiddenTabs.contains(tab)) return tab;
        }
        return RadioTab.BELARUS;
    }

    public boolean shows(Station station) {
        return !hiddenStations.contains(station.id)
                && !(hideUnavailable && unavailableStations.contains(station.id));
    }

    public List<Station> ordered(List<Station> stations) {
        List<Station> sorted = new ArrayList<>(stations);
        sorted.sort(Comparator.comparingInt(station -> ranks.getOrDefault(station.id, Integer.MAX_VALUE)));
        return sorted;
    }

    public List<Station> visibleStations(List<Station> stations, RadioTab tab, Set<String> favorites) {
        List<Station> visible = new ArrayList<>();
        for (Station station : ordered(stations)) {
            if (tab.contains(station, favorites) && shows(station)) visible.add(station);
        }
        return visible;
    }

    public List<String> move(List<Station> stations, Station station, int direction) {
        List<Station> sorted = ordered(stations);
        int from = sorted.indexOf(station);
        if (from >= 0) {
            for (int to = from + direction; to >= 0 && to < sorted.size(); to += direction) {
                if (sorted.get(to).group == station.group) {
                    Collections.swap(sorted, from, to);
                    break;
                }
            }
        }
        List<String> order = new ArrayList<>(sorted.size());
        for (Station item : sorted) order.add(item.id);
        return order;
    }
}
