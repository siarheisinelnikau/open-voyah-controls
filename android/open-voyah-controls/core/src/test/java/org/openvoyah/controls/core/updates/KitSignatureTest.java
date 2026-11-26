package org.openvoyah.controls.core.updates;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.Test;
import static org.junit.Assert.*;

public final class KitSignatureTest {
    @Test public void signatureBindsTheExactManifestAndRejectsTrailingBytes() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        String pin = HexFormat.of().formatHex(encoded, encoded.length - 65, encoded.length);
        byte[] manifest = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);
        var signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(manifest);
        byte[] signature = signer.sign();
        assertTrue(KitSignature.verifies(pin, manifest, signature));
        manifest[manifest.length - 2] = '2';
        assertFalse(KitSignature.verifies(pin, manifest, signature));
        manifest[manifest.length - 2] = '1';
        assertFalse(KitSignature.verifies(pin, manifest, Arrays.copyOf(signature, signature.length + 1)));
        assertFalse(KitSignature.verifies("", manifest, signature));
        assertFalse(KitSignature.verifies("04" + "00".repeat(64), manifest, signature));
    }
}
