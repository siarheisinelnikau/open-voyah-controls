package app.voyahmusic.shared.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import app.voyahmusic.shared.R;
import app.voyahmusic.shared.data.MusicPreferences;
import app.voyahmusic.shared.update.UpdateFiles;
import app.voyahmusic.shared.update.Updates;
import app.voyahmusic.shared.update.MusicApp;

import static app.voyahmusic.shared.ui.UiKit.MATCH;
import static app.voyahmusic.shared.ui.UiKit.WRAP;

public abstract class MusicActivity extends Activity {
    private static final int SOURCES = 613;
    private static final int INSTALL = 614;
    protected UiKit ui;
    protected boolean settings;
    protected MusicPreferences prefs;
    private Updates updates;
    private final java.util.function.Consumer<Updates.Screen> listener = this::update;
    private Updates.Screen updateScreen;
    private TextView updateStatus;
    private Button check, install;
    private Button updateLink;
    private boolean installRequested;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(MusicPreferences.localized(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        prefs = new MusicPreferences(this);
        setTheme(prefs.amber() ? R.style.MusicTheme_Amber : R.style.MusicTheme);
        super.onCreate(saved);
        ui = new UiKit(this);
        settings = saved != null && saved.getBoolean("settings");
        installRequested = saved != null && saved.getBoolean("installRequested");
        updates = ((MusicApp) getApplication()).updates();
    }

    @Override
    protected void onStart() {
        super.onStart();
        updates.observe(listener);
        updates.startup();
    }

    @Override
    protected void onStop() {
        updates.remove(listener);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle output) {
        super.onSaveInstanceState(output);
        output.putBoolean("settings", settings);
        output.putBoolean("installRequested", installRequested);
    }

    protected abstract void showMain();

    protected void openSettings() {
        settings = true;
        showSettings();
    }

    protected void renderRoute() {
        if (settings) showSettings();
        else showMain();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (settings) {
            settings = false;
            showMain();
        } else {
            super.onBackPressed();
        }
    }

    protected LinearLayout page() {
        LinearLayout root = ui.column();
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(UiKit.BACKGROUND);
        int margin = ui.dp(getResources().getConfiguration().screenWidthDp >= 720 ? 28 : 16);
        root.setPadding(margin, ui.dp(12), margin, ui.dp(12));
        return root;
    }

    protected LinearLayout heading(String title, boolean back) {
        LinearLayout header = ui.row();
        if (back) {
            header.addView(ui.iconButton(UiKit.Icon.BACK, getText(R.string.back), false, this::onBackPressed),
                    ui.dp(48), ui.dp(48));
            ui.gap(header, 12);
        }
        TextView label = ui.text(title, 25, UiKit.TEXT, true);
        ui.truncate(label, 2);
        label.setMinHeight(ui.dp(52));
        header.addView(label, ui.weighted());
        label.setGravity(Gravity.CENTER_VERTICAL);
        return header;
    }

    protected void settingsButton(LinearLayout header) {
        updateLink = ui.button(getText(R.string.update_available_short), true, this::openSettings);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(WRAP, ui.dp(48));
        linkParams.rightMargin = ui.dp(8);
        header.addView(updateLink, linkParams);
        showUpdateLink();
        header.addView(ui.iconButton(UiKit.Icon.SETTINGS, getText(R.string.settings), false, this::openSettings),
                ui.dp(48), ui.dp(48));
    }

    private void showUpdateLink() {
        if (updateLink == null) return;
        boolean offered = updateScreen != null && (updateScreen.phase() == Updates.Phase.AVAILABLE
                || updateScreen.phase() == Updates.Phase.READY || updateScreen.phase() == Updates.Phase.DOWNLOAD_FAILED);
        updateLink.setVisibility(offered && !settings ? View.VISIBLE : View.GONE);
        if (offered && getResources().getConfiguration().screenWidthDp < 500) {
            updateLink.setText(R.string.update_short);
        }
    }

    protected void showSettings() {
        LinearLayout root = page();
        ui.add(root, heading(getString(R.string.settings), true), MATCH, WRAP, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        ui.add(root, scroll, MATCH, MATCH, 14);
        LinearLayout cards = ui.column();
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, MATCH, WRAP);
        int available = getResources().getConfiguration().screenWidthDp - 32;
        FrameLayout.LayoutParams cardWidth = new FrameLayout.LayoutParams(
                available > 760 ? ui.dp(760) : MATCH, WRAP, Gravity.CENTER_HORIZONTAL);
        frame.addView(cards, cardWidth);

        LinearLayout appearance = ui.card(16);
        ui.add(cards, appearance, MATCH, WRAP, 0);
        ui.add(appearance, ui.text(getText(R.string.appearance), 21, UiKit.TEXT, true), MATCH, WRAP, 0);
        ui.add(appearance, ui.text(getText(R.string.theme), 14, UiKit.MUTED, false), MATCH, WRAP, 17);
        LinearLayout themes = ui.row();
        themes.addView(ui.button(getText(R.string.graphite), !prefs.amber(), () -> chooseTheme(false)), ui.weighted());
        ui.gap(themes, 8);
        themes.addView(ui.button(getText(R.string.amber), prefs.amber(), () -> chooseTheme(true)), ui.weighted());
        ui.add(appearance, themes, MATCH, WRAP, 8);
        ui.add(appearance, ui.text(getText(R.string.language), 14, UiKit.MUTED, false), MATCH, WRAP, 17);
        String[] languages = {"system", "en", "ru", "kk"};
        CharSequence[] labels = {getText(R.string.system_language), "English", "Русский", "Қазақша"};
        int current = java.util.Arrays.asList(languages).indexOf(prefs.language());
        int selected = Math.max(current, 0);
        ui.add(appearance, ui.button(labels[selected], false, () ->
                new AlertDialog.Builder(this).setTitle(R.string.language)
                        .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                            dialog.dismiss();
                            if (index != selected) {
                                prefs.language(languages[index]);
                                recreate();
                            }
                        }).setNegativeButton(R.string.cancel, null).show()), MATCH, WRAP, 8);

        LinearLayout releases = ui.card(16);
        ui.add(cards, releases, MATCH, WRAP, 12);
        ui.add(releases, ui.text(getText(R.string.updates), 21, UiKit.TEXT, true), MATCH, WRAP, 0);
        ui.add(releases, ui.text(getString(R.string.installed_version, updates.installed()), 15,
                UiKit.MUTED, false), MATCH, WRAP, 8);
        Switch startup = new Switch(this);
        startup.setText(R.string.startup_updates);
        startup.setTextColor(UiKit.TEXT);
        startup.setMinHeight(ui.dp(48));
        startup.setChecked(prefs.startupUpdates());
        startup.setOnCheckedChangeListener((button, enabled) -> prefs.startupUpdates(enabled));
        ui.add(releases, startup, MATCH, WRAP, 7);
        check = ui.button(getText(R.string.check_updates), false, updates::check);
        ui.add(releases, check, MATCH, WRAP, 6);
        install = ui.button(getText(R.string.download_install), true, this::requestInstall);
        ui.add(releases, install, MATCH, WRAP, 8);
        updateStatus = ui.text("", 15, UiKit.MUTED, false);
        updateStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.add(releases, updateStatus, MATCH, WRAP, 8);
        setContentView(root);
        renderUpdate();
    }

    private void chooseTheme(boolean amber) {
        if (prefs.amber() != amber) {
            prefs.amber(amber);
            recreate();
        }
    }

    private void update(Updates.Screen state) {
        updateScreen = state;
        renderUpdate();
        if (installRequested && state.phase() == Updates.Phase.READY) launchInstaller();
        if (state.phase() == Updates.Phase.DOWNLOAD_FAILED) installRequested = false;
    }

    private void renderUpdate() {
        showUpdateLink();
        if (!settings || updateStatus == null) return;
        Updates.Phase phase = updateScreen == null ? Updates.Phase.IDLE : updateScreen.phase();
        String version = updateScreen == null ? "" : updateScreen.version();
        String text = switch (phase) {
            case IDLE -> "";
            case CHECKING -> getString(R.string.checking);
            case CURRENT -> getString(R.string.up_to_date);
            case AVAILABLE -> getString(R.string.update_available, version);
            case CHECK_FAILED -> getString(R.string.check_failed);
            case DOWNLOADING -> getString(R.string.downloading, updateScreen.percent());
            case READY -> getString(R.string.ready_to_install, version);
            case DOWNLOAD_FAILED -> getString(R.string.download_failed);
        };
        ui.enabled(check, phase != Updates.Phase.CHECKING && phase != Updates.Phase.DOWNLOADING);
        updateStatus.setText(text);
        updateStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        install.setVisibility(phase == Updates.Phase.AVAILABLE || phase == Updates.Phase.DOWNLOAD_FAILED
                || phase == Updates.Phase.READY ? View.VISIBLE : View.GONE);
        install.setText(phase == Updates.Phase.READY ? R.string.install_update : R.string.download_install);
    }

    private void requestInstall() {
        installRequested = true;
        if (updateScreen != null && updateScreen.phase() == Updates.Phase.READY) launchInstaller();
        else updates.download();
    }

    @SuppressWarnings("deprecation")
    private void launchInstaller() {
        installRequested = false;
        if (updateScreen == null || updateScreen.file() == null || !updateScreen.file().isFile()) {
            updates.download();
            installRequested = true;
            return;
        }
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), SOURCES);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE, UpdateFiles.uri(this))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(intent, INSTALL);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SOURCES && getPackageManager().canRequestPackageInstalls()) launchInstaller();
        if (request == INSTALL && result == RESULT_FIRST_USER) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show();
        }
    }
}
