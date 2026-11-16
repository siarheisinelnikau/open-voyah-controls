package org.openvoyah.controls.core.updates;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Compatibility decisions use observed APK and device identities, never connection preferences. */
public final class ApkUpdatePolicy {
    public static final String PACKAGE = "org.openvoyah.controls";

    public enum Refusal { RELEASE_SIGNER, PACKAGE, DEBUGGABLE, SPLIT, VERSION, VERSION_CODE, MIN_SDK, ABI, RUNTIME }

    public static final class Runtime {
        public final String version;
        public final String file;
        public final String sha256;

        public Runtime(String version, String file, String sha256) {
            if (ReleaseVersion.parse(version) == null || !file.equals("open-voyah-controls-frida-" + version) ||
                !ReleasePolicy.isSha256(sha256)) throw new IllegalArgumentException("Invalid runtime contract");
            this.version = version;
            this.file = file;
            this.sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Runtime)) return false;
            Runtime runtime = (Runtime) other;
            return version.equals(runtime.version) && file.equals(runtime.file) && sha256.equals(runtime.sha256);
        }

        @Override public int hashCode() { return Objects.hash(version, file, sha256); }
    }

    public static final class Identity {
        public final String packageName;
        public final String version;
        public final long versionCode;
        public final int minSdk;
        public final boolean debuggable;
        public final boolean split;
        public final Set<String> signers;
        public final Set<String> nativeAbis;
        public final Runtime runtime;

        public Identity(String packageName, String version, long versionCode, int minSdk, boolean debuggable,
                        boolean split, Set<String> signers, Set<String> nativeAbis, Runtime runtime) {
            this.packageName = packageName;
            this.version = version;
            this.versionCode = versionCode;
            this.minSdk = minSdk;
            this.debuggable = debuggable;
            this.split = split;
            this.signers = Collections.unmodifiableSet(new HashSet<>(signers));
            this.nativeAbis = Collections.unmodifiableSet(new HashSet<>(nativeAbis));
            this.runtime = runtime;
        }
    }

    private ApkUpdatePolicy() { }

    public static Refusal verify(ReleasePolicy.Candidate release, Identity installed, Identity candidate,
                                  String expectedSigner, int sdk, Set<String> supportedAbis, Runtime runtime) {
        if (!ReleasePolicy.isSha256(expectedSigner) || installed.signers.size() != 1 || candidate.signers.size() != 1 ||
            !installed.signers.iterator().next().equalsIgnoreCase(expectedSigner) ||
            !candidate.signers.iterator().next().equalsIgnoreCase(expectedSigner)) return Refusal.RELEASE_SIGNER;
        if (!PACKAGE.equals(installed.packageName) || !PACKAGE.equals(candidate.packageName)) return Refusal.PACKAGE;
        if (installed.debuggable || candidate.debuggable) return Refusal.DEBUGGABLE;
        if (installed.split || candidate.split) return Refusal.SPLIT;
        ReleaseVersion current = ReleaseVersion.parse(installed.version);
        ReleaseVersion proposed = ReleaseVersion.parse(candidate.version);
        if (current == null || proposed == null || !proposed.equals(release.version) || proposed.compareTo(current) <= 0)
            return Refusal.VERSION;
        if (installed.versionCode <= 0 || candidate.versionCode <= installed.versionCode) return Refusal.VERSION_CODE;
        if (candidate.minSdk <= 0 || candidate.minSdk > sdk) return Refusal.MIN_SDK;
        // The prepared Frida runtime is ARM64. A universal Java APK does not make that runtime portable.
        if (!supportedAbis.contains("arm64-v8a") || (!candidate.nativeAbis.isEmpty() &&
            Collections.disjoint(candidate.nativeAbis, supportedAbis))) return Refusal.ABI;
        if (runtime == null || !runtime.equals(installed.runtime) || !runtime.equals(candidate.runtime)) return Refusal.RUNTIME;
        return null;
    }
}
