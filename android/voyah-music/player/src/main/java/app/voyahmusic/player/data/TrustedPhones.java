package app.voyahmusic.player.data;

import android.content.Context;
import android.content.SharedPreferences;

import app.voyahmusic.shared.lan.MusicWire;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TrustedPhones {
    public record Phone(String token, String name) {}
    private final SharedPreferences values;

    public TrustedPhones(Context context) {
        values = context.getSharedPreferences("trusted_phones", Context.MODE_PRIVATE);
    }

    public boolean knows(String token) {
        return MusicWire.fingerprint(token) && values.contains(token);
    }

    public String add(String name) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = MusicWire.hex(secret);
        return values.edit().putString(token, name).commit() ? token : "";
    }

    public boolean remove(Phone phone) {
        return values.edit().remove(phone.token()).commit();
    }

    public List<Phone> list() {
        List<Phone> result = new ArrayList<>();
        values.getAll().forEach((token, name) -> result.add(new Phone(token, String.valueOf(name))));
        result.sort(Comparator.comparing(Phone::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Phone::token));
        return result;
    }
}
