package app.voyahmusic.player.data;

import app.voyahmusic.shared.lan.MusicWire.Status;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class PairingInbox {
    public static final class Request {
        private final String name;
        private final String digits;
        private volatile Status result;

        Request(String name, String digits) {
            this.name = name;
            this.digits = digits;
        }

        public String name() { return name; }
        public String digits() { return digits; }
    }

    private final Consumer<Request> changed;
    private boolean open;
    private Request pending;

    public PairingInbox(Consumer<Request> changed) {
        this.changed = changed;
    }

    public synchronized void setOpen(boolean enabled) {
        open = enabled;
        if (!enabled && pending != null) decide(pending, Status.EXPIRED);
    }

    public Status ask(String name, String digits, BooleanSupplier connected) {
        Request request = new Request(name, digits);
        synchronized (this) {
            if (!open) return Status.EXPIRED;
            if (pending != null) return Status.BUSY;
            pending = request;
            changed.accept(request);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (request.result == null && System.nanoTime() < deadline) {
            if (!connected.getAsBoolean()) {
                decide(request, Status.EXPIRED);
                return Status.EXPIRED;
            }
        }
        decide(request, Status.EXPIRED);
        return request.result;
    }

    public synchronized void decide(Request request, Status result) {
        if (pending != request || request == null) return;
        pending = null;
        request.result = result;
        changed.accept(null);
    }
}
