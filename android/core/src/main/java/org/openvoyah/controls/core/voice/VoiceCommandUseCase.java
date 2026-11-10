package org.openvoyah.controls.core.voice;

import java.util.function.Predicate;
import org.openvoyah.controls.core.VoiceCatalog.Command;

/** Routes a matched voice command to its execution boundary. */
public final class VoiceCommandUseCase {
    public enum Destination { OFFLINE_WEATHER, LOCAL, DIAL, VEHICLE }

    public interface Contacts {
        String phone(String commandId) throws Exception;
    }

    public record Request(Destination destination, Command command, String phrase, String phone) { }

    private final Predicate<String> local;
    private final Contacts contacts;

    public VoiceCommandUseCase(Predicate<String> local, Contacts contacts) {
        this.local = local;
        this.contacts = contacts;
    }

    public static boolean isWeather(String id) {
        return "Weather".equals(id) || "WeatherTomorrow".equals(id);
    }

    public Request prepare(Command command, String phrase) throws Exception {
        String id = command.id();
        if (isWeather(id)) return new Request(Destination.OFFLINE_WEATHER, command, phrase, "");
        if (id.startsWith("Call_")) return new Request(Destination.DIAL, command, phrase, contacts.phone(id));
        return new Request(local.test(id) ? Destination.LOCAL : Destination.VEHICLE, command, phrase, "");
    }
}
