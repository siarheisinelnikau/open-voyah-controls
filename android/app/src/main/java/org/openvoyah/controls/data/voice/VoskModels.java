package org.openvoyah.controls.data.voice;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import org.openvoyah.controls.R;
import org.openvoyah.controls.UiText;
import org.vosk.Model;

/** One bounded native model cache, accessed only by the serialized speech worker. */
public final class VoskModels {
    private Model cached;
    private File cachedPath;

    public Model load(Context context, String language, BooleanSupplier current) throws IOException {
        File path = modelPath(context, language);
        File marker = new File(path, "ready");
        if (!path.equals(cachedPath) || !marker.isFile()) release();
        if (!marker.isFile()) {
            copyAssets(context, "speech-model-" + language, path, current);
            checkCurrent(current);
            try (FileOutputStream ready = new FileOutputStream(marker)) { ready.write(1); }
        }
        checkCurrent(current);
        if (cached == null) {
            cached = new Model(path.getAbsolutePath());
            cachedPath = path;
        }
        return cached;
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
        return new File(context.getFilesDir(), language.equals("en") ? "speech-model-en-0.15" : "speech-model-ru-0.22");
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
