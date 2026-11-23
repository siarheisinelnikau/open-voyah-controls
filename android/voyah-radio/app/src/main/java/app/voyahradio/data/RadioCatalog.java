package app.voyahradio.data;

import android.content.Context;
import app.voyahradio.domain.RadioTab;
import app.voyahradio.domain.Station;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RadioCatalog {
    private final List<Station> stations;

    public RadioCatalog(Context context) {
        try (InputStream input = context.getAssets().open("stations.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray data = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            ArrayList<Station> loaded = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < data.length(); index++) {
                JSONObject item = data.getJSONObject(index);
                JSONArray links = item.getJSONArray("streams");
                ArrayList<String> streams = new ArrayList<>();
                for (int link = 0; link < links.length(); link++) streams.add(links.getString(link));
                Station station = new Station(item.getString("id"), item.getString("name"),
                        item.getString("label"), item.optString("aliases"),
                        RadioTab.valueOf(item.getString("group")), streams, item.optInt("trackSourceId", 0),
                        item.optString("countryCode"), item.optString("labelRu"), item.optString("labelKk"));
                if (!ids.add(station.id)) throw new IllegalArgumentException("Duplicate station: " + station.id);
                loaded.add(station);
            }
            if (loaded.isEmpty()) throw new IllegalArgumentException("Empty radio catalog");
            stations = Collections.unmodifiableList(loaded);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read the bundled radio catalog", error);
        }
    }

    public List<Station> all() {
        return stations;
    }

    public Station find(String id) {
        for (Station station : stations) {
            if (station.id.equals(id)) return station;
        }
        return null;
    }

}
