package app.voyahmusic.transfer.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Status;
import app.voyahmusic.shared.lan.WifiLink;
import app.voyahmusic.transfer.data.SelectedFile;
import app.voyahmusic.transfer.data.SendSession;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class TransferViewModel {
    public enum State { READY, READING, CONNECTING, SENDING, DONE, CANCELLED, NO_WIFI, ADDRESS, CODE, CONNECTION, FAILED }

    public record Screen(List<SelectedFile> files, State state, int completed, int duplicates,
                         SelectedFile current, long bytes, Status error) {
        boolean busy() {
            return state == State.READING || state == State.CONNECTING || state == State.SENDING;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Uri, SelectedFile> selected = new LinkedHashMap<>();
    private List<SelectedFile> files = Collections.emptyList();
    private Consumer<Screen> listener;
    private Screen screen = new Screen(files, State.READY, 0, 0, null, 0, null);
    private volatile int generation;
    private volatile boolean closed;
    private volatile SendSession active;

    public TransferViewModel(Context context) {
        this.context = context.getApplicationContext();
    }

    void observe(Consumer<Screen> listener) {
        this.listener = listener;
        if (listener != null) listener.accept(screen);
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
            });
        });
    }

    void remove(SelectedFile file) {
        if (screen.busy()) return;
        selected.remove(file.uri());
        snapshot();
        show(State.READY, 0, 0, null, 0, null);
    }

    void send(String host, String code) {
        if (files.isEmpty() || screen.busy()) return;
        InetAddress address = MusicWire.address(host);
        if (address == null) {
            show(State.ADDRESS, 0, 0, null, 0, null);
            return;
        }
        if (!code.matches("[0-9]{8}")) {
            show(State.CODE, 0, 0, null, 0, null);
            return;
        }
        int task = ++generation;
        List<SelectedFile> batch = files;
        show(State.CONNECTING, 0, 0, null, 0, null);
        worker.execute(() -> transfer(task, address, code, batch));
    }

    private void transfer(int task, InetAddress address, String code, List<SelectedFile> batch) {
        WifiLink link = null;
        for (WifiLink candidate : WifiLink.available(context, false)) {
            if (candidate.includes(address)) {
                link = candidate;
                break;
            }
        }
        if (link == null) {
            post(task, State.NO_WIFI, 0, 0, null, 0, null);
            return;
        }
        SendSession session = new SendSession(link, address, code);
        active = session;
        int sent = 0;
        int duplicates = 0;
        SelectedFile current = null;
        try {
            if (!current(task)) return;
            Status connected = session.check();
            if (connected != Status.OK) {
                post(task, State.FAILED, 0, 0, null, 0, connected);
                return;
            }
            for (SelectedFile file : batch) {
                if (!current(task)) return;
                current = file;
                int completed = sent;
                int skipped = duplicates;
                post(task, State.SENDING, completed, skipped, file, 0, null);
                Status result = session.send(context.getContentResolver(), file,
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
                        if (!screen.busy()) {
                            show(screen.state(), screen.completed(), screen.duplicates(),
                                    screen.current(), screen.bytes(), screen.error());
                        }
                    }
                });
            }
            post(task, State.DONE, sent, duplicates, null, 0, null);
        } catch (IOException e) {
            post(task, State.CONNECTION, sent, duplicates, current, 0, null);
        } finally {
            session.cancel();
            active = null;
        }
    }

    private void snapshot() {
        files = Collections.unmodifiableList(new ArrayList<>(selected.values()));
    }

    private boolean current(int task) {
        return !closed && generation == task;
    }

    private void post(int task, State state, int sent, int duplicates, SelectedFile file, long bytes, Status error) {
        main.post(() -> {
            if (current(task)) show(state, sent, duplicates, file, bytes, error);
        });
    }

    private void show(State state, int completed, int duplicates, SelectedFile file, long bytes, Status error) {
        screen = new Screen(files, state, completed, duplicates, file, bytes, error);
        if (listener != null) listener.accept(screen);
    }

    void cancel() {
        generation++;
        SendSession session = active;
        if (session != null) session.cancel();
        show(State.CANCELLED, screen.completed(), screen.duplicates(), null, 0, null);
    }

    void close() {
        closed = true;
        listener = null;
        cancel();
        worker.shutdownNow();
    }
}
