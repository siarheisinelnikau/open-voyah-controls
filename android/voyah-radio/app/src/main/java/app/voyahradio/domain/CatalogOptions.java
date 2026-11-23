package app.voyahradio.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
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
    private final Map<String, Integer> favoriteRanks = new HashMap<>();

    public CatalogOptions(Set<RadioTab> hiddenTabs, Set<String> hiddenStations,
                          Set<String> unavailableStations, boolean hideUnavailable,
                          List<String> order, List<String> favoriteOrder) {
        this.hiddenTabs = Collections.unmodifiableSet(new HashSet<>(hiddenTabs));
        this.hiddenStations = Collections.unmodifiableSet(new HashSet<>(hiddenStations));
        this.unavailableStations = Collections.unmodifiableSet(new HashSet<>(unavailableStations));
        this.hideUnavailable = hideUnavailable;
        for (int index = 0; index < order.size(); index++) ranks.putIfAbsent(order.get(index), index);
        for (int index = 0; index < favoriteOrder.size(); index++) {
            favoriteRanks.putIfAbsent(favoriteOrder.get(index), index);
        }
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
        // Older builds saved every group even when only one group was reordered.
        // Only groups with a change in the relative order of saved stations need an override.
        Map<RadioTab, Integer> lastRank = new EnumMap<>(RadioTab.class);
        Set<RadioTab> changedGroups = EnumSet.noneOf(RadioTab.class);
        for (Station station : stations) {
            Integer rank = ranks.get(station.id);
            if (rank == null) continue;
            Integer previous = lastRank.put(station.group, rank);
            if (previous != null && rank < previous) changedGroups.add(station.group);
        }
        for (RadioTab group : changedGroups) {
            List<Station> orderedGroup = new ArrayList<>();
            for (Station station : stations) {
                if (station.group == group) orderedGroup.add(station);
            }
            orderedGroup.sort(Comparator.comparingInt(station -> ranks.getOrDefault(station.id, Integer.MAX_VALUE)));
            ArrayDeque<Station> remaining = new ArrayDeque<>(orderedGroup);
            for (int index = 0; index < stations.size(); index++) {
                if (stations.get(index).group == group) sorted.set(index, remaining.removeFirst());
            }
        }
        return sorted;
    }

    public List<Station> visibleStations(List<Station> stations, RadioTab tab, Set<String> favorites) {
        List<Station> visible = stationsForTab(stations, tab, favorites);
        visible.removeIf(station -> !shows(station));
        return visible;
    }

    public List<Station> stationsForTab(List<Station> stations, RadioTab tab, Set<String> favorites) {
        List<Station> matching = new ArrayList<>();
        for (Station station : ordered(stations)) {
            if (tab.contains(station, favorites)) matching.add(station);
        }
        if (tab == RadioTab.FAVORITES) {
            matching.sort(Comparator.comparingInt(station -> favoriteRanks.getOrDefault(station.id, Integer.MAX_VALUE)));
        }
        return matching;
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
