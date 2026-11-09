package org.openvoyah.controls;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** One worker owns all native/audio resources. Cancellation invalidates queued callbacks. */
final class OfflineVoiceInput implements AutoCloseable {
    interface Listener { void status(String text); void result(String text); void error(String text); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    OfflineVoiceInput(Context context) { this.context = context.getApplicationContext(); }
    void start(Listener listener) {
        if (closed) return;
        String language = new VoiceSettings(context).language();
        long token = generation.incrementAndGet();
        worker.execute(() -> {
            AudioRecord audio = null;
            try {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    throw new IOException(UiText.get(R.string.ui_138));
                post(token, () -> listener.status(UiText.get(R.string.ui_139)));
                File modelPath = new File(context.getFilesDir(), language.equals("en") ? "speech-model-en-0.15" : "speech-model-ru-0.22");
                if (!new File(modelPath,"ready").isFile()) {
                    copyAssets("speech-model-" + language,modelPath,token);
                    try (FileOutputStream ready = new FileOutputStream(new File(modelPath,"ready"))) { ready.write(1); }
                }
                if (!current(token)) return;
                try (Model model = new Model(modelPath.getAbsolutePath()); Recognizer recognizer = new Recognizer(model,16000f)) {
                    if (!current(token)) return;
                    int size = AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if (size <= 0) throw new IOException(UiText.get(R.string.ui_140));
                    audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size,6400));
                    if (audio.getState()!=AudioRecord.STATE_INITIALIZED) throw new IOException(UiText.get(R.string.ui_141));
                    if (!current(token)) return;
                    audio.startRecording();
                    if (audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) throw new IOException(UiText.get(R.string.ui_142));
                    post(token, () -> listener.status(UiText.get(R.string.ui_143)));
                    short[] buffer = new short[1600];
                    long deadline = android.os.SystemClock.elapsedRealtime()+12000;
                    while (current(token) && android.os.SystemClock.elapsedRealtime()<deadline) {
                        int count = audio.read(buffer,0,buffer.length,AudioRecord.READ_NON_BLOCKING);
                        if (count<0) throw new IOException(UiText.get(R.string.ui_144) + count);
                        if (count==0) { Thread.sleep(20); continue; }
                        if (recognizer.acceptWaveForm(buffer,count)) {
                            String text = new JSONObject(recognizer.getResult()).optString("text");
                            if (!text.isBlank()) { post(token, () -> listener.result(text)); return; }
                        }
                    }
                    if (current(token)) {
                        String text = new JSONObject(recognizer.getFinalResult()).optString("text");
                        if (text.isBlank()) post(token, () -> listener.error(UiText.get(R.string.ui_145)));
                        else post(token, () -> listener.result(text));
                    }
                }
            } catch (Exception | LinkageError ex) {
                post(token, () -> listener.error(UiText.get(R.string.ui_146) + ex.getMessage() + UiText.get(R.string.ui_147)));
            } finally {
                if (audio != null) {
                    try { if (audio.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING) audio.stop(); }
                    catch (IllegalStateException ignored) { }
                    audio.release();
                }
            }
        });
    }
    private boolean current(long token) { return !closed && token==generation.get(); }
    private void post(long token, Runnable runnable) { main.post(() -> { if (current(token)) runnable.run(); }); }
    void cancel() { generation.incrementAndGet(); }
    void repair(Runnable done) {
        if(closed)return;
        long token=generation.incrementAndGet();
        worker.execute(()->{
            File marker=new File(context.getFilesDir(),new VoiceSettings(context).language().equals("en")?"speech-model-en-0.15/ready":"speech-model-ru-0.22/ready");
            if(!marker.exists() || marker.delete()) post(token,done);
        });
    }
    @Override public void close() { closed=true; cancel(); worker.shutdown(); }
    private void copyAssets(String asset, File directory, long token) throws IOException {
        if (!current(token)) throw new IOException(UiText.get(R.string.ui_148));
        String[] children = context.getAssets().list(asset);
        if (children == null || children.length==0) throw new IOException(UiText.get(R.string.ui_149));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(UiText.get(R.string.ui_150));
        for (String child : children) {
            String path = asset+"/"+child;
            String[] nested = context.getAssets().list(path);
            if (nested != null && nested.length>0) { copyAssets(path,new File(directory,child),token); continue; }
            try (InputStream input = context.getAssets().open(path); OutputStream output = new FileOutputStream(new File(directory,child))) {
                byte[] buffer = new byte[32768]; int n;
                while ((n=input.read(buffer))!=-1) { if (!current(token)) throw new IOException(UiText.get(R.string.ui_148)); output.write(buffer,0,n); }
            }
        }
    }
}
