package app.voyahmusic.shared.lan;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class LanTls {
    private LanTls() {}

    public static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            return MusicWire.hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }

    public static SSLContext client(String pin) throws GeneralSecurityException {
        if (!MusicWire.fingerprint(pin) && !pin.matches("[0-9]{8}")) {
            throw new GeneralSecurityException("Invalid car identity");
        }
        boolean full = MusicWire.fingerprint(pin);
        X509TrustManager trust = new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing car certificate");
                chain[0].checkValidity();
                String fingerprint = fingerprint(chain[0]);
                String identity = full ? fingerprint : MusicWire.pairingCode(fingerprint);
                if (!pin.equals(identity)) throw new CertificateException("Car identity mismatch");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                throw new CertificateException("Client certificates are not used");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trust}, null);
        return context;
    }
}
