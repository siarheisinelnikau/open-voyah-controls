package app.voyahradio.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import app.voyahradio.R;
import app.voyahradio.RadioApp;
import app.voyahradio.data.RadioPreferences;
import app.voyahradio.domain.RadioTab;
import app.voyahradio.domain.Station;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public final class RadioSettingsActivity extends Activity {
    private final Map<RadioTab, Switch> tabSwitches = new EnumMap<>(RadioTab.class);
    private final Map<RadioTab, TextView> groupButtons = new EnumMap<>(RadioTab.class);
    private final Map<String, TextView> languages = new LinkedHashMap<>();
    private final Map<String, TextView> themes = new LinkedHashMap<>();
    private final Map<String, TextView> visualizations = new LinkedHashMap<>();
    private final Map<String, StationControl> rows = new LinkedHashMap<>();

    private RadioSettingsViewModel model;
    private ScrollView stationsScroll;
    private LinearLayout stationRows;
    private Switch hideUnavailable;
    private Switch smoothVisualization;
    private TextView checkButton;
    private TextView checkStatus;
    private RadioTab displayedGroup;
    private List<Station> displayedStations = Collections.emptyList();
    private boolean updating;
    private boolean animate;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(RadioLocale.apply(base, new RadioPreferences(base)));
    }

    @Override protected void onCreate(Bundle saved) {
        RadioTheme.apply(this, new RadioPreferences(this).theme());
        super.onCreate(saved);
        RadioApp app = (RadioApp) getApplication();
        RadioTab group = saved == null ? RadioTab.BELARUS
                : RadioTab.parse(saved.getString("group"), RadioTab.BELARUS);
        model = new RadioSettingsViewModel(app.catalog(), app.preferences(), app.streamProbe(), group, this::render);
        animate = ValueAnimator.areAnimatorsEnabled();
        build();
        render(model.state());
        if (animate) {
            stationRows.setAlpha(0f);
            stationRows.setTranslationY(dp(10));
            stationRows.animate().alpha(1f).translationY(0).setDuration(200).start();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("group", model.state().group.name());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        model.close();
        super.onDestroy();
    }

    private void build() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 760;
        LinearLayout page = column();
        page.setPadding(dp(wide ? 28 : 16), dp(16), dp(wide ? 28 : 16), dp(12));
        page.setBackgroundColor(getColor(R.color.radio_background));
        setContentView(page);

        LinearLayout header = row();
        page.addView(header, size(MATCH_PARENT, WRAP_CONTENT));
        header.addView(icon(RadioGlyph.Kind.BACK, getString(R.string.back), this::finish), size(50, 50));
        TextView title = text(26, R.color.radio_text, true);
        title.setText(R.string.settings);
        LinearLayout.LayoutParams titleParams = size(WRAP_CONTENT, WRAP_CONTENT);
        titleParams.leftMargin = dp(13);
        header.addView(title, titleParams);

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        contentParams.topMargin = dp(16);
        LinearLayout controls = column();
        buildControls(controls, wide);
        View stations = buildStations(wide);
        if (wide) {
            LinearLayout content = row();
            content.setGravity(Gravity.TOP);
            page.addView(content, contentParams);
            ScrollView controlsScroll = scroll();
            controlsScroll.setVerticalScrollBarEnabled(true);
            controlsScroll.addView(controls);
            content.addView(controlsScroll, size(378, MATCH_PARENT));
            LinearLayout.LayoutParams stationsParams = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
            stationsParams.leftMargin = dp(20);
            content.addView(stations, stationsParams);
        } else {
            ScrollView content = scroll();
            page.addView(content, contentParams);
            LinearLayout stack = column();
            content.addView(stack);
            stack.addView(controls, size(MATCH_PARENT, WRAP_CONTENT));
            stack.addView(stations, size(MATCH_PARENT, WRAP_CONTENT));
        }
    }

    private void buildControls(LinearLayout controls, boolean wide) {
        LinearLayout appearance = card(controls, R.string.appearance);
        LinearLayout languageRow = labeledOptions(appearance, R.string.language, false);
        option(languageRow, languages, "ru", R.string.language_ru, value -> {
            if (model.setLanguage(value)) recreate();
        });
        option(languageRow, languages, "en", R.string.language_en, value -> {
            if (model.setLanguage(value)) recreate();
        });
        option(languageRow, languages, "kk", R.string.language_kk, value -> {
            if (model.setLanguage(value)) recreate();
        });
        LinearLayout themeRow = labeledOptions(appearance, R.string.theme, wide);
        option(themeRow, themes, "graphite", R.string.theme_graphite, this::chooseTheme);
        option(themeRow, themes, "amber", R.string.theme_amber, this::chooseTheme);
        LinearLayout visualRow = labeledOptions(appearance, R.string.visualization, wide);
        option(visualRow, visualizations, "spectrum", R.string.spectrum, model::setVisualization);
        option(visualRow, visualizations, "wave", R.string.wave, model::setVisualization);
        option(visualRow, visualizations, "off", R.string.visualization_off, model::setVisualization);
        smoothVisualization = toggle(getString(R.string.smooth_visualization));
        appearance.addView(smoothVisualization, size(MATCH_PARENT, WRAP_CONTENT));
        smoothVisualization.setOnCheckedChangeListener((button, enabled) -> {
            if (!updating) model.setSmoothVisualization(enabled);
        });

        LinearLayout tabs = card(controls, R.string.visible_tabs);
        LinearLayout tabRow = tabs;
        int tabIndex = 0;
        for (RadioTab tab : RadioTab.values()) {
            if (wide && tabIndex % 2 == 0) {
                tabRow = row();
                tabs.addView(tabRow, size(MATCH_PARENT, WRAP_CONTENT));
            }
            Switch toggle = toggle(getString(RadioLabels.tab(tab)));
            LinearLayout.LayoutParams toggleParams = wide
                    ? new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    : size(MATCH_PARENT, WRAP_CONTENT);
            if (wide && tabIndex % 2 == 1) toggleParams.leftMargin = dp(10);
            tabRow.addView(toggle, toggleParams);
            tabIndex++;
            tabSwitches.put(tab, toggle);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (!updating && !model.showTab(tab, checked)) {
                    Toast.makeText(this, R.string.keep_one_tab, Toast.LENGTH_SHORT).show();
                }
            });
        }

        LinearLayout availability = card(controls, R.string.availability);
        hideUnavailable = toggle(getString(R.string.hide_unavailable));
        availability.addView(hideUnavailable, size(MATCH_PARENT, WRAP_CONTENT));
        hideUnavailable.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) model.hideUnavailable(checked);
        });
        checkStatus = caption(R.string.availability_hint);
        checkStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        availability.addView(checkStatus, size(MATCH_PARENT, WRAP_CONTENT));
        checkButton = button(R.string.check_availability, model::toggleCheck);
        availability.addView(checkButton, size(MATCH_PARENT, WRAP_CONTENT));
    }

    private LinearLayout labeledOptions(LinearLayout parent, int label, boolean inline) {
        TextView caption = caption(label);
        if (!inline) {
            parent.addView(caption, size(MATCH_PARENT, WRAP_CONTENT));
            return optionRow(parent);
        }
        LinearLayout line = row();
        LinearLayout.LayoutParams lineParams = size(MATCH_PARENT, WRAP_CONTENT);
        lineParams.topMargin = dp(6);
        parent.addView(line, lineParams);
        line.addView(caption, size(108, WRAP_CONTENT));
        LinearLayout options = row();
        line.addView(options, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        return options;
    }

    private View buildStations(boolean wide) {
        LinearLayout panel = column();
        panel.setPadding(dp(15), dp(15), dp(15), dp(4));
        panel.setBackground(shape(getColor(R.color.radio_panel), role(R.attr.radio_edge_role), 20));
        TextView title = text(21, R.color.radio_text, true);
        title.setText(R.string.station_settings);
        panel.addView(title, size(MATCH_PARENT, WRAP_CONTENT));
        panel.addView(caption(R.string.station_settings_hint), size(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout groups = optionRow(panel);
        for (RadioTab tab : RadioTab.values()) {
            TextView group = button(RadioLabels.tab(tab), () -> model.selectGroup(tab));
            groups.addView(group, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            groupButtons.put(tab, group);
        }
        stationRows = column();
        if (wide) {
            stationsScroll = scroll();
            stationsScroll.addView(stationRows);
            panel.addView(stationsScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
        } else {
            panel.addView(stationRows, size(MATCH_PARENT, WRAP_CONTENT));
        }
        return panel;
    }

    private void chooseTheme(String style) {
        if (model.setTheme(style)) recreate();
    }

    private void render(RadioSettingsViewModel.State state) {
        updating = true;
        try {
            for (Map.Entry<RadioTab, Switch> entry : tabSwitches.entrySet()) {
                entry.getValue().setChecked(!state.options.hiddenTabs.contains(entry.getKey()));
            }
            chooseOptions(languages, state.language);
            chooseOptions(themes, state.theme);
            chooseOptions(visualizations, state.visualization);
            smoothVisualization.setChecked(state.smoothVisualization);
            hideUnavailable.setChecked(state.options.hideUnavailable);
            checkButton.setText(state.scan == RadioSettingsViewModel.ScanStatus.RUNNING
                    ? R.string.cancel_check : R.string.check_availability);
            String status = switch (state.scan) {
                case IDLE -> getString(R.string.availability_hint);
                case RUNNING -> getString(R.string.checking_stations, state.checked, state.total);
                case DONE -> getString(R.string.check_finished, state.unavailable, state.unknown);
                case NO_NETWORK -> getString(R.string.check_needs_internet);
            };
            if (!TextUtils.equals(checkStatus.getText(), status)) checkStatus.setText(status);
            boolean groupChanged = displayedGroup != state.group;
            if (groupChanged || !displayedStations.equals(state.stations)) {
                rebuildRows(state.stations, state.group, groupChanged);
                displayedGroup = state.group;
                displayedStations = state.stations;
                chooseOptions(groupButtons, state.group);
            }
            for (StationControl item : rows.values()) {
                item.visible.setChecked(!state.options.hiddenStations.contains(item.station.id));
                String detail = state.options.unavailableStations.contains(item.station.id)
                        ? getString(R.string.station_unavailable)
                        : item.station.displayLabel(getResources().getConfiguration().getLocales().get(0));
                if (!TextUtils.equals(item.details.getText(), detail)) item.details.setText(detail);
            }
        } finally {
            updating = false;
        }
    }

    private void rebuildRows(List<Station> stations, RadioTab group, boolean groupChanged) {
        int scrollY = stationsScroll == null || groupChanged ? 0 : stationsScroll.getScrollY();
        Map<String, Integer> oldPositions = new LinkedHashMap<>();
        if (!groupChanged && animate) {
            for (StationControl control : rows.values()) oldPositions.put(control.station.id, control.view.getTop());
        }
        stationRows.removeAllViews();
        rows.clear();
        if (stations.isEmpty() && group == RadioTab.FAVORITES) {
            stationRows.addView(caption(R.string.empty_favorites));
        }
        for (int index = 0; index < stations.size(); index++) {
            Station station = stations.get(index);
            StationControl control = new StationControl(station, index > 0, index + 1 < stations.size());
            rows.put(station.id, control);
            LinearLayout.LayoutParams params = size(MATCH_PARENT, WRAP_CONTENT);
            params.bottomMargin = dp(6);
            stationRows.addView(control.view, params);
            Integer oldY = oldPositions.get(station.id);
            if (oldY != null) {
                control.view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View view, int l, int top, int r, int b,
                                                          int oldL, int oldT, int oldR, int oldB) {
                        view.removeOnLayoutChangeListener(this);
                        if (oldY != top) {
                            view.setTranslationY(oldY - top);
                            view.animate().translationY(0).setDuration(190).start();
                        }
                    }
                });
            }
        }
        if (stationsScroll != null) stationsScroll.post(() -> stationsScroll.scrollTo(0, scrollY));
        if (animate && groupChanged && displayedGroup != null) {
            stationRows.animate().cancel();
            stationRows.setAlpha(0.45f);
            stationRows.setTranslationX(dp(8));
            stationRows.animate().alpha(1f).translationX(0).setDuration(170).start();
        }
    }

    private final class StationControl {
        final Station station;
        final LinearLayout view;
        final Switch visible;
        final TextView details;

        StationControl(Station station, boolean canMoveUp, boolean canMoveDown) {
            this.station = station;
            view = row();
            view.setPadding(dp(12), dp(4), dp(6), dp(4));
            view.setBackground(shape(getColor(R.color.radio_surface), role(R.attr.radio_edge_role), 13));
            LinearLayout labels = column();
            view.addView(labels, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            TextView name = text(17, R.color.radio_text, true);
            name.setText(station.name);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name, size(MATCH_PARENT, WRAP_CONTENT));
            details = text(14, R.color.radio_muted, false);
            details.setMaxLines(1);
            details.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(details, size(MATCH_PARENT, WRAP_CONTENT));
            visible = toggle("");
            visible.setContentDescription(getString(R.string.show_station, station.name));
            visible.setGravity(Gravity.CENTER);
            visible.setOnCheckedChangeListener((button, checked) -> {
                if (!updating) model.showStation(station, checked);
            });
            view.addView(visible, size(56, 52));
            addMove(RadioGlyph.Kind.UP, R.string.move_station_up, -1, canMoveUp);
            addMove(RadioGlyph.Kind.DOWN, R.string.move_station_down, 1, canMoveDown);
        }

        private void addMove(RadioGlyph.Kind kind, int label, int direction, boolean enabled) {
            View move = icon(kind, getString(label, station.name), () -> model.move(station, direction));
            move.setEnabled(enabled);
            move.setAlpha(enabled ? 1f : 0.38f);
            LinearLayout.LayoutParams params = size(48, 48);
            params.leftMargin = dp(4);
            view.addView(move, params);
        }
    }

    private <T> void chooseOptions(Map<T, TextView> options, T selected) {
        for (Map.Entry<T, TextView> entry : options.entrySet()) {
            TextView option = entry.getValue();
            boolean active = entry.getKey().equals(selected);
            if (option.isSelected() == active && option.getTag() != null) continue;
            option.setTag(Boolean.TRUE);
            option.setSelected(active);
            option.setTextColor(active ? role(R.attr.radio_on_accent_role) : getColor(R.color.radio_text));
            int fill = active ? role(R.attr.radio_accent_role) : getColor(R.color.radio_surface);
            option.setBackground(action(fill));
        }
    }

    private void option(LinearLayout group, Map<String, TextView> options, String value,
                        int label, Consumer<String> onSelect) {
        TextView button = button(label, () -> onSelect.accept(value));
        group.addView(button, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        options.put(value, button);
    }

    private LinearLayout optionRow(LinearLayout parent) {
        LinearLayout options = row();
        options.setPadding(0, 0, 0, dp(8));
        parent.addView(options, size(MATCH_PARENT, WRAP_CONTENT));
        return options;
    }

    private LinearLayout card(LinearLayout parent, int title) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(8));
        card.setBackground(shape(getColor(R.color.radio_panel), role(R.attr.radio_edge_role), 18));
        LinearLayout.LayoutParams params = size(MATCH_PARENT, WRAP_CONTENT);
        params.bottomMargin = dp(10);
        parent.addView(card, params);
        TextView heading = text(19, R.color.radio_text, true);
        heading.setText(title);
        card.addView(heading, size(MATCH_PARENT, WRAP_CONTENT));
        return card;
    }

    private TextView caption(int label) {
        TextView caption = text(14, R.color.radio_muted, false);
        caption.setText(label);
        caption.setPadding(0, dp(7), 0, dp(7));
        return caption;
    }

    private Switch toggle(String label) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextColor(getColor(R.color.radio_text));
        view.setTextSize(16);
        view.setMinHeight(dp(48));
        view.setSwitchPadding(dp(10));
        return view;
    }

    private TextView button(int label, Runnable click) {
        TextView button = text(15, R.color.radio_text, true);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(7), dp(5), dp(7), dp(5));
        button.setMinHeight(dp(48));
        button.setFocusable(true);
        button.setOnClickListener(view -> click.run());
        button.setBackground(action(getColor(R.color.radio_surface)));
        return button;
    }

    private FrameLayout icon(RadioGlyph.Kind kind, String label, Runnable click) {
        FrameLayout button = new FrameLayout(this);
        button.addView(new RadioGlyph(this, kind, getColor(R.color.radio_text), 22),
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setOnClickListener(view -> click.run());
        button.setBackground(action(getColor(R.color.radio_surface)));
        return button;
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) result.setStroke(dp(1), border);
        return result;
    }

    private RippleDrawable action(int fill) {
        int ripple = (role(R.attr.radio_accent_role) & 0x00FFFFFF) | 0x44000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple),
                shape(fill, role(R.attr.radio_edge_role), 12), shape(Color.WHITE, Color.TRANSPARENT, 12));
    }

    private TextView text(int sp, int resource, boolean emphasis) {
        TextView value = new TextView(this);
        value.setTextSize(sp);
        value.setTextColor(getColor(resource));
        value.setTypeface(Typeface.create(emphasis ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        value.setIncludeFontPadding(false);
        return value;
    }

    private ScrollView scroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private int role(int attribute) {
        return RadioTheme.color(this, attribute);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
