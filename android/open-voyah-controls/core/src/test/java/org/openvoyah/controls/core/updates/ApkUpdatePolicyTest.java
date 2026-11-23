package org.openvoyah.controls.core.updates;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ApkUpdatePolicyTest {
    private static final String SIGNER = "a".repeat(64);
    private static final ApkUpdatePolicy.Runtime RUNTIME = new ApkUpdatePolicy.Runtime("17.26.2",
        "open-voyah-controls-frida-17.26.2", "b".repeat(64));
    private static final ReleasePolicy.Candidate RELEASE = ReleasePolicy.candidate("android-v0.19.0", false, false,
        "OpenVoyahControls-v0.19.0.apk", ReleasePolicy.DOWNLOAD_PREFIX + "android-v0.19.0/OpenVoyahControls-v0.19.0.apk",
        123, "sha256:" + "c".repeat(64));

    private ApkUpdatePolicy.Identity identity(String version, long code, String pkg, boolean debug, boolean split,
        int minSdk, Set<String> signers, Set<String> abis, ApkUpdatePolicy.Runtime runtime) {
        return new ApkUpdatePolicy.Identity(pkg, version, code, minSdk, debug, split, signers, abis, runtime);
    }

    private ApkUpdatePolicy.Identity installed() {
        return identity("0.18.0", 20, ApkUpdatePolicy.PACKAGE, false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME);
    }

    private ApkUpdatePolicy.Refusal verify(ApkUpdatePolicy.Identity candidate) {
        return ApkUpdatePolicy.verify(RELEASE, installed(), candidate, SIGNER, 28, Set.of("arm64-v8a"), RUNTIME);
    }

    private ApkUpdatePolicy.Identity candidate() {
        return identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE, false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME);
    }

    @Test public void permitsOnlyNewerCompatibleSameSignerSameRuntimeApk() { assertNull(verify(candidate())); }

    @Test public void rejectsSignerMigrationAndUnpinnedOrMultipleSignerInstallations() {
        assertEquals(ApkUpdatePolicy.Refusal.RELEASE_SIGNER, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of("d".repeat(64)), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.RELEASE_SIGNER, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of(SIGNER, "d".repeat(64)), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.RELEASE_SIGNER,
            ApkUpdatePolicy.verify(RELEASE, installed(), candidate(), "", 28, Set.of("arm64-v8a"), RUNTIME));
    }

    @Test public void rejectsArchiveIdentityAndAndroidCompatibilityMismatches() {
        assertEquals(ApkUpdatePolicy.Refusal.PACKAGE, verify(identity("0.19.0", 21, "org.example.other",
            false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.DEBUGGABLE, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            true, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.SPLIT, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, true, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.VERSION, verify(identity("0.20.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.VERSION_CODE, verify(identity("0.19.0", 20, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.MIN_SDK, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 30, Set.of(SIGNER), Set.of("arm64-v8a"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.ABI, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of(SIGNER), Set.of("x86_64"), RUNTIME)));
        assertEquals(ApkUpdatePolicy.Refusal.ABI,
            ApkUpdatePolicy.verify(RELEASE, installed(), candidate(), SIGNER, 28, Set.of("x86_64"), RUNTIME));
    }

    @Test public void rejectsRuntimeMigrationEvenWhenTheApkIsOtherwiseValid() {
        ApkUpdatePolicy.Runtime next = new ApkUpdatePolicy.Runtime("17.27.0", "open-voyah-controls-frida-17.27.0", "e".repeat(64));
        assertEquals(ApkUpdatePolicy.Refusal.RUNTIME, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), next)));
        assertEquals(ApkUpdatePolicy.Refusal.RUNTIME, verify(identity("0.19.0", 21, ApkUpdatePolicy.PACKAGE,
            false, false, 28, Set.of(SIGNER), Set.of("arm64-v8a"), null)));
    }
}
