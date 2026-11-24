package app.voyahmusic.player.data;

import android.database.sqlite.SQLiteException;

import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Status;
import app.voyahmusic.shared.lan.WifiLink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLServerSocket;

public final class ReceiveSession implements AutoCloseable {
    private final MusicLibrary library;
    private final WifiLink link;
    private final LocalIdentity identity;
    private final PairingInbox pairing;
    private final TrustedPhones phones;
    private final Runnable trustedChanged;
    private final Consumer<String> received;
    private final Runnable stopped;
    private final ServerSocket server;
    private final Set<Socket> clients = new HashSet<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(0, 4, 20, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private volatile boolean closed;

    public ReceiveSession(MusicLibrary library, WifiLink link, LocalIdentity identity, PairingInbox pairing,
                          TrustedPhones phones, Runnable trustedChanged, Consumer<String> received, Runnable stopped)
            throws IOException {
        this.library = library;
        this.link = link;
        this.identity = identity;
        this.pairing = pairing;
        this.phones = phones;
        this.trustedChanged = trustedChanged;
        this.received = received;
        this.stopped = stopped;
        SSLServerSocket listener = (SSLServerSocket) identity.tls().getServerSocketFactory().createServerSocket();
        String[] protocols = java.util.Arrays.stream(listener.getSupportedProtocols())
                .filter(value -> value.equals("TLSv1.2") || value.equals("TLSv1.3")).toArray(String[]::new);
        listener.setEnabledProtocols(protocols);
        server = listener;
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(link.address(), MusicWire.PORT), 4);
        } catch (IOException e) {
            server.close();
            throw e;
        }
    }

    public void run() {
        try {
            while (!closed) accept(server.accept());
        } catch (IOException ignored) {
        } finally {
            close();
            stopped.run();
        }
    }

    private synchronized void accept(Socket socket) {
        if (closed || !link.includes(socket.getInetAddress())) {
            closeClient(socket);
            return;
        }
        clients.add(socket);
        try {
            requests.execute(() -> {
                try (Socket client = socket) {
                    handle(client);
                } catch (IOException | SQLiteException ignored) {
                    // The next request can proceed after a disconnect or local storage failure.
                } finally {
                    synchronized (this) {
                        clients.remove(socket);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            clients.remove(socket);
            closeClient(socket);
        }
    }

    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        int command = MusicWire.command(in);
        String token = in.readUTF();
        if (command == MusicWire.PAIR) {
            pair(socket, in, out, token);
            return;
        }
        if (!phones.knows(token)) {
            Status.UNPAIRED.write(out);
            return;
        }
        if (command == MusicWire.CHECK) {
            List<MusicWire.Folder> folders = library.folders();
            Status.OK.write(out);
            out.writeInt(folders.size());
            for (MusicWire.Folder folder : folders) {
                out.writeLong(folder.id());
                out.writeUTF(folder.name());
            }
            out.flush();
            return;
        }
        socket.setSoTimeout(20_000);
        long folder = in.readLong();
        String name = in.readUTF();
        long declared = in.readLong();
        if (!library.hasFolder(folder)) {
            Status.FOLDER.write(out);
        } else if (!MusicWire.validName(name) || declared == 0 || declared < -1) {
            Status.FORMAT.write(out);
        } else if (declared > MusicWire.MAX_BYTES) {
            Status.TOO_LARGE.write(out);
        } else if (!library.hasSpace(Math.max(0, declared))) {
            Status.STORAGE.write(out);
        } else {
            receive(in, out, name, declared, folder, token);
        }
    }

    private void pair(Socket socket, DataInputStream in, DataOutputStream out, String proof) throws IOException {
        String fingerprint = identity.fingerprint();
        if (!fingerprint.equals(proof) && !MusicWire.pairingCode(fingerprint).equals(proof)) {
            Status.CODE.write(out);
            return;
        }
        String name = in.readUTF();
        String digits = in.readUTF();
        if (!digits.matches("[0-9]{4}") || name.length() > 60 || name.trim().isEmpty()
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            Status.IO.write(out);
            return;
        }
        socket.setSoTimeout(300);
        Status decision = pairing.ask(name, digits, () -> waiting(in));
        if (decision != Status.OK) {
            decision.write(out);
            return;
        }
        String token = phones.add(name);
        if (token.isEmpty()) {
            Status.STORAGE.write(out);
            return;
        }
        try {
            Status.OK.write(out);
            out.writeUTF(token);
            out.flush();
            trustedChanged.run();
        } catch (IOException e) {
            phones.remove(new TrustedPhones.Phone(token, name));
            throw e;
        }
    }

    private static boolean waiting(DataInputStream in) {
        try {
            in.read();
            return false;
        } catch (SocketTimeoutException timeout) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void receive(DataInputStream in, DataOutputStream out, String name, long declared, long folder, String token)
            throws IOException {
        File staging;
        try {
            staging = library.staging();
        } catch (IOException e) {
            Status.STORAGE.write(out);
            return;
        }
        try {
            Status.OK.write(out);
            MessageDigest digest = sha256();
            Status result;
            try (FileOutputStream file = new FileOutputStream(staging);
                 DigestOutputStream data = new DigestOutputStream(file, digest)) {
                result = copy(in, data, declared);
                if (result == Status.OK) file.getFD().sync();
            } catch (IOException e) {
                result = Status.IO;
            }
            if (result == Status.OK) {
                if (closed || !phones.knows(token)) {
                    result = Status.EXPIRED;
                } else if (!MusicLibrary.isAudio(staging)) {
                    result = Status.FORMAT;
                } else {
                    try {
                        synchronized (this) {
                            result = closed || !phones.knows(token) ? Status.EXPIRED
                                    : library.publish(staging, MusicWire.hex(digest.digest()), name, folder);
                        }
                        if (result == Status.OK) received.accept(name);
                    } catch (IOException e) {
                        result = Status.STORAGE;
                    }
                }
            }
            Status reported = result;
            reported.write(out);
        } finally {
            MusicLibrary.discard(staging);
        }
    }

    private Status copy(DataInputStream in, DigestOutputStream output, long declared) throws IOException {
        byte[] buffer = new byte[MusicWire.CHUNK];
        long bytes = 0;
        long nextSpaceCheck = 0;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
        while (!closed && System.nanoTime() < deadline) {
            int chunk = in.readInt();
            if (chunk == 0) return bytes > 0 && (declared < 0 || bytes == declared) ? Status.OK : Status.IO;
            if (chunk < 0 || chunk > buffer.length) return Status.IO;
            if (bytes + chunk > MusicWire.MAX_BYTES) return Status.TOO_LARGE;
            if (declared >= 0 && bytes + chunk > declared) return Status.IO;
            if (bytes >= nextSpaceCheck) {
                if (!library.hasSpace(4L * 1024 * 1024)) return Status.STORAGE;
                nextSpaceCheck = bytes + 4L * 1024 * 1024;
            }
            in.readFully(buffer, 0, chunk);
            output.write(buffer, 0, chunk);
            bytes += chunk;
        }
        return Status.EXPIRED;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        pairing.setOpen(false);
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (Socket socket : clients) closeClient(socket);
        requests.shutdownNow();
    }

    private static void closeClient(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
