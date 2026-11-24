package app.voyahmusic.player.data;

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
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ReceiveSession implements AutoCloseable {
    public static final int MINUTES = 20;
    private final MusicLibrary library;
    private final WifiLink link;
    private final Consumer<String> received;
    private final Runnable stopped;
    private final ServerSocket server;
    private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(MINUTES);
    private final String code = String.format(Locale.ROOT, "%08d", new SecureRandom().nextInt(100_000_000));
    private volatile Socket client;
    private volatile boolean closed;

    public ReceiveSession(MusicLibrary library, WifiLink link, Consumer<String> received, Runnable stopped)
            throws IOException {
        this.library = library;
        this.link = link;
        this.received = received;
        this.stopped = stopped;
        server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(link.address(), MusicWire.PORT), 4);
            server.setSoTimeout((int) TimeUnit.MINUTES.toMillis(MINUTES));
        } catch (IOException e) {
            server.close();
            throw e;
        }
    }

    public String code() {
        return code;
    }

    public void run() {
        try {
            while (!closed && System.nanoTime() < deadline) {
                server.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                try (Socket socket = server.accept()) {
                    client = socket;
                    if (closed) break;
                    if (link.includes(socket.getInetAddress())) handle(socket);
                } catch (SocketTimeoutException ignored) {
                    // A stalled client can retry while the receive window is still open.
                } catch (IOException e) {
                    if (closed || client == null) break;
                } finally {
                    client = null;
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
            stopped.run();
        }
    }

    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        int command = MusicWire.command(in);
        String provided = in.readUTF();
        if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.UTF_8))) {
            Status.CODE.write(out);
            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (command == MusicWire.CHECK) {
            Status.OK.write(out);
            return;
        }
        socket.setSoTimeout(20_000);
        String name = in.readUTF();
        long declared = in.readLong();
        if (!MusicWire.validName(name) || declared == 0 || declared < -1) {
            Status.FORMAT.write(out);
        } else if (declared > MusicWire.MAX_BYTES) {
            Status.TOO_LARGE.write(out);
        } else if (!library.hasSpace(Math.max(0, declared))) {
            Status.STORAGE.write(out);
        } else {
            receive(in, out, name, declared);
        }
    }

    private void receive(DataInputStream in, DataOutputStream out, String name, long declared) throws IOException {
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
                if (closed || System.nanoTime() >= deadline) {
                    result = Status.EXPIRED;
                } else if (!MusicLibrary.isAudio(staging)) {
                    result = Status.FORMAT;
                } else {
                    try {
                        synchronized (this) {
                            result = closed ? Status.EXPIRED : library.publish(staging, hex(digest.digest()), name);
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

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 0xff) >>> 4, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Socket socket = client;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
