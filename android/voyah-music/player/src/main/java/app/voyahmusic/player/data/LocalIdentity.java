package app.voyahmusic.player.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import app.voyahmusic.shared.lan.LanTls;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;

public record LocalIdentity(String fingerprint, SSLContext tls) {
    private static final String ALIAS = "music-local-server";

    public static synchronized LocalIdentity load() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator keys = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            keys.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(new X500Principal("CN=Voyah Music"))
                    .build());
            keys.generateKeyPair();
        }
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(store, null);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(manager.getKeyManagers(), null, null);
        return new LocalIdentity(LanTls.fingerprint(certificate), tls);
    }
}
