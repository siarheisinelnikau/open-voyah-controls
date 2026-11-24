package app.voyahmusic.transfer.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Folder;
import app.voyahmusic.shared.lan.MusicWire.Status;
import app.voyahmusic.shared.lan.WifiLink;
import app.voyahmusic.transfer.data.CarConnection;
import app.voyahmusic.transfer.data.SelectedFile;
import app.voyahmusic.transfer.data.SendSession;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.net.ssl.SSLHandshakeException;

public final class TransferViewModel {
    enum State { READY, READING, CONNECTING, SENDING, DONE, CANCELLED, NO_WIFI, ADDRESS, CODE, CONNECTION, FAILED }
    enum Link { UNPAIRED, PAIRING, CHECKING, CONNECTED, OFFLINE, DISCONNECTED }

    record Connection(Link state, String address, String confirmation, List<Folder> folders, long destination) {}

    record Screen(List<SelectedFile> files, Connection connection, State state, int completed, int duplicates,
                  SelectedFile current, long bytes, Status error) {
        boolean busy() {
            return state == State.READING || state == State.CONNECTING || state == State.SENDING
                    || connection.state() == Link.PAIRING;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Uri, SelectedFile> selected = new LinkedHashMap<>();
    private final Runnable monitor = this::monitor;
    private List<SelectedFile> files = Collections.emptyList();
    private List<Folder> folders = Collections.emptyList();
    private Consumer<Screen> listener;
    private Screen screen;
    private CarConnection car;
    private Link link;
    private long destination;
    private String confirmation = "";
    private boolean checking;
    private volatile int generation;
    private volatile boolean closed;
    private volatile SendSession active;

    public TransferViewModel(Context context) {
        this.context = context.getApplicationContext();
        car = CarConnection.load(this.context);
        link = car == null ? Link.UNPAIRED : CarConnection.paused(this.context) ? Link.DISCONNECTED : Link.CHECKING;
        show(State.READY, 0, 0, null, 0, null);
    }

    void observe(Consumer<Screen> listener) {
        this.listener = listener;
        main.removeCallbacks(monitor);
        if (listener != null) {
            listener.accept(screen);
            monitor();
        }
    }

    private void monitor() {
        if (listener == null) return;
        probe();
        main.postDelayed(monitor, 7_000);
    }

    void choose(List<Uri> uris) {
        if (screen.busy()) return;
        int task = ++generation;
        show(State.READING, 0, 0, null, 0, null);
        worker.execute(() -> {
            List<SelectedFile> result = new ArrayList<>(uris.size());
            for (Uri uri : uris) result.add(SelectedFile.read(context.getContentResolver(), uri));
            main.post(() -> {
                if (!current(task)) return;
                for (SelectedFile file : result) selected.put(file.uri(), file);
                snapshot();
                show(State.READY, 0, 0, null, 0, null);
                probe();
            });
        });
    }

    void remove(SelectedFile file) {
        if (screen.busy()) return;
        selected.remove(file.uri());
        snapshot();
        show(State.READY, 0, 0, null, 0, null);
    }

    void pair(String host, String pin) {
        if (screen.busy() || car != null) return;
        InetAddress address = MusicWire.address(host);
        if (address == null) {
            show(State.ADDRESS, 0, 0, null, 0, null);
            return;
        }
        if (!pin.matches("[0-9]{8}") && !MusicWire.fingerprint(pin)) {
            show(State.CODE, 0, 0, null, 0, null);
            return;
        }
        int task = ++generation;
        String digits = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10_000));
        confirmation = digits;
        link = Link.PAIRING;
        show(State.READY, 0, 0, null, 0, null);
        worker.execute(() -> {
            if (!current(task)) return;
            WifiLink wifi = wifi(address);
            if (wifi == null) {
                pairingResult(task, null, State.NO_WIFI, null);
                return;
            }
            try {
                SendSession session = new SendSession(wifi, address, pin, "");
                active = session;
                if (!current(task)) return;
                String name = Build.MODEL.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
                if (name.isEmpty()) name = "Android";
                SendSession.Pair result = session.pair(name.substring(0, Math.min(60, name.length())), digits);
                if (result.status() != Status.OK) {
                    pairingResult(task, null, State.FAILED, result.status());
                    return;
                }
                pairingResult(task, new CarConnection(address.getHostAddress(), result.fingerprint(), result.token()),
                        State.READY, null);
            } catch (SSLHandshakeException e) {
                pairingResult(task, null, State.FAILED, Status.CODE);
            } catch (IOException e) {
                pairingResult(task, null, State.CONNECTION, null);
            } finally {
                active = null;
            }
        });
    }

    private void pairingResult(int task, CarConnection result, State outcome, Status error) {
        main.post(() -> {
            if (!current(task)) return;
            confirmation = "";
            if (result != null && result.save(context)) {
                car = result;
                link = Link.CHECKING;
                show(State.READY, 0, 0, null, 0, null);
                probe();
            } else {
                link = Link.UNPAIRED;
                show(result == null ? outcome : State.FAILED, 0, 0, null, 0, result == null ? error : Status.IO);
            }
        });
    }

    void disconnect() {
        if (car == null) {
            cancel();
            return;
        }
        invalidate();
        CarConnection.paused(context, true);
        link = Link.DISCONNECTED;
        show(State.READY, 0, 0, null, 0, null);
    }

    void reconnect() {
        if (car == null || screen.busy() || checking) return;
        CarConnection.paused(context, false);
        link = Link.CHECKING;
        emit();
        probe();
    }

    void forget() {
        invalidate();
        clearCar();
        show(State.READY, 0, 0, null, 0, null);
    }

    private void clearCar() {
        CarConnection.forget(context);
        car = null;
        link = Link.UNPAIRED;
        folders = Collections.emptyList();
        destination = 0;
    }

    void target(long folder) {
        if (screen.busy()) return;
        if (folder == 0 || folders.stream().anyMatch(entry -> entry.id() == folder)) {
            destination = folder;
            emit();
        }
    }

    private void probe() {
        if (car == null || link == Link.DISCONNECTED || checking || screen.busy()) return;
        checking = true;
        int task = generation;
        CarConnection saved = car;
        worker.execute(() -> {
            SendSession.Check response = null;
            try {
                if (current(task)) {
                    SendSession session = connect(saved);
                    active = session;
                    if (current(task) && session != null) response = session.check();
                }
            } catch (IOException ignored) {
            } finally {
                active = null;
            }
            SendSession.Check result = response;
            main.post(() -> {
                checking = false;
                if (!current(task)) {
                    if (listener != null) probe();
                    return;
                }
                Connection previous = screen.connection();
                if (result != null && result.status() == Status.OK) {
                    link = Link.CONNECTED;
                    folders = result.folders();
                    if (destination != 0 && folders.stream().noneMatch(folder -> folder.id() == destination)) {
                        destination = 0;
                    }
                    if (!previous.equals(connection())) emit();
                } else if (result != null && result.status() == Status.UNPAIRED) {
                    clearCar();
                    show(State.FAILED, 0, 0, null, 0, Status.UNPAIRED);
                } else if (link != Link.OFFLINE) {
                    link = Link.OFFLINE;
                    emit();
                }
            });
        });
    }

    void send() {
        if (files.isEmpty() || screen.busy() || link != Link.CONNECTED) return;
        int task = ++generation;
        List<SelectedFile> batch = files;
        CarConnection saved = car;
        long folder = destination;
        show(State.CONNECTING, 0, 0, null, 0, null);
        worker.execute(() -> transfer(task, saved, folder, batch));
    }

    private void transfer(int task, CarConnection saved, long folder, List<SelectedFile> batch) {
        int sent = 0;
        int duplicates = 0;
        SelectedFile current = null;
        try {
            if (!current(task)) return;
            SendSession session = connect(saved);
            if (session == null) {
                post(task, State.NO_WIFI, 0, 0, null, 0, null);
                return;
            }
            active = session;
            if (!current(task)) return;
            SendSession.Check connected = session.check();
            if (connected.status() != Status.OK) {
                post(task, State.FAILED, 0, 0, null, 0, connected.status());
                return;
            }
            for (SelectedFile file : batch) {
                if (!current(task)) return;
                current = file;
                int completed = sent;
                int skipped = duplicates;
                post(task, State.SENDING, completed, skipped, file, 0, null);
                Status result = session.send(context.getContentResolver(), file, folder,
                        bytes -> post(task, State.SENDING, completed, skipped, file, bytes, null));
                if (result != Status.OK && result != Status.DUPLICATE) {
                    post(task, State.FAILED, sent, duplicates, file, 0, result);
                    return;
                }
                if (result == Status.DUPLICATE) duplicates++;
                sent++;
                main.post(() -> {
                    if (!closed) {
                        if (selected.get(file.uri()) == file) selected.remove(file.uri());
                        snapshot();
                        if (!screen.busy()) emit();
                    }
                });
            }
            post(task, State.DONE, sent, duplicates, null, 0, null);
        } catch (IOException e) {
            post(task, State.CONNECTION, sent, duplicates, current, 0, null);
        } finally {
            active = null;
        }
    }

    private SendSession connect(CarConnection saved) throws IOException {
        InetAddress address = MusicWire.address(saved.address());
        WifiLink wifi = wifi(address);
        return wifi == null ? null : new SendSession(wifi, address, saved.fingerprint(), saved.token());
    }

    private WifiLink wifi(InetAddress address) {
        for (WifiLink candidate : WifiLink.available(context, false)) {
            if (candidate.includes(address)) return candidate;
        }
        return null;
    }

    private void snapshot() {
        files = Collections.unmodifiableList(new ArrayList<>(selected.values()));
    }

    private boolean current(int task) {
        return !closed && generation == task;
    }

    private void post(int task, State state, int sent, int duplicates, SelectedFile file, long bytes, Status error) {
        main.post(() -> {
            if (!current(task)) return;
            if (error == Status.UNPAIRED) clearCar();
            if (state == State.NO_WIFI || state == State.CONNECTION) link = Link.OFFLINE;
            show(state, sent, duplicates, file, bytes, error);
        });
    }

    private Connection connection() {
        return new Connection(link, car == null ? "" : car.address(), confirmation, folders, destination);
    }

    private void emit() {
        show(screen.state(), screen.completed(), screen.duplicates(), screen.current(), screen.bytes(), screen.error());
    }

    private void show(State state, int completed, int duplicates, SelectedFile file, long bytes, Status error) {
        screen = new Screen(files, connection(), state, completed, duplicates, file, bytes, error);
        if (listener != null) listener.accept(screen);
    }

    private void invalidate() {
        generation++;
        SendSession session = active;
        if (session != null) session.cancel();
    }

    void cancel() {
        boolean pairing = link == Link.PAIRING;
        invalidate();
        if (pairing) {
            link = Link.UNPAIRED;
            confirmation = "";
        }
        show(pairing ? State.READY : State.CANCELLED, screen.completed(), screen.duplicates(), null, 0, null);
    }

    void close() {
        closed = true;
        observe(null);
        invalidate();
        worker.shutdownNow();
    }
}
