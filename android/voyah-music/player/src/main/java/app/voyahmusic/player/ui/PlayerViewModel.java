package app.voyahmusic.player.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import app.voyahmusic.player.data.MusicLibrary;
import app.voyahmusic.player.data.ReceiveSession;
import app.voyahmusic.player.domain.Track;
import app.voyahmusic.shared.lan.WifiLink;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class PlayerViewModel {
    enum Receiving { INACTIVE, OPENING, WAITING, NO_NETWORK, FAILED, STOPPED }
    record Receiver(Receiving state, List<WifiLink> networks, WifiLink link, String code, String latest) {}

    private final Context context;
    private final MusicLibrary library;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final ExecutorService server = Executors.newCachedThreadPool();
    private Receiver receiver = new Receiver(Receiving.INACTIVE, Collections.emptyList(), null, "", "");
    private ReceiveSession session;
    private int generation;
    private boolean closed;
    private Consumer<Receiver> receiveListener;
    private Consumer<List<Track>> tracksListener;
    private List<Track> tracks;

    PlayerViewModel(Context context, MusicLibrary library) {
        this.context = context.getApplicationContext();
        this.library = library;
    }

    void observe(Consumer<List<Track>> tracksListener, Consumer<Receiver> receiveListener) {
        this.tracksListener = tracksListener;
        this.receiveListener = receiveListener;
        if (tracksListener != null && tracks != null) tracksListener.accept(tracks);
        if (receiveListener != null) receiveListener.accept(receiver);
    }

    void refresh() {
        storage.execute(() -> {
            List<Track> result = library.tracks();
            main.post(() -> {
                if (closed) return;
                tracks = result;
                if (tracksListener != null) tracksListener.accept(result);
            });
        });
    }

    void delete(Track track, Runnable failure) {
        storage.execute(() -> {
            boolean removed = library.remove(track);
            main.post(() -> {
                if (closed) return;
                if (removed) refresh();
                else failure.run();
            });
        });
    }

    void startReceiving(WifiLink selected) {
        stopSocket();
        int task = ++generation;
        show(new Receiver(Receiving.OPENING, Collections.emptyList(), null, "", ""));
        server.execute(() -> {
            List<WifiLink> links = WifiLink.available(context, true);
            if (links.isEmpty()) {
                main.post(() -> {
                    if (current(task)) show(new Receiver(Receiving.NO_NETWORK, links, null, "", ""));
                });
                return;
            }
            WifiLink preferred = links.contains(selected) ? selected : links.get(0);
            ReceiveSession active;
            try {
                active = new ReceiveSession(library, preferred,
                        name -> main.post(() -> received(task, name)),
                        () -> main.post(() -> ended(task)));
            } catch (IOException e) {
                main.post(() -> {
                    if (current(task)) show(new Receiver(Receiving.FAILED, links, preferred, "", ""));
                });
                return;
            }
            main.post(() -> {
                if (!current(task)) {
                    active.close();
                    return;
                }
                session = active;
                show(new Receiver(Receiving.WAITING, links, preferred, active.code(), ""));
                server.execute(active::run);
            });
        });
    }

    private void received(int task, String name) {
        if (closed) return;
        refresh();
        if (current(task)) show(new Receiver(Receiving.WAITING, receiver.networks(), receiver.link(), receiver.code(), name));
    }

    private void ended(int task) {
        if (!current(task)) return;
        session = null;
        show(new Receiver(Receiving.STOPPED, receiver.networks(), receiver.link(), "", receiver.latest()));
    }

    void stopReceiving() {
        generation++;
        stopSocket();
        if (receiver.state() == Receiving.WAITING || receiver.state() == Receiving.OPENING) {
            show(new Receiver(Receiving.STOPPED, receiver.networks(), receiver.link(), "", receiver.latest()));
        }
    }

    private void stopSocket() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    Receiver receiver() {
        return receiver;
    }

    private void show(Receiver state) {
        receiver = state;
        if (receiveListener != null) receiveListener.accept(state);
    }

    private boolean current(int task) {
        return !closed && generation == task;
    }

    void close() {
        closed = true;
        observe(null, null);
        stopReceiving();
        storage.shutdownNow();
        server.shutdownNow();
    }
}
