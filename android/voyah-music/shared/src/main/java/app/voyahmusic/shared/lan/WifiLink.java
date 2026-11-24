package app.voyahmusic.shared.lan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WifiLink(InetAddress address, int prefix, Network network) {
    public boolean includes(InetAddress peer) {
        return MusicWire.sameSubnet(address, peer, prefix);
    }

    public static List<WifiLink> available(Context context, boolean includeHotspot) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Map<InetAddress, WifiLink> links = new LinkedHashMap<>();
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (capabilities == null || properties == null
                    || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                add(links, link.getAddress(), link.getPrefixLength(), network);
            }
        }
        if (includeHotspot) {
            try {
                var interfaces = NetworkInterface.getNetworkInterfaces();
                if (interfaces != null) {
                    for (NetworkInterface iface : Collections.list(interfaces)) {
                        if (!iface.isUp() || !iface.getName().matches("(wlan|swlan|softap|ap|wifi|br)[0-9]+")) continue;
                        for (InterfaceAddress link : iface.getInterfaceAddresses()) {
                            add(links, link.getAddress(), link.getNetworkPrefixLength(), null);
                        }
                    }
                }
            } catch (SocketException ignored) {
                // The connectivity API already supplied any active Wi-Fi client links.
            }
        }
        return new ArrayList<>(links.values());
    }

    private static void add(Map<InetAddress, WifiLink> links, InetAddress address, int prefix, Network network) {
        if (MusicWire.isLocal(address) && prefix > 0 && prefix <= 32) {
            links.putIfAbsent(address, new WifiLink(address, prefix, network));
        }
    }
}
