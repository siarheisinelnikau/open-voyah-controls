package app.voyahradio.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.voyahradio.R;
import app.voyahradio.RadioApp;
import app.voyahradio.domain.Playback;
import app.voyahradio.domain.Station;
import app.voyahradio.playback.PlaybackText;
import app.voyahradio.playback.RadioService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static app.voyahradio.ui.RadioGlyph.Kind.*;

public final class RadioActivity extends Activity {
    private static final int BACKGROUND = 0xFF0E1517;
    private static final int PANEL = 0xFF172321;
    private static final int SURFACE = 0xFF182224;
    private static final int EDGE = 0xFF2B3939;
    private static final int SELECTED = 0xFF263A30;
    private static final int SELECTED_EDGE = 0xFF5A795D;
    private static final int TEXT = 0xFFF1F4EA;
    private static final int MUTED = 0xFFAABAB4;
    private static final int ACCENT = 0xFFBDF39C;
    private static final int ON_ACCENT = 0xFF19271C;
    private static final int ERROR = 0xFFFFBAAA;
    private static final int WARM = 0xFFE1C992;

    private final EnumMap<RadioViewModel.Tab, TextView> tabs = new EnumMap<>(RadioViewModel.Tab.class);
    private final ArrayList<StationRow> rows = new ArrayList<>();

    private RadioViewModel model;
    private final Consumer<Playback> playbackListener = state -> model.onPlayback(state);
    private RadioService service;
    private boolean bound;
    private int listColumns;
    private TextView stationTitle;
    private TextView stationLabel;
    private TextView playbackStatus;
    private LinearLayout playButton;
    private TextView playLabel;
    private RadioGlyph playIcon;
    private View previous;
    private View next;
    private EditText search;
    private View clear;
    private ScrollView catalogScroll;
    private LinearLayout stationList;
    private TextView emptyMessage;
    private RadioViewModel.Tab displayedTab;
    private String displayedQuery;
    private List<Station> displayedStations = Collections.emptyList();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RadioService.LocalBinder) binder).service();
            service.observe(playbackListener);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            model.onPlayback(new Playback(model.state().selected.id, Playback.Status.READY));
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        RadioApp app = (RadioApp) getApplication();
        RadioViewModel.Tab tab = saved == null ? RadioViewModel.Tab.BELARUS
                : RadioViewModel.Tab.valueOf(saved.getString("tab", "BELARUS"));
        String query = saved == null ? "" : saved.getString("query", "");
        model = new RadioViewModel(app.catalog(), app.preferences(), new RadioViewModel.Controls() {
            @Override public void play(String id) {
                hideKeyboard();
                try {
                    startForegroundService(new Intent(RadioActivity.this, RadioService.class)
                            .setAction(RadioService.PLAY).putExtra(RadioService.STATION_ID, id));
                } catch (IllegalStateException | SecurityException error) {
                    Toast.makeText(RadioActivity.this, R.string.stream_error, Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void pause() {
                if (service != null) service.pause();
            }
        }, tab, query, this::render);

        buildLayout();
        search.setText(query);
        render(model.state());
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, RadioService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (service != null) service.removeObserver(playbackListener);
        service = null;
        if (bound) unbindService(connection);
        bound = false;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        RadioViewModel.State state = model.state();
        out.putString("tab", state.tab.name());
        out.putString("query", state.query);
        super.onSaveInstanceState(out);
    }

    private void buildLayout() {
        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        boolean wide = width >= 720;
        listColumns = width >= 1000 ? 2 : 1;

        LinearLayout page = vertical();
        page.setBackgroundColor(BACKGROUND);
        page.setFocusableInTouchMode(true);
        int outer = wide ? 28 : 16;
        page.setPadding(dp(outer), dp(wide ? 16 : 12), dp(outer), dp(outer > 16 ? 18 : 12));
        setContentView(page);
        page.requestFocus();

        LinearLayout header = wide ? horizontal() : vertical();
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, wrap(MATCH_PARENT));
        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.radio_icon);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(logo, size(40, 40));
        TextView title = text(24, TEXT, true);
        title.setText(R.string.app_name);
        LinearLayout.LayoutParams titleParams = wrap(WRAP_CONTENT);
        titleParams.leftMargin = dp(10);
        brand.addView(title, titleParams);
        header.addView(brand, wide ? new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) : wrap(MATCH_PARENT));

        LinearLayout tabGroup = horizontal();
        tabGroup.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabGroup.setBackground(shape(SURFACE, 18, EDGE));
        LinearLayout.LayoutParams groupParams = wrap(wide ? WRAP_CONTENT : MATCH_PARENT);
        if (!wide) groupParams.topMargin = dp(12);
        header.addView(tabGroup, groupParams);
        addTab(tabGroup, RadioViewModel.Tab.BELARUS, R.string.belarus, wide);
        addTab(tabGroup, RadioViewModel.Tab.INTERNATIONAL, R.string.international, wide);
        addTab(tabGroup, RadioViewModel.Tab.FAVORITES, R.string.favorites, wide);

        LinearLayout content = wide ? horizontal() : vertical();
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        contentParams.topMargin = dp(wide ? 18 : 12);
        page.addView(content, contentParams);

        boolean compactPlayer = !wide || height < 510;
        View player = playerPanel(compactPlayer);
        int playerWidth = Math.min(440, Math.max(292, width * 34 / 100));
        LinearLayout.LayoutParams playerParams = wide
                ? new LinearLayout.LayoutParams(dp(playerWidth), MATCH_PARENT) : wrap(MATCH_PARENT);
        content.addView(player, playerParams);
        LinearLayout.LayoutParams catalogParams = wide
                ? new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        if (wide) catalogParams.leftMargin = dp(20);
        else catalogParams.topMargin = dp(12);
        content.addView(catalogPanel(), catalogParams);
    }

    private void addTab(LinearLayout group, RadioViewModel.Tab tab, int label, boolean wide) {
        TextView button = text(16, TEXT, true);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(wide ? 21 : 5), 0, dp(wide ? 21 : 5), 0);
        buttonLike(button, null, () -> model.chooseTab(tab));
        LinearLayout.LayoutParams params = wide ? wrap(WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        group.addView(button, params);
        tabs.put(tab, button);
    }

    private View playerPanel(boolean compact) {
        LinearLayout panel = vertical();
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(compact ? 14 : 25), dp(compact ? 13 : 26),
                dp(compact ? 14 : 25), dp(compact ? 14 : 27));
        panel.setBackground(shape(PANEL, compact ? 18 : 24, SELECTED_EDGE));

        playbackStatus = text(14, ACCENT, true);
        playbackStatus.setGravity(Gravity.CENTER);
        playbackStatus.setPadding(dp(12), dp(6), dp(12), dp(6));
        playbackStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(playbackStatus, wrap(WRAP_CONTENT));

        if (!compact) {
            View upperSpace = new View(this);
            panel.addView(upperSpace, new LinearLayout.LayoutParams(1, 0, 1f));
            RadioGlyph signal = new RadioGlyph(this, SIGNAL, ACCENT, 96);
            signal.setBackground(shape(SELECTED, 62, SELECTED_EDGE));
            panel.addView(signal, size(122, 122));
        }

        stationTitle = text(compact ? 27 : 36, TEXT, true);
        stationTitle.setGravity(Gravity.CENTER);
        stationTitle.setMaxLines(2);
        stationTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = wrap(MATCH_PARENT);
        titleParams.topMargin = dp(compact ? 8 : 21);
        panel.addView(stationTitle, titleParams);
        stationLabel = text(compact ? 14 : 17, MUTED, false);
        stationLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = wrap(MATCH_PARENT);
        labelParams.topMargin = dp(4);
        panel.addView(stationLabel, labelParams);

        if (!compact) {
            View lowerSpace = new View(this);
            panel.addView(lowerSpace, new LinearLayout.LayoutParams(1, 0, 1f));
        }

        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = wrap(MATCH_PARENT);
        controlsParams.topMargin = dp(compact ? 12 : 18);
        panel.addView(controls, controlsParams);

        int buttonSize = compact ? 50 : 58;
        previous = iconButton(PREVIOUS, TEXT, R.string.previous, () -> model.step(-1));
        previous.setBackground(actionBackground(SURFACE, 15, EDGE));
        controls.addView(previous, size(buttonSize, buttonSize));

        playButton = horizontal();
        playButton.setGravity(Gravity.CENTER);
        playButton.setPadding(dp(14), 0, dp(16), 0);
        playButton.setMinimumWidth(dp(compact ? 120 : 142));
        playButton.setBackground(actionBackground(ACCENT, 16, Color.TRANSPARENT));
        buttonLike(playButton, null, () -> model.togglePlayback());
        playIcon = new RadioGlyph(this, PLAY, ON_ACCENT, 24);
        playButton.addView(playIcon, size(26, 26));
        playLabel = text(16, ON_ACCENT, true);
        playLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams playText = wrap(WRAP_CONTENT);
        playText.leftMargin = dp(7);
        playButton.addView(playLabel, playText);
        LinearLayout.LayoutParams playParams = size(WRAP_CONTENT, buttonSize);
        playParams.setMargins(dp(9), 0, dp(9), 0);
        controls.addView(playButton, playParams);

        next = iconButton(NEXT, TEXT, R.string.next, () -> model.step(1));
        next.setBackground(actionBackground(SURFACE, 15, EDGE));
        controls.addView(next, size(buttonSize, buttonSize));
        return panel;
    }

    private View catalogPanel() {
        LinearLayout catalog = vertical();

        LinearLayout searchBar = horizontal();
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(shape(SURFACE, 15, EDGE));
        searchBar.setPadding(dp(5), 0, dp(3), 0);
        catalog.addView(searchBar, size(MATCH_PARENT, 54));
        searchBar.addView(new RadioGlyph(this, SEARCH, MUTED, 24), size(44, MATCH_PARENT));
        search = new EditText(this);
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setHint(R.string.station_search);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(17);
        search.setPadding(dp(4), 0, 0, 0);
        search.setBackgroundColor(Color.TRANSPARENT);
        searchBar.addView(search, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
        searchBar.setOnClickListener(v -> {
            search.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                model.search(text.toString());
            }
            @Override public void afterTextChanged(Editable edit) {}
        });
        clear = iconButton(CLEAR, MUTED, R.string.clear_search, () -> search.setText(""));
        clear.setBackground(actionBackground(SURFACE, 13, Color.TRANSPARENT));
        searchBar.addView(clear, size(48, 48));

        FrameLayout listFrame = new FrameLayout(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(12);
        catalog.addView(listFrame, listParams);
        catalogScroll = new ScrollView(this);
        catalogScroll.setFillViewport(false);
        catalogScroll.setClipToPadding(false);
        catalogScroll.setVerticalScrollBarEnabled(false);
        listFrame.addView(catalogScroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        stationList = vertical();
        stationList.setPadding(0, 0, 0, dp(6));
        catalogScroll.addView(stationList, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        emptyMessage = text(18, MUTED, false);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setPadding(dp(20), 0, dp(20), 0);
        listFrame.addView(emptyMessage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        return catalog;
    }

    private void render(RadioViewModel.State state) {
        if (displayedTab != state.tab) {
            for (RadioViewModel.Tab tab : RadioViewModel.Tab.values()) {
                TextView button = tabs.get(tab);
                boolean selected = tab == state.tab;
                button.setSelected(selected);
                button.setTextColor(selected ? ON_ACCENT : MUTED);
                button.setBackground(actionBackground(selected ? ACCENT : SURFACE, 14, Color.TRANSPARENT));
            }
        }

        boolean newFilter = displayedTab != state.tab || !state.query.equals(displayedQuery);
        if (newFilter || !displayedStations.equals(state.stations)) {
            displayedStations = state.stations;
            rebuildRows(state.stations);
            if (newFilter) catalogScroll.scrollTo(0, 0);
        }
        displayedTab = state.tab;
        displayedQuery = state.query;

        boolean empty = state.stations.isEmpty();
        emptyMessage.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyMessage.setText(state.tab == RadioViewModel.Tab.FAVORITES && state.query.trim().isEmpty()
                ? R.string.empty_favorites : R.string.no_results);
        clear.setVisibility(state.query.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        setText(stationTitle, state.selected.name);
        setText(stationLabel, state.selected.label);
        setText(playbackStatus, getString(PlaybackText.label(state.playback.status)));
        int statusColor = state.playback.failed() ? ERROR : switch (state.playback.status) {
            case LIVE -> ACCENT;
            case CONNECTING, BUFFERING, RECONNECTING -> WARM;
            default -> MUTED;
        };
        if (playbackStatus.getCurrentTextColor() != statusColor || playbackStatus.getBackground() == null) {
            playbackStatus.setTextColor(statusColor);
            playbackStatus.setBackground(shape(SELECTED, 30, Color.TRANSPARENT));
        }
        boolean playing = state.playback.active();
        int action = playing ? R.string.pause : (state.playback.failed() ? R.string.retry : R.string.play);
        setText(playLabel, getString(action));
        playButton.setContentDescription(getString(action));
        playIcon.appearance(playing ? PAUSE : PLAY, ON_ACCENT);
        boolean canStep = state.stations.size() > 1
                || (state.stations.size() == 1 && state.stations.get(0) != state.selected);
        previous.setEnabled(canStep);
        next.setEnabled(canStep);
        previous.setAlpha(canStep ? 1f : 0.42f);
        next.setAlpha(canStep ? 1f : 0.42f);

        for (StationRow row : rows) {
            row.render(row.station == state.selected, state.favorites.contains(row.station.id));
        }
    }

    private void rebuildRows(List<Station> stations) {
        stationList.removeAllViews();
        rows.clear();
        for (int index = 0; index < stations.size(); index += listColumns) {
            LinearLayout line = horizontal();
            LinearLayout.LayoutParams lineParams = wrap(MATCH_PARENT);
            lineParams.bottomMargin = dp(8);
            stationList.addView(line, lineParams);
            for (int col = 0; col < listColumns; col++) {
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
                if (col > 0) tileParams.leftMargin = dp(9);
                if (index + col >= stations.size()) {
                    line.addView(new View(this), tileParams);
                    continue;
                }
                StationRow row = new StationRow(stations.get(index + col));
                rows.add(row);
                line.addView(row.tile, tileParams);
            }
        }
    }

    private final class StationRow {
        final Station station;
        final LinearLayout tile;
        final LinearLayout listen;
        final TextView name;
        final RadioGlyph star;
        final FrameLayout favorite;
        Boolean selected;
        Boolean saved;

        StationRow(Station station) {
            this.station = station;
            tile = horizontal();
            tile.setGravity(Gravity.CENTER_VERTICAL);

            listen = vertical();
            listen.setGravity(Gravity.CENTER_VERTICAL);
            listen.setMinimumHeight(dp(68));
            listen.setPadding(dp(14), dp(7), dp(4), dp(7));
            listen.setBackground(actionBackground(Color.TRANSPARENT, 14, Color.TRANSPARENT));
            buttonLike(listen, station.name, () -> model.select(station));
            tile.addView(listen, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

            name = text(18, TEXT, true);
            name.setText(station.name);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            listen.addView(name, wrap(MATCH_PARENT));
            TextView label = text(14, MUTED, false);
            label.setText(station.label);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            listen.addView(label, wrap(MATCH_PARENT));

            favorite = new FrameLayout(RadioActivity.this);
            favorite.setBackground(actionBackground(Color.TRANSPARENT, 14, Color.TRANSPARENT));
            buttonLike(favorite, null, () -> model.toggleFavorite(station));
            star = new RadioGlyph(RadioActivity.this, STAR, MUTED, 24);
            favorite.addView(star, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            LinearLayout.LayoutParams favoriteParams = size(54, 54);
            favoriteParams.rightMargin = dp(5);
            tile.addView(favorite, favoriteParams);
        }

        void render(boolean active, boolean isFavorite) {
            if (selected == null || selected != active) {
                selected = active;
                tile.setBackground(shape(active ? SELECTED : SURFACE, 14, active ? SELECTED_EDGE : EDGE));
                listen.setSelected(active);
                name.setTextColor(active ? ACCENT : TEXT);
            }
            if (saved == null || saved != isFavorite) {
                saved = isFavorite;
                favorite.setContentDescription(getString(
                        isFavorite ? R.string.remove_favorite : R.string.add_favorite, station.name));
                star.appearance(isFavorite ? STAR_FILLED : STAR, isFavorite ? ACCENT : MUTED);
            }
        }
    }

    private void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void hideKeyboard() {
        if (search != null && search.hasFocus()) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
        }
    }

    private FrameLayout iconButton(RadioGlyph.Kind icon, int color, int label, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.addView(new RadioGlyph(this, icon, color, 25),
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        buttonLike(button, getString(label), action);
        return button;
    }

    private void buttonLike(View button, String description, Runnable action) {
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) background.setStroke(dp(1), stroke);
        return background;
    }

    private RippleDrawable actionBackground(int color, int radius, int stroke) {
        int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}};
        int[] colors = {0x40BDF39C, 0x44BDF39C, 0x22BDF39C};
        if (color == ACCENT) colors = new int[]{0x5519271C, 0x4019271C, 0x3319271C};
        return new RippleDrawable(new ColorStateList(states, colors), shape(color, radius, stroke),
                shape(Color.WHITE, radius, Color.TRANSPARENT));
    }

    private TextView text(int sp, int color, boolean emphasis) {
        TextView view = new TextView(this);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(emphasis ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        return container;
    }

    private LinearLayout horizontal() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        return container;
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private LinearLayout.LayoutParams wrap(int width) {
        return new LinearLayout.LayoutParams(width, WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
