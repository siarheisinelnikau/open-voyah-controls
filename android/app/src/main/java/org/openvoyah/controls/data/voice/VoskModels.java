package org.openvoyah.controls.data.voice;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.vosk.Model;

/** One bounded native model cache, accessed only by the serialized speech worker. */
public final class VoskModels {
    private static final Object PREPARATION_LOCK = new Object();
    // Both pinned models are below 96 MiB unpacked; leave room for files and native setup.
    private static final long PREPARATION_SPACE = 128L * 1024 * 1024;
    private final ToLongFunction<File> usableSpace;
    private Model cached;
    private File cachedPath;

    public VoskModels() { this(File::getUsableSpace); }

    public VoskModels(ToLongFunction<File> usableSpace) { this.usableSpace = usableSpace; }

    public static final class StorageUnavailable extends IOException {
        public StorageUnavailable() { super(UiText.get(R.string.setup_space_failed)); }
    }

    public Model load(Context context, String language, BooleanSupplier current) throws IOException {
        return load(context, language, null, current);
    }

    /**
     * Loads [downloaded], a verified model folder of SpeechModelStore, when the language has no model in the APK;
     * otherwise unpacks the bundled model of [language] on first use.
     */
    public Model load(Context context, String language, File downloaded, BooleanSupplier current) throws IOException {
        if (downloaded != null) {
            synchronized (PREPARATION_LOCK) {
                checkCurrent(current);
                if (!new File(downloaded, "ready").isFile()) throw new IOException(UiText.get(R.string.voice_model_not_downloaded));
                if (!downloaded.equals(cachedPath)) release();
                if (cached == null) {
                    cached = new Model(downloaded.getAbsolutePath());
                    cachedPath = downloaded;
                }
                if (!current.getAsBoolean()) { release(); checkCurrent(current); }
                return cached;
            }
        }
        synchronized (PREPARATION_LOCK) {
        File path = modelPath(context, language);
        File marker = new File(path, "ready");
        if (!path.equals(cachedPath) || !marker.isFile()) release();
        if (!marker.isFile()) {
            checkCurrent(current);
            if (usableSpace.applyAsLong(context.getFilesDir()) < PREPARATION_SPACE) throw new StorageUnavailable();
            copyAssets(context, "speech-model-" + language, path, current);
            checkCurrent(current);
        }
        checkCurrent(current);
        if (cached == null) {
            cached = new Model(path.getAbsolutePath());
            cachedPath = path;
        }
        if (!current.getAsBoolean()) { release(); checkCurrent(current); }
        // Only a successfully opened native model is marked ready after a cold copy or repair.
        if (!marker.isFile()) {
            try (FileOutputStream ready = new FileOutputStream(marker)) { ready.write(1); ready.getFD().sync(); }
        }
        return cached;
        }
    }

    public void invalidate(Context context, String language) throws IOException {
        File path = modelPath(context, language);
        if (path.equals(cachedPath)) release();
        File marker = new File(path, "ready");
        if (marker.exists() && !marker.delete()) throw new IOException(UiText.get(R.string.voice_model_repair_failed));
    }

    public void release() {
        if (cached != null) cached.close();
        cached = null;
        cachedPath = null;
    }

    private static File modelPath(Context context, String language) {
        return switch (language) {
            case "en" -> new File(context.getFilesDir(), "speech-model-en-0.15");
            case "ru" -> new File(context.getFilesDir(), "speech-model-ru-0.22");
            default -> throw new IllegalArgumentException("No bundled speech model for this language");
        };
    }

    private static void checkCurrent(BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException(UiText.get(R.string.ui_148));
    }

    private static void copyAssets(Context context, String asset, File directory, BooleanSupplier current) throws IOException {
        checkCurrent(current);
        String[] children = context.getAssets().list(asset);
        if (children == null || children.length == 0) throw new IOException(UiText.get(R.string.ui_149));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(UiText.get(R.string.ui_150));
        byte[] buffer = new byte[32768];
        for (String child : children) {
            String path = asset + "/" + child;
            String[] nested = context.getAssets().list(path);
            if (nested != null && nested.length > 0) {
                copyAssets(context, path, new File(directory, child), current);
                continue;
            }
            try (InputStream input = context.getAssets().open(path);
                 OutputStream output = new FileOutputStream(new File(directory, child))) {
                for (int count; (count = input.read(buffer)) != -1;) {
                    checkCurrent(current);
                    output.write(buffer, 0, count);
                }
            }
        }
    }
}
