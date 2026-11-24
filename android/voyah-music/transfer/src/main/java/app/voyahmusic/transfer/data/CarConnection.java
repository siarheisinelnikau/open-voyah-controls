package app.voyahmusic.transfer.data;

import android.content.Context;
import android.content.SharedPreferences;

import app.voyahmusic.shared.lan.MusicWire;

public record CarConnection(String address, String fingerprint, String token) {
    private static SharedPreferences values(Context context) {
        return context.getSharedPreferences("paired_car", Context.MODE_PRIVATE);
    }

    public static CarConnection load(Context context) {
        SharedPreferences values = values(context);
        String host = values.getString("address", "");
        String identity = values.getString("identity", "");
        String token = values.getString("credential", "");
        return MusicWire.address(host) != null && MusicWire.fingerprint(identity) && MusicWire.fingerprint(token)
                ? new CarConnection(host, identity, token) : null;
    }

    public boolean save(Context context) {
        return values(context).edit().putString("address", address).putString("identity", fingerprint)
                .putString("credential", token).putBoolean("paused", false).commit();
    }

    public static void forget(Context context) {
        values(context).edit().clear().apply();
    }

    public static boolean paused(Context context) {
        return values(context).getBoolean("paused", false);
    }

    public static void paused(Context context, boolean value) {
        values(context).edit().putBoolean("paused", value).apply();
    }
}
