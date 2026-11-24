package app.voyahmusic.shared.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;

import app.voyahmusic.shared.data.MusicPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public final class Updates {
    private static final String REPOSITORY = "siarheisinelnikau/open-voyah-controls";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases";
    private static final String DOWNLOAD = "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final long MAX_APK = 128L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}");
    private static final Pattern CHECKSUM = Pattern.compile("[A-Fa-f0-9]{64}");

    public enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, CHECK_FAILED, DOWNLOADING, READY, DOWNLOAD_FAILED }
    public record Screen(Phase phase, String version, int percent, File file) {}
    private record Release(String version, String url, long size, String digest, String checksumUrl) {}

    private final Context context;
    private final String installed;
    private final Pattern assetName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Consumer<Screen>> listeners = new HashSet<>();
    private Screen screen = new Screen(Phase.IDLE, "", 0, null);
    private Release offer;
    private boolean startupDone;

    Updates(Context context, String releaseName) {
        this.context = context;
        assetName = Pattern.compile(Pattern.quote(releaseName) + "-v(" + VERSION.pattern() + ")\\.apk");
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public String installed() { return installed; }

    public void observe(Consumer<Screen> listener) {
        listeners.add(listener);
        listener.accept(screen);
    }

    public void remove(Consumer<Screen> listener) {
        listeners.remove(listener);
    }

    public void startup() {
        if (startupDone) return;
        startupDone = true;
        if (new MusicPreferences(context).startupUpdates()) check();
    }

    public void check() {
        if (screen.phase() == Phase.CHECKING || screen.phase() == Phase.DOWNLOADING) return;
        set(new Screen(Phase.CHECKING, "", 0, null));
        worker.execute(() -> {
            try {
                Release candidate = latest();
                main.post(() -> {
                    offer = candidate;
                    set(new Screen(candidate == null ? Phase.CURRENT : Phase.AVAILABLE,
                            candidate == null ? "" : candidate.version(), 0, null));
                });
            } catch (IOException | JSONException e) {
                main.post(() -> set(new Screen(Phase.CHECK_FAILED, "", 0, null)));
            }
        });
    }

    public void download() {
        if (offer == null || screen.phase() == Phase.CHECKING || screen.phase() == Phase.DOWNLOADING) return;
        Release target = offer;
        set(new Screen(Phase.DOWNLOADING, target.version(), 0, null));
        worker.execute(() -> {
            try {
                File apk = download(target);
                main.post(() -> set(new Screen(Phase.READY, target.version(), 100, apk)));
            } catch (IOException | PackageManager.NameNotFoundException | SecurityException e) {
                main.post(() -> set(new Screen(Phase.DOWNLOAD_FAILED, target.version(), 0, null)));
            }
        });
    }

    private Release latest() throws IOException, JSONException {
        Release best = null;
        for (int page = 1; page <= 8; page++) {
            JSONArray releases = new JSONArray(read(API + "?per_page=30&page=" + page, 4 * 1024 * 1024));
            for (int index = 0; index < releases.length(); index++) {
                JSONObject release = releases.getJSONObject(index);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int item = 0; item < assets.length(); item++) {
                    JSONObject asset = assets.getJSONObject(item);
                    String name = asset.optString("name");
                    Matcher match = assetName.matcher(name);
                    if (!match.matches()) continue;
                    String version = match.group(1);
                    String url = asset.optString("browser_download_url");
                    long size = asset.optLong("size");
                    if (!url.startsWith(DOWNLOAD) || size <= 0 || size > MAX_APK || compare(version, installed) <= 0
                            || (best != null && compare(version, best.version()) <= 0)) continue;
                    String digest = asset.optString("digest");
                    best = new Release(version, url, size, digest, checksumUrl(assets, name));
                }
            }
            if (releases.length() < 30) break;
        }
        return best;
    }

    private static String checksumUrl(JSONArray assets, String name) throws JSONException {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String url = asset.optString("browser_download_url");
            if ((name + ".sha256").equals(asset.optString("name")) && url.startsWith(DOWNLOAD)) return url;
        }
        return "";
    }

    static int compare(String a, String b) {
        if (!VERSION.matcher(b).matches()) return 1;
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < left.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private File download(Release release) throws IOException, PackageManager.NameNotFoundException {
        File folder = new File(context.getCacheDir(), "updates");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create update directory");
        File pending = new File(folder, "pending.apk");
        File apk = UpdateFiles.apk(context);
        String expected = expectedDigest(release);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        HttpsURLConnection connection = open(release.url(), "application/octet-stream");
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(pending)) {
            byte[] buffer = new byte[64 * 1024];
            long bytes = 0;
            int last = 0;
            for (int count; (count = input.read(buffer)) != -1; ) {
                bytes += count;
                if (bytes > release.size()) throw new IOException("Unexpected update length");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                int percent = (int) (100 * bytes / release.size());
                if (percent >= last + 5) {
                    last = percent;
                    main.post(() -> set(new Screen(Phase.DOWNLOADING, release.version(), percent, null)));
                }
            }
            if (bytes != release.size()) throw new IOException("Incomplete update");
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(hex(digest.digest()))) {
                throw new IOException("Update checksum mismatch");
            }
            output.getFD().sync();
        } catch (IOException e) {
            pending.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        try {
            verify(pending, release.version());
            if (!pending.renameTo(apk)) throw new IOException("Could not prepare update");
            return apk;
        } finally {
            if (pending.exists()) pending.delete();
        }
    }

    private String expectedDigest(Release release) throws IOException {
        if (release.digest().startsWith("sha256:")) {
            String value = release.digest().substring(7);
            if (!CHECKSUM.matcher(value).matches()) throw new IOException("Invalid release digest");
            return value;
        }
        if (release.checksumUrl().isEmpty()) return "";
        String value = read(release.checksumUrl(), 8 * 1024).trim().split("\\s+", 2)[0];
        if (!CHECKSUM.matcher(value).matches()) throw new IOException("Invalid release checksum");
        return value;
    }

    private void verify(File file, String version) throws IOException, PackageManager.NameNotFoundException {
        PackageManager packages = context.getPackageManager();
        PackageInfo existing = packages.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo apk = packages.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (apk == null || !existing.packageName.equals(apk.packageName) || apk.signingInfo == null
                || existing.signingInfo == null || !version.equals(apk.versionName)
                || apk.getLongVersionCode() <= existing.getLongVersionCode()
                || !signers(existing).equals(signers(apk))) {
            throw new IOException("Update package, version or signing certificate mismatch");
        }
    }

    private static Set<Signature> signers(PackageInfo info) {
        return new HashSet<>(Arrays.asList(info.signingInfo.getApkContentsSigners()));
    }

    private static String read(String url, int limit) throws IOException {
        HttpsURLConnection connection = open(url, url.startsWith(API) ? "application/vnd.github+json" : "application/octet-stream");
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (output.size() + count > limit) throw new IOException("Response exceeds size limit");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "Voyah-Music");
        return connection;
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            value.append(Character.forDigit((b & 255) >>> 4, 16));
            value.append(Character.forDigit(b & 15, 16));
        }
        return value.toString();
    }

    private void set(Screen next) {
        screen = next;
        for (Consumer<Screen> listener : listeners) listener.accept(next);
    }
}
