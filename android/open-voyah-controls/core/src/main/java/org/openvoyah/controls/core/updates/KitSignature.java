package org.openvoyah.controls.core.updates;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Verifies the exact manifest bytes using the configured uncompressed P-256 release key. */
public final class KitSignature {
    private static final String P256_X509_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d030107034200";

    private KitSignature() { }

    public static boolean hasPin(String publicKey) {
        return publicKey != null && publicKey.matches("04[0-9a-f]{128}");
    }

    public static boolean verifies(String publicKey, byte[] manifest, byte[] signature) {
        if (!hasPin(publicKey) || !canonicalSignature(signature)) return false;
        try {
            var key = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(decode(P256_X509_PREFIX + publicKey)));
            var verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(manifest);
            return verifier.verify(signature);
        } catch (GeneralSecurityException ignored) {
            return false;
        }
    }

    private static boolean canonicalSignature(byte[] signature) {
        if (signature.length < 8 || signature.length > 72 || signature[0] != 0x30 ||
            signature[1] != signature.length - 2) return false;
        int offset = 2;
        for (int field = 0; field < 2; field++) {
            if (offset + 2 >= signature.length || signature[offset++] != 0x02) return false;
            int size = signature[offset++];
            if (size < 1 || size > 33 || offset + size > signature.length || signature[offset] < 0 ||
                (size > 1 && signature[offset] == 0 && signature[offset + 1] >= 0)) return false;
            offset += size;
        }
        return offset == signature.length;
    }

    private static byte[] decode(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4) |
                Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return bytes;
    }
}
