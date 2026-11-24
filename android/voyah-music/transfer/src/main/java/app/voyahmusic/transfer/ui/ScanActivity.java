package app.voyahmusic.transfer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.voyahmusic.shared.R;
import app.voyahmusic.shared.data.MusicPreferences;
import app.voyahmusic.shared.lan.PairQr;
import app.voyahmusic.shared.ui.UiKit;

import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity {
    static final String QR_RESULT = "voyah_pair_qr";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private Camera camera;
    private SurfaceHolder preview;
    private SurfaceView surface;
    private FrameLayout frame;
    private TextView status;
    private boolean running;
    private int generation;
    private final Runnable capture = this::captureFrame;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(MusicPreferences.localized(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        setTheme(new MusicPreferences(this).amber() ? R.style.MusicTheme_Amber : R.style.MusicTheme);
        super.onCreate(saved);
        UiKit ui = new UiKit(this);
        LinearLayout page = ui.column();
        page.setBackgroundColor(UiKit.BACKGROUND);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16));
        LinearLayout header = ui.row();
        header.addView(ui.iconButton(UiKit.Icon.BACK, getText(R.string.back), false, this::finish),
                ui.dp(48), ui.dp(48));
        ui.gap(header, 10);
        header.addView(ui.text(getText(R.string.scan_qr), 23, UiKit.TEXT, true), ui.weighted());
        ui.add(page, header, UiKit.MATCH, UiKit.WRAP, 0);
        status = ui.text(getText(R.string.scan_hint), 16, UiKit.MUTED, false);
        ui.add(page, status, UiKit.MATCH, UiKit.WRAP, 15);
        surface = new SurfaceView(this);
        frame = new FrameLayout(this);
        frame.addView(surface, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH, Gravity.CENTER));
        LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(UiKit.MATCH, 0, 1);
        area.topMargin = ui.dp(14);
        page.addView(frame, area);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                preview = holder;
                startCamera();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                preview = null;
                stopCamera();
            }
        });
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        startCamera();
    }

    private void startCamera() {
        if (!running || preview == null || camera != null) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int chosen = 0;
            for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
                Camera.getCameraInfo(id, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    chosen = id;
                    break;
                }
            }
            Camera.getCameraInfo(chosen, info);
            camera = Camera.open(chosen);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = parameters.getSupportedPreviewSizes().stream()
                    .min(Comparator.comparingInt(value -> Math.abs(value.width * value.height - 1280 * 720)))
                    .orElseThrow(() -> new IllegalStateException("Missing camera preview format"));
            parameters.setPreviewSize(size.width, size.height);
            parameters.setPreviewFormat(ImageFormat.NV21);
            if (parameters.getSupportedFocusModes() != null && parameters.getSupportedFocusModes()
                    .contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(parameters);
            int angle = switch (getWindowManager().getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_0 -> 0;
                case Surface.ROTATION_90 -> 90;
                case Surface.ROTATION_180 -> 180;
                case Surface.ROTATION_270 -> 270;
                default -> 0;
            };
            int rotation = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (720 - info.orientation - angle) % 360 : (360 + info.orientation - angle) % 360;
            fitPreview(size, rotation);
            camera.setDisplayOrientation(rotation);
            camera.setPreviewDisplay(preview);
            camera.startPreview();
            captureFrame();
        } catch (IOException | RuntimeException e) {
            stopCamera();
            status.setText(R.string.camera_unavailable);
        }
    }

    private void fitPreview(Camera.Size size, int rotation) {
        frame.post(() -> {
            if (camera == null) return;
            int width = rotation % 180 == 0 ? size.width : size.height;
            int height = rotation % 180 == 0 ? size.height : size.width;
            float fit = Math.min(frame.getWidth() / (float) width, frame.getHeight() / (float) height);
            if (fit > 0) surface.setLayoutParams(new FrameLayout.LayoutParams(
                    Math.round(width * fit), Math.round(height * fit), Gravity.CENTER));
        });
    }

    private void captureFrame() {
        if (camera == null) return;
        int task = generation;
        camera.setOneShotPreviewCallback((data, source) -> {
            if (camera != source || task != generation) return;
            Camera.Size size = source.getParameters().getPreviewSize();
            if (data == null || data.length < size.width * size.height) {
                main.postDelayed(capture, 180);
                return;
            }
            decoder.execute(() -> {
                String value = PairQr.decodeFrame(data, size.width, size.height);
                PairQr result = PairQr.parse(value);
                main.post(() -> {
                    if (camera == null || task != generation) return;
                    if (result == null) {
                        if (value != null) status.setText(R.string.invalid_qr);
                        main.postDelayed(capture, 180);
                    } else {
                        setResult(RESULT_OK, new Intent().putExtra(QR_RESULT, result.encode()));
                        finish();
                    }
                });
            });
        });
    }

    private void stopCamera() {
        generation++;
        main.removeCallbacks(capture);
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        running = false;
        stopCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }
}
