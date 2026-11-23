package app.voyahradio.data;

import app.voyahradio.domain.Track;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

public final class NowPlayingFeed {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    public Track read(int stationId, String stationName) {
        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL("https://radioplayer.by/api/songs/current/" + stationId);
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", StreamProbe.USER_AGENT);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream input = connection.getInputStream()) {
                return parse(input, stationName);
            }
        } catch (IOException | JSONException | DateTimeParseException ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Track parse(InputStream input, String stationName) throws IOException, JSONException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (body.size() + count > MAX_RESPONSE_BYTES) return null;
            body.write(buffer, 0, count);
        }
        JSONObject track = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
        if (!track.optBoolean("hasData") || track.optInt("id", -1) <= 0) return null;
        int duration = track.optInt("duration", 0);
        if (duration <= 0 || duration > 6 * 60 * 60) return null;
        Instant start = OffsetDateTime.parse(track.getString("lastStartPlaying")).toInstant();
        Instant now = Instant.now();
        if (start.isAfter(now.plusSeconds(30)) || start.plusSeconds(duration + 25).isBefore(now)) return null;
        return Track.from(track.getString("name"), track.optString("artist"), stationName);
    }
}
