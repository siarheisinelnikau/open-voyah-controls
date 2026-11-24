package app.voyahmusic.player.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import app.voyahmusic.player.data.LocalIdentity;
import app.voyahmusic.player.data.MusicLibrary;
import app.voyahmusic.player.data.PairingInbox;
import app.voyahmusic.player.data.ReceiveSession;
import app.voyahmusic.player.data.TrustedPhones;
import app.voyahmusic.player.domain.Track;
import app.voyahmusic.shared.lan.MusicWire.Status;
import app.voyahmusic.shared.lan.WifiLink;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class PlayerViewModel {
    enum Receiving { INACTIVE, OPENING, WAITING, NO_NETWORK, FAILED, STOPPED }
    record Receiver(Receiving state, List<WifiLink> networks, WifiLink link, String fingerprint, String latest,
                    PairingInbox.Request request, List<TrustedPhones.Phone> phones) {}

    private final Context context;
    private final MusicLibrary library;
    private final TrustedPhones phones;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final ExecutorService server = Executors.newFixedThreadPool(2);
    private Receiver receiver = new Receiver(Receiving.INACTIVE, Collections.emptyList(), null, "", "", null,
            Collections.emptyList());
    private ReceiveSession session;
    private PairingInbox inbox;
    private int generation;
    private boolean pairingVisible;
    private boolean starting;
    private boolean closed;
    private Consumer<Receiver> receiveListener;
    private Consumer<MusicLibrary.Snapshot> tracksListener;
    private MusicLibrary.Snapshot snapshot;

    PlayerViewModel(Context context, MusicLibrary library) {
        this.context = context.getApplicationContext();
        this.library = library;
        phones = new TrustedPhones(this.context);
    }

    void observe(Consumer<MusicLibrary.Snapshot> tracksListener, Consumer<Receiver> receiveListener) {
        this.tracksListener = tracksListener;
        this.receiveListener = receiveListener;
        if (tracksListener != null && snapshot != null) tracksListener.accept(snapshot);
        if (receiveListener != null) receiveListener.accept(receiver);
    }

    void refresh() {
        storage.execute(() -> {
            MusicLibrary.Snapshot result = library.snapshot();
            main.post(() -> {
                if (closed) return;
                snapshot = result;
                if (tracksListener != null) tracksListener.accept(result);
            });
        });
    }

    void delete(Track track, Runnable failure) {
        edit(() -> library.remove(track), failure);
    }

    void createFolder(String name, Runnable failure) {
        edit(() -> library.createFolder(name) > 0, failure);
    }

    void moveTrack(Track track, long folder, Runnable failure) {
        edit(() -> library.moveTrack(track, folder), failure);
    }

    private void edit(Supplier<Boolean> change, Runnable failure) {
        storage.execute(() -> {
            boolean success;
            try {
                success = change.get();
            } catch (android.database.sqlite.SQLiteException e) {
                success = false;
            }
            boolean changed = success;
            main.post(() -> {
                if (closed) return;
                if (changed) refresh();
                else failure.run();
            });
        });
    }

    void setPairingVisible(boolean visible) {
        pairingVisible = visible;
        if (inbox != null) inbox.setOpen(visible);
        if (visible && session == null && !starting) startReceiving(null);
    }

    void decide(PairingInbox.Request request, boolean allow) {
        if (inbox != null && pairingVisible) inbox.decide(request, allow ? Status.OK : Status.DECLINED);
    }

    void forgetPhone(TrustedPhones.Phone phone, Runnable failure) {
        storage.execute(() -> {
            boolean removed = phones.remove(phone);
            main.post(() -> {
                if (closed) return;
                if (removed) phonesChanged();
                else failure.run();
            });
        });
    }

    void wifiChanged() {
        if (!starting && (session == null
                || !WifiLink.available(context, true).contains(receiver.link()))) startReceiving(null);
    }

    void startReceiving(WifiLink selected) {
        if (starting || (session != null && (selected == null || selected.equals(receiver.link())))) return;
        starting = true;
        stopSocket();
        int task = ++generation;
        show(new Receiver(Receiving.OPENING, Collections.emptyList(), null, "", "", null, phones.list()));
        server.execute(() -> {
            List<WifiLink> links = WifiLink.available(context, true);
            if (links.isEmpty()) {
                main.post(() -> failed(task, Receiving.NO_NETWORK, links, null));
                return;
            }
            WifiLink preferred = links.contains(selected) ? selected : links.get(0);
            ReceiveSession active;
            LocalIdentity identity;
            PairingInbox newInbox = new PairingInbox(request -> main.post(() -> request(task, request)));
            try {
                identity = LocalIdentity.load();
                active = new ReceiveSession(library, preferred, identity, newInbox, phones,
                        () -> main.post(() -> { if (current(task)) phonesChanged(); }),
                        name -> main.post(() -> received(task, name)),
                        () -> main.post(() -> ended(task)));
            } catch (IOException | GeneralSecurityException e) {
                main.post(() -> failed(task, Receiving.FAILED, links, preferred));
                return;
            }
            main.post(() -> {
                if (!current(task)) {
                    active.close();
                    return;
                }
                starting = false;
                session = active;
                inbox = newInbox;
                inbox.setOpen(pairingVisible);
                show(new Receiver(Receiving.WAITING, links, preferred, identity.fingerprint(), "", null, phones.list()));
                server.execute(active::run);
            });
        });
    }

    private void failed(int task, Receiving problem, List<WifiLink> links, WifiLink selected) {
        if (!current(task)) return;
        starting = false;
        show(new Receiver(problem, links, selected, "", "", null, phones.list()));
    }

    private void phonesChanged() {
        show(new Receiver(receiver.state(), receiver.networks(), receiver.link(), receiver.fingerprint(),
                receiver.latest(), receiver.request(), phones.list()));
    }

    private void request(int task, PairingInbox.Request request) {
        if (!current(task)) return;
        show(new Receiver(receiver.state(), receiver.networks(), receiver.link(), receiver.fingerprint(),
                receiver.latest(), request, receiver.phones()));
    }

    private void received(int task, String name) {
        if (closed) return;
        refresh();
        if (current(task)) show(new Receiver(Receiving.WAITING, receiver.networks(), receiver.link(),
                receiver.fingerprint(), name, receiver.request(), receiver.phones()));
    }

    private void ended(int task) {
        if (!current(task)) return;
        starting = false;
        stopSocket();
        show(new Receiver(Receiving.STOPPED, receiver.networks(), receiver.link(), "", receiver.latest(), null,
                receiver.phones()));
    }

    void stopReceiving() {
        generation++;
        starting = false;
        stopSocket();
        if (receiver.state() == Receiving.WAITING || receiver.state() == Receiving.OPENING) {
            show(new Receiver(Receiving.STOPPED, receiver.networks(), receiver.link(), "", receiver.latest(), null,
                    receiver.phones()));
        }
    }

    private void stopSocket() {
        if (inbox != null) {
            inbox.setOpen(false);
            inbox = null;
        }
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
