package app.voyahmusic.transfer.data;

import android.content.ContentResolver;

import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Folder;
import app.voyahmusic.shared.lan.MusicWire.Status;
import app.voyahmusic.shared.lan.LanTls;
import app.voyahmusic.shared.lan.WifiLink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

public final class SendSession {
    public record Check(Status status, List<Folder> folders) {}
    public record Pair(Status status, String fingerprint, String token) {}

    private final WifiLink link;
    private final InetAddress address;
    private final String pin;
    private final String token;
    private final SSLContext tls;
    private Socket current;
    private volatile boolean cancelled;

    public SendSession(WifiLink link, InetAddress address, String pin, String token) throws IOException {
        this.link = link;
        this.address = address;
        this.pin = pin;
        this.token = token;
        try {
            tls = LanTls.client(pin);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    public Pair pair(String name, String digits) throws IOException {
        try (SSLSocket socket = connect()) {
            socket.setSoTimeout(50_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            MusicWire.request(out, MusicWire.PAIR, pin);
            out.writeUTF(name);
            out.writeUTF(digits);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Status status = Status.read(in);
            if (status != Status.OK) return new Pair(status, "", "");
            String credential = in.readUTF();
            if (!MusicWire.fingerprint(credential)) throw new IOException("Invalid pairing credential");
            String identity = LanTls.fingerprint((X509Certificate) socket.getSession().getPeerCertificates()[0]);
            return new Pair(status, identity, credential);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        } finally {
            clear();
        }
    }

    public Check check() throws IOException {
        try (Socket socket = connect()) {
            socket.setSoTimeout(5_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            MusicWire.request(out, MusicWire.CHECK, token);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Status status = Status.read(in);
            if (status != Status.OK) return new Check(status, Collections.emptyList());
            int count = in.readInt();
            if (count < 0 || count > MusicWire.MAX_FOLDERS) throw new IOException("Invalid folder count");
            List<Folder> folders = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long id = in.readLong();
                String name = in.readUTF();
                if (id <= 0 || !MusicWire.validFolder(name)) throw new IOException("Invalid folder");
                folders.add(new Folder(id, name));
            }
            return new Check(status, Collections.unmodifiableList(folders));
        } finally {
            clear();
        }
    }

    public Status send(ContentResolver resolver, SelectedFile file, long folder, LongConsumer progress) throws IOException {
        if (!MusicWire.validName(file.name()) || file.size() == 0) return Status.FORMAT;
        if (file.size() > MusicWire.MAX_BYTES) return Status.TOO_LARGE;

        try (InputStream source = resolver.openInputStream(file.uri())) {
            if (source == null) return Status.IO;
            try (Socket socket = connect()) {
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                MusicWire.request(out, MusicWire.UPLOAD, token);
                out.writeLong(folder);
                out.writeUTF(file.name());
                out.writeLong(file.size() < 0 ? -1 : file.size());
                out.flush();
                Status ready = Status.read(in);
                if (ready != Status.OK) return ready;
                byte[] buffer = new byte[MusicWire.CHUNK];
                long bytes = 0;
                long nextProgressAt = 0;
                for (int count; (count = source.read(buffer)) != -1; ) {
                    if (cancelled) throw new InterruptedIOException();
                    if (count == 0) continue;
                    bytes += count;
                    if (bytes > MusicWire.MAX_BYTES) return Status.TOO_LARGE;
                    out.writeInt(count);
                    out.write(buffer, 0, count);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now >= nextProgressAt) {
                        progress.accept(bytes);
                        nextProgressAt = now + 240;
                    }
                }
                out.writeInt(0);
                out.flush();
                progress.accept(bytes);
                return Status.read(in);
            } finally {
                clear();
            }
        } catch (SecurityException e) {
            return Status.IO;
        }
    }

    private SSLSocket connect() throws IOException {
        Socket socket = link.network().getSocketFactory().createSocket();
        synchronized (this) {
            if (cancelled) {
                socket.close();
                throw new InterruptedIOException();
            }
            current = socket;
        }
        try {
            socket.connect(new InetSocketAddress(address, MusicWire.PORT), 4_000);
            socket.setSoTimeout(5_000);
            SSLSocket secured = (SSLSocket) tls.getSocketFactory()
                    .createSocket(socket, address.getHostAddress(), MusicWire.PORT, true);
            secured.startHandshake();
            secured.setSoTimeout(25_000);
            return secured;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private synchronized void clear() {
        current = null;
    }

    public synchronized void cancel() {
        cancelled = true;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }
}
