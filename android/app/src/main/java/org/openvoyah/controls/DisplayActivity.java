package org.openvoyah.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.provider.Settings;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import org.openvoyah.controls.core.display.DisplayEventBus;
import org.openvoyah.controls.data.display.DisplayPreferences;
import org.openvoyah.controls.data.frida.FridaNavigationFeed;
import org.openvoyah.controls.presentation.display.DisplayViewModel;

import org.openvoyah.controls.presentation.theme.CockpitViews;

/** A private Android display whose density does not affect either physical screen. */
public final class DisplayActivity extends Activity implements SurfaceHolder.Callback {
    private static final int[] DENSITIES = {0, 160, 200, 240, 320};
    private VirtualDisplay display;
    private Presentation presentation;
    private SurfaceView preview;
    private TextView status, media, route;
    private Button density;
    private DisplayViewModel model;
    private Runnable unsubscribe;
    private boolean showing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        model = new DisplayViewModel(UiText.displayEvents(this), new DisplayPreferences(this), new FridaNavigationFeed(this));
        LinearLayout root = CockpitViews.page(this, R.string.virtual_display);
        LinearLayout actions = new LinearLayout(this);
        density = new Button(this);
        density.setAllCaps(false);
        density.setOnClickListener(v -> chooseDensity());
        actions.addView(density, new LinearLayout.LayoutParams(0, -2, 1));
        Button permission = new Button(this);
        permission.setAllCaps(false);
        permission.setText(UiText.get(R.string.display_media_access));
        permission.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (android.content.ActivityNotFoundException ex) { status.setText(UiText.get(R.string.ui_134)); }
        });
        actions.addView(permission, new LinearLayout.LayoutParams(0, -2, 1));
        Button navigation = new Button(this);
        navigation.setAllCaps(false);
        navigation.setText(UiText.get(R.string.display_navigation));
        navigation.setOnClickListener(v -> configureNavigation(navigation));
        actions.addView(navigation, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        int gap = CockpitViews.dp(this, 8);
        for (int index = 1; index < actions.getChildCount(); index++) {
            LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) actions.getChildAt(index).getLayoutParams();
            size.setMarginStart(gap);
        }
        Switch share = new Switch(this);
        share.setText(UiText.get(R.string.cluster_media_share));
        share.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        share.setChecked(model.clusterMedia());
        share.setOnCheckedChangeListener((v, checked) -> model.clusterMedia(checked));
        root.addView(share);
        status = new TextView(this);
        root.addView(status);
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateDensityLabel();
    }

    private int chosenDensity() { return model.density(); }

    private void updateDensityLabel() {
        int value = chosenDensity();
        density.setText(value == 0 ? UiText.get(R.string.display_auto_density)
            : String.format(Locale.ROOT, "%d DPI", value));
    }

    private void chooseDensity() {
        String[] labels = new String[DENSITIES.length];
        labels[0] = UiText.get(R.string.display_auto_density);
        for (int i = 1; i < labels.length; i++) labels[i] = DENSITIES[i] + " DPI";
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.display_density))
            .setItems(labels, (d, n) -> {
                model.density(DENSITIES[n]);
                updateDensityLabel();
                createDisplay(preview.getHolder());
            }).setNegativeButton(UiText.get(R.string.ui_89), null).show();
    }

    private void configureNavigation(Button button) {
        if (model.changingNavigation()) return;
        boolean enabled = model.navigationEnabled();
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.display_navigation))
            .setMessage(UiText.get(enabled ? R.string.navigation_feed_disable : R.string.navigation_feed_enable))
            .setPositiveButton(UiText.get(R.string.ui_187), (d, w) -> {
                button.setEnabled(false);
                model.navigationEnabled(!enabled, message -> {
                    if (isDestroyed() || isFinishing()) return;
                    button.setEnabled(true);
                    status.setText(message);
                });
            }).setNegativeButton(UiText.get(R.string.ui_89), null).show();
    }

    private void createDisplay(SurfaceHolder holder) {
        closeDisplay();
        int width = holder.getSurfaceFrame().width(), height = holder.getSurfaceFrame().height();
        if (!showing || !holder.getSurface().isValid() || width <= 0 || height <= 0) return;
        int dpi = chosenDensity();
        if (dpi == 0) dpi = getResources().getDisplayMetrics().densityDpi;
        try {
            display = getSystemService(DisplayManager.class).createVirtualDisplay("Open Voyah Controls", width, height, dpi,
                holder.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (display == null) throw new IllegalStateException();
            presentation = new Presentation(this, display.getDisplay(), R.style.AppTheme);
            LinearLayout screen = new LinearLayout(presentation.getContext());
            screen.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(24 * screen.getResources().getDisplayMetrics().density);
            screen.setPadding(pad, pad, pad, pad);
            media = new TextView(screen.getContext());
            media.setTextSize(24);
            screen.addView(media);
            route = new TextView(screen.getContext());
            route.setTextSize(20);
            route.setPadding(0, pad, 0, 0);
            screen.addView(route);
            presentation.setContentView(screen);
            presentation.show();
            status.setText(String.format(Locale.ROOT, "%d × %d · %d DPI", width, height, dpi));
            renderData(model.state());
        } catch (RuntimeException ex) {
            closeDisplay();
            status.setText(UiText.get(R.string.display_unavailable));
        }
    }

    private void renderData(DisplayEventBus.Snapshot state) {
        if (media == null || route == null) return;
        DisplayEventBus.Media track = state.media();
        media.setText(track.available() ? track.title() + (track.artist().isEmpty() ? "" : "\n" + track.artist())
            : UiText.get(R.string.display_no_media));
        DisplayEventBus.Navigation navigation = state.navigation();
        String detail = navigation.nextRoad().isEmpty() ? "" : "\n" + navigation.nextRoad();
        if (navigation.meters() >= 0) detail += " · " + navigation.meters() + " m";
        route.setText(navigation.road().isEmpty() && detail.isEmpty() ? UiText.get(R.string.display_no_route) : navigation.road() + detail);
    }

    @Override protected void onStart() {
        super.onStart();
        showing = true;
        unsubscribe = model.observe(this::renderData);
        createDisplay(preview.getHolder());
    }

    @Override protected void onStop() {
        showing = false;
        if (unsubscribe != null) { unsubscribe.run(); unsubscribe = null; }
        closeDisplay();
        super.onStop();
    }

    private void closeDisplay() {
        if (presentation != null) { presentation.dismiss(); presentation = null; }
        if (display != null) { display.release(); display = null; }
        media = null;
        route = null;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { createDisplay(holder); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { closeDisplay(); }
}
