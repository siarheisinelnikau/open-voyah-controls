package app.voyahradio.domain;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class RadioOptionsTest {
    private final Station radius = station("radius", RadioTab.BELARUS);
    private final Station fourYou = station("4you", RadioTab.BELARUS);
    private final Station europa = station("europa", RadioTab.RUSSIA);
    private final List<Station> stations = List.of(radius, europa, fourYou);

    @Test public void customOrderAndHiddenStationsApplyToFavoritesAndCountry() {
        CatalogOptions settings = new CatalogOptions(Set.of(RadioTab.INTERNATIONAL), Set.of(),
                Set.of(), false, List.of());
        List<String> order = settings.move(stations, fourYou, -1);
        CatalogOptions ordered = new CatalogOptions(settings.hiddenTabs, Set.of(radius.id), Set.of(),
                false, order);

        assertEquals(List.of(fourYou, europa, radius), ordered.ordered(stations));
        assertEquals(RadioTab.BELARUS, ordered.visibleTab(RadioTab.INTERNATIONAL));
        assertEquals(List.of(fourYou), ordered.visibleStations(stations, RadioTab.BELARUS, Set.of()));
        assertEquals(List.of(fourYou, europa), ordered.visibleStations(stations, RadioTab.FAVORITES,
                Set.of(radius.id, europa.id, fourYou.id)));
    }

    @Test public void unknownStreamOrNetworkFailureCannotHideAStation() {
        assertEquals(Availability.UNKNOWN, Availability.UNAVAILABLE.merge(Availability.UNKNOWN));
        assertEquals(Availability.AVAILABLE, Availability.UNKNOWN.merge(Availability.AVAILABLE));
        for (int status : new int[]{0, 403, 429, 500, 502, 503, 504}) {
            assertFalse(Availability.isDefinitiveHttpFailure(status));
        }
        assertTrue(Availability.isDefinitiveHttpFailure(404));
        assertTrue(Availability.isDefinitiveHttpFailure(410));

        CatalogOptions filtered = new CatalogOptions(Set.of(), Set.of(), Set.of(fourYou.id), true, List.of());
        assertEquals(List.of(radius), filtered.visibleStations(stations, RadioTab.BELARUS, Set.of()));
        CatalogOptions unfiltered = new CatalogOptions(Set.of(), Set.of(), Set.of(fourYou.id), false, List.of());
        assertEquals(List.of(radius, fourYou), unfiltered.visibleStations(stations, RadioTab.BELARUS, Set.of()));
    }

    @Test public void radioMetadataSeparatesArtistAndTitle() {
        Track track = Track.from("Artist – Real title - Remix", null, "Station");
        assertNotNull(track);
        assertEquals("Artist", track.artist);
        assertEquals("Real title - Remix", track.title);
        assertEquals("Another artist", Track.from("Title", "Another artist", "Station").artist);
        assertNull(Track.from("", null, "Station"));
        assertNull(Track.from("station", null, "Station"));
    }

    private static Station station(String id, RadioTab group) {
        return new Station(id, id, "", "", group, List.of("https://example.com/radio"), 0);
    }
}
