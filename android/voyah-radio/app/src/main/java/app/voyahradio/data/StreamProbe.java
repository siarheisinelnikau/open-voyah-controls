package app.voyahradio.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import app.voyahradio.domain.Availability;
import app.voyahradio.domain.Station;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class StreamProbe {
    public static final String USER_AGENT = "VoyahRadio/0.3.4";
    private final ConnectivityManager connectivity;

    public StreamProbe(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
    }

    public Network validatedNetwork() {
        Network network = connectivity.getActiveNetwork();
        if (network == null) return null;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ? network : null;
    }

    public boolean isCurrent(Network network) {
        return network != null && network.equals(validatedNetwork());
    }

    public Availability check(Station station, Network network) {
        Availability combined = Availability.UNAVAILABLE;
        for (String stream : station.streams) {
            if (Thread.currentThread().isInterrupted() || !isCurrent(network)) return Availability.UNKNOWN;
            Availability result = checkStream(stream, network);
            if (result == Availability.AVAILABLE) return isCurrent(network) ? result : Availability.UNKNOWN;
            combined = combined.merge(result);
        }
        return isCurrent(network) ? combined : Availability.UNKNOWN;
    }

    private Availability checkStream(String address, Network network) {
        try {
            URL url = new URL(address);
            for (int redirect = 0; redirect <= 5 && !Thread.currentThread().isInterrupted(); redirect++) {
                if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) break;
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(url);
                try {
                    connection.setConnectTimeout(3500);
                    connection.setReadTimeout(3500);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    int status = connection.getResponseCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) break;
                        url = new URL(url, location);
                        continue;
                    }
                    if (Availability.isDefinitiveHttpFailure(status)) return Availability.UNAVAILABLE;
                    if (status != 200 && status != 206) break;
                    String type = connection.getContentType();
                    if (type == null) type = "";
                    type = type.toLowerCase(Locale.ROOT);
                    try (InputStream input = connection.getInputStream()) {
                        if (type.startsWith("audio/") || connection.getHeaderField("icy-name") != null) {
                            return input.read() >= 0 ? Availability.AVAILABLE : Availability.UNKNOWN;
                        }
                        if (type.contains("mpegurl") || url.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                            byte[] prefix = new byte[7];
                            int count = 0;
                            while (count < prefix.length) {
                                int read = input.read(prefix, count, prefix.length - count);
                                if (read < 0) break;
                                count += read;
                            }
                            if (count == prefix.length
                                    && new String(prefix, StandardCharsets.US_ASCII).equals("#EXTM3U")) {
                                return Availability.AVAILABLE;
                            }
                        }
                    }
                    break;
                } finally {
                    connection.disconnect();
                }
            }
        } catch (IOException ignored) {
            // A transport failure provides no evidence about the station.
        }
        return Availability.UNKNOWN;
    }
}
