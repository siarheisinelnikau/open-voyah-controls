package app.voyahmusic.player.ui;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import app.voyahmusic.player.PlayerApp;
import app.voyahmusic.player.domain.Track;
import app.voyahmusic.player.playback.PlaybackService;
import app.voyahmusic.shared.R;
import app.voyahmusic.shared.lan.WifiLink;
import app.voyahmusic.shared.ui.MusicActivity;
import app.voyahmusic.shared.ui.UiKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static app.voyahmusic.shared.ui.UiKit.MATCH;
import static app.voyahmusic.shared.ui.UiKit.WRAP;

@UnstableApi
public final class PlayerActivity extends MusicActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable playbackChanged = this::renderPlayback;
    private final Runnable tick = this::tick;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.observe(playbackChanged);
            if (tracks != null) playback.setTracks(tracks);
            renderPlayback();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playback = null;
            renderPlayback();
        }
    };
    private PlayerViewModel model;
    private PlaybackService playback;
    private List<Track> tracks;
    private boolean bound, visible, receiving, dragging;
    private LinearLayout receiveBody;
    private TextView title, artist, elapsed, total, problem, emptyTitle, emptyHint;
    private ImageButton previous, play, next, shuffle, repeat;
    private Button receiveButton;
    private SeekBar seek;
    private TrackAdapter adapter;
    private String query = "", highlighted = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        model = new PlayerViewModel(this, ((PlayerApp) getApplication()).library());
        if (saved != null) {
            query = saved.getString("query", "");
            receiving = saved.getBoolean("receiving");
        }
        renderRoute();
    }

    @Override
    protected void renderRoute() {
        if (receiving) showReceiver();
        else super.renderRoute();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("receiving", receiving);
        state.putString("query", query);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        model.observe(this::tracks, this::renderReceiver);
        model.refresh();
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        visible = false;
        main.removeCallbacks(tick);
        model.observe(null, null);
        model.stopReceiving();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playback != null) playback.removeObserver(playbackChanged);
        if (bound) unbindService(connection);
        bound = false;
        playback = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        model.close();
        super.onDestroy();
    }

    @Override
    protected void openSettings() {
        main.removeCallbacks(tick);
        super.openSettings();
    }

    @Override
    public void onBackPressed() {
        if (receiving) {
            receiving = false;
            model.stopReceiving();
            showMain();
        } else {
            super.onBackPressed();
        }
    }

    private void openReceiver() {
        main.removeCallbacks(tick);
        receiving = true;
        showReceiver();
        model.startReceiving(null);
    }

    @Override
    protected void showMain() {
        LinearLayout root = page();
        LinearLayout header = heading(getString(R.string.player_name), false);
        settingsButton(header);
        ui.add(root, header, MATCH, WRAP, 0);

        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        boolean wide = width >= 720;
        boolean compact = !wide || height < 470;
        LinearLayout content = wide ? ui.row() : ui.column();
        content.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        fill.topMargin = ui.dp(12);
        root.addView(content, fill);
        LinearLayout player = playerCard(compact, width < 375);
        LinearLayout library = libraryCard(wide);
        if (wide) {
            int panelWidth = Math.min(445, Math.max(320, (int) (width * 0.36f)));
            content.addView(player, ui.dp(panelWidth), MATCH);
            LinearLayout.LayoutParams remainder = new LinearLayout.LayoutParams(0, MATCH, 1f);
            remainder.leftMargin = ui.dp(18);
            content.addView(library, remainder);
        } else {
            content.addView(player, MATCH, WRAP);
            LinearLayout.LayoutParams remainder = new LinearLayout.LayoutParams(MATCH, 0, 1f);
            remainder.topMargin = ui.dp(12);
            content.addView(library, remainder);
        }
        setContentView(root);
        root.requestFocus();
        filter();
        renderPlayback();
    }

    private LinearLayout playerCard(boolean compact, boolean narrow) {
        LinearLayout card = ui.card(compact ? 12 : 22);
        card.setBackground(ui.box(UiKit.PANEL, ui.selectedEdge, 22));
        TextView label = ui.text(getText(R.string.now_playing), 15, ui.accent, true);
        ui.center(card, label, WRAP, WRAP, 2);
        if (!compact) {
            card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            int art = getResources().getConfiguration().screenHeightDp >= 590 ? 126 : 82;
            ui.center(card, ui.emblem(art), ui.dp(art), ui.dp(art), 8);
        }
        title = ui.text("", compact ? 21 : 29, UiKit.TEXT, true);
        title.setGravity(Gravity.CENTER);
        ui.truncate(title, compact ? 1 : 2);
        ui.add(card, title, MATCH, WRAP, compact ? 10 : 20);
        artist = ui.text("", 15, UiKit.MUTED, false);
        artist.setGravity(Gravity.CENTER);
        ui.truncate(artist, 1);
        ui.add(card, artist, MATCH, WRAP, 6);
        if (!compact) card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setContentDescription(getText(R.string.playback_position));
        seek.setProgressTintList(ColorStateList.valueOf(ui.accent));
        seek.setThumbTintList(ColorStateList.valueOf(ui.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(ui.edge));
        seek.setPadding(ui.dp(7), 0, ui.dp(7), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekTo(bar.getProgress());
                dragging = false;
                updatePosition();
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && playback != null) {
                    elapsed.setText(time(playback.duration() * progress / 1000));
                    if (!dragging) seekTo(progress);
                }
            }
        });
        ui.add(card, seek, MATCH, ui.dp(38), compact ? 4 : 10);
        LinearLayout timestamps = ui.row();
        timestamps.setPadding(ui.dp(5), 0, ui.dp(5), 0);
        elapsed = ui.text("0:00", 14, UiKit.MUTED, false);
        total = ui.text("0:00", 14, UiKit.MUTED, false);
        timestamps.addView(elapsed, ui.weighted());
        timestamps.addView(total, WRAP, WRAP);
        ui.add(card, timestamps, MATCH, WRAP, 0);

        LinearLayout controls = ui.row();
        controls.setGravity(Gravity.CENTER);
        shuffle = ui.iconButton(UiKit.Icon.SHUFFLE, getText(R.string.shuffle), false,
                () -> { if (playback != null) playback.shuffle(); });
        previous = ui.iconButton(UiKit.Icon.PREVIOUS, getText(R.string.previous), false,
                () -> { if (playback != null) playback.previous(); });
        play = ui.iconButton(UiKit.Icon.PLAY, getText(R.string.play), true,
                () -> { if (playback != null) playback.toggle(); });
        next = ui.iconButton(UiKit.Icon.NEXT, getText(R.string.next), false,
                () -> { if (playback != null) playback.next(); });
        repeat = ui.iconButton(UiKit.Icon.REPEAT, getText(R.string.repeat_off), false,
                () -> { if (playback != null) playback.repeat(); });
        int touch = narrow ? 42 : 48;
        controls.addView(shuffle, ui.dp(touch), ui.dp(48));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(previous, ui.dp(touch), ui.dp(48));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(play, ui.dp(narrow ? 56 : 70), ui.dp(compact ? 51 : 58));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(next, ui.dp(touch), ui.dp(48));
        ui.gap(controls, narrow ? 4 : 6);
        controls.addView(repeat, ui.dp(touch), ui.dp(48));
        ui.add(card, controls, MATCH, WRAP, compact ? 10 : 16);
        problem = ui.text(getText(R.string.playback_error), 14, UiKit.ERROR, false);
        problem.setVisibility(View.GONE);
        ui.add(card, problem, MATCH, WRAP, 9);
        return card;
    }

    private LinearLayout libraryCard(boolean wide) {
        LinearLayout library = ui.card(wide ? 16 : 12);
        LinearLayout header = ui.row();
        TextView label = ui.text(getText(R.string.library), wide ? 24 : 20, UiKit.TEXT, true);
        header.addView(label, ui.weighted());
        ui.gap(header, 6);
        receiveButton = ui.button(getText(R.string.receive), false, this::openReceiver);
        header.addView(receiveButton, WRAP, WRAP);
        ui.add(library, header, MATCH, WRAP, 0);
        LinearLayout search = ui.row();
        search.setBackground(ui.box(UiKit.SURFACE, ui.edge, 13));
        search.setPadding(ui.dp(11), 0, ui.dp(12), 0);
        search.addView(ui.mark(UiKit.Icon.SEARCH, 19), ui.dp(24), ui.dp(24));
        ui.gap(search, 8);
        EditText searchField = new EditText(this);
        searchField.setTextColor(UiKit.TEXT);
        searchField.setTextSize(16);
        searchField.setHintTextColor(UiKit.MUTED);
        searchField.setHint(R.string.search_library);
        searchField.setSingleLine();
        searchField.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchField.setPadding(0, ui.dp(8), 0, ui.dp(8));
        searchField.setText(query);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                filter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        search.addView(searchField, new LinearLayout.LayoutParams(0, MATCH, 1f));
        ui.add(library, search, MATCH, ui.dp(48), 10);

        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams listSpace = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        listSpace.topMargin = ui.dp(8);
        library.addView(body, listSpace);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        body.addView(list, MATCH, MATCH);
        LinearLayout empty = ui.column();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
        emptyTitle = ui.text("", 19, UiKit.TEXT, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyHint = ui.text("", 15, UiKit.MUTED, false);
        emptyHint.setGravity(Gravity.CENTER);
        ui.add(empty, emptyTitle, MATCH, WRAP, 0);
        ui.add(empty, emptyHint, MATCH, WRAP, 7);
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER));
        list.setEmptyView(empty);
        return library;
    }

    private void tracks(List<Track> value) {
        tracks = value;
        if (playback != null) playback.setTracks(value);
        if (!settings && !receiving) filter();
        renderPlayback();
    }

    private void filter() {
        if (adapter == null) return;
        List<Track> result = new ArrayList<>();
        String term = query.trim().toLowerCase(Locale.ROOT);
        if (tracks != null) {
            for (Track track : tracks) {
                if (term.isEmpty() || track.matches(term)) result.add(track);
            }
        }
        emptyTitle.setText(tracks == null ? R.string.loading_library
                : term.isEmpty() ? R.string.empty_library : R.string.no_matches);
        emptyHint.setVisibility(tracks != null && term.isEmpty() ? View.VISIBLE : View.GONE);
        emptyHint.setText(R.string.empty_library_hint);
        boolean emphasizeReceive = tracks != null && tracks.isEmpty();
        if (receiveButton.isSelected() != emphasizeReceive) {
            receiveButton.setSelected(emphasizeReceive);
            ui.styleButton(receiveButton, emphasizeReceive);
        }
        adapter.rows = result;
        adapter.notifyDataSetChanged();
    }

    private void renderPlayback() {
        if (!visible || settings || receiving || title == null) return;
        PlaybackService.State state = playback == null ? null : playback.state();
        String active = state == null ? "" : state.id();
        Track track = currentTrack(active);
        if (!highlighted.equals(active)) {
            highlighted = active;
            adapter.notifyDataSetChanged();
        }
        boolean hasTrack = track != null;
        title.setText(hasTrack ? track.title() : getText(R.string.player_empty));
        String details = hasTrack ? details(track) : getString(R.string.player_empty_hint);
        artist.setText(details);
        artist.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        problem.setVisibility(state != null && state.failed() ? View.VISIBLE : View.GONE);
        boolean playing = state != null && state.wantsPlay();
        play.setImageDrawable(ui.glyph(playing ? UiKit.Icon.PAUSE : UiKit.Icon.PLAY, ui.onAccent, 26));
        play.setContentDescription(getText(playing ? R.string.pause : R.string.play));
        play.setTooltipText(play.getContentDescription());
        ui.enabled(play, hasTrack);
        ui.enabled(previous, hasTrack);
        ui.enabled(next, hasTrack && playback.hasNext());
        ui.enabled(shuffle, hasTrack);
        ui.enabled(repeat, hasTrack);
        option(shuffle, state != null && state.shuffle(), UiKit.Icon.SHUFFLE, R.string.shuffle);
        int loop = state == null ? Player.REPEAT_MODE_OFF : state.repeat();
        option(repeat, loop != Player.REPEAT_MODE_OFF,
                loop == Player.REPEAT_MODE_ONE ? UiKit.Icon.REPEAT_ONE : UiKit.Icon.REPEAT,
                loop == Player.REPEAT_MODE_ONE ? R.string.repeat_one
                        : loop == Player.REPEAT_MODE_ALL ? R.string.repeat_all : R.string.repeat_off);
        updatePosition();
        main.removeCallbacks(tick);
        if (state != null && state.playing()) main.postDelayed(tick, 500);
    }

    private Track currentTrack(String id) {
        if (tracks == null || id.isEmpty()) return null;
        for (Track track : tracks) {
            if (track.id().equals(id)) return track;
        }
        return null;
    }

    private void option(ImageButton button, boolean active, UiKit.Icon icon, int description) {
        button.setSelected(active);
        button.setImageDrawable(ui.glyph(icon, active ? ui.accent : UiKit.TEXT, 21));
        button.setBackground(ui.actionBackground(active ? ui.selected : UiKit.SURFACE, active ? ui.selectedEdge : ui.edge, 14));
        button.setContentDescription(getText(description));
        button.setTooltipText(getText(description));
    }

    private void tick() {
        if (!visible || settings || receiving || playback == null || !playback.state().playing()) return;
        updatePosition();
        main.postDelayed(tick, 500);
    }

    private void updatePosition() {
        if (dragging) return;
        long duration = playback == null ? 0 : playback.duration();
        long position = playback == null ? 0 : playback.position();
        seek.setEnabled(duration > 0);
        int progress = duration == 0 ? 0 : (int) Math.min(1000, position * 1000 / duration);
        if (seek.getProgress() != progress) seek.setProgress(progress);
        setTime(elapsed, position);
        setTime(total, duration);
    }

    private void seekTo(int progress) {
        if (playback != null) playback.seek(playback.duration() * progress / 1000);
    }

    private void setTime(TextView view, long value) {
        String text = time(value);
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private static String time(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String details(Track track) {
        if (track.artist().isEmpty()) return track.album();
        if (track.album().isEmpty()) return track.artist();
        return track.artist() + " · " + track.album();
    }

    private void showReceiver() {
        LinearLayout root = page();
        ui.add(root, heading(getString(R.string.receive), true), MATCH, WRAP, 0);
        ScrollView scroll = new ScrollView(this);
        ui.add(root, scroll, MATCH, MATCH, 12);
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, MATCH, WRAP);
        receiveBody = ui.card(22);
        int width = getResources().getConfiguration().screenWidthDp;
        frame.addView(receiveBody, new FrameLayout.LayoutParams(width > 780 ? ui.dp(720) : MATCH,
                WRAP, Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        renderReceiver(model.receiver());
    }

    private void renderReceiver(PlayerViewModel.Receiver state) {
        boolean waiting = receiving && state.state() == PlayerViewModel.Receiving.WAITING;
        if (waiting) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!receiving || receiveBody == null) return;
        receiveBody.removeAllViews();
        TextView instructions = ui.text(getText(R.string.receive_instructions), 17, UiKit.MUTED, false);
        instructions.setLineSpacing(ui.dp(4), 1f);
        ui.add(receiveBody, instructions, MATCH, WRAP, 0);
        if (waiting) {
            LinearLayout values = getResources().getConfiguration().screenWidthDp >= 650 ? ui.row() : ui.column();
            LinearLayout address = receiveValue(R.string.car_address, state.link().address().getHostAddress());
            LinearLayout code = receiveValue(R.string.pairing_code, state.code());
            if (values.getOrientation() == LinearLayout.HORIZONTAL) {
                values.addView(address, ui.weighted());
                ui.gap(values, 12);
                values.addView(code, ui.weighted());
            } else {
                ui.add(values, address, MATCH, WRAP, 0);
                ui.add(values, code, MATCH, WRAP, 10);
            }
            ui.add(receiveBody, values, MATCH, WRAP, 18);
            if (state.networks().size() > 1) {
                ui.add(receiveBody, ui.button(getText(R.string.car_address), false, () -> chooseLink(state)),
                        WRAP, WRAP, 12);
            }
        }
        int notice = switch (state.state()) {
            case INACTIVE, STOPPED -> R.string.receive_expired;
            case OPENING -> R.string.opening_receiver;
            case WAITING -> R.string.receive_active;
            case NO_NETWORK -> R.string.receive_no_wifi;
            case FAILED -> R.string.receive_failed;
        };
        ui.add(receiveBody, ui.text(getText(notice), 16, waiting ? ui.accent : UiKit.TEXT, true), MATCH, WRAP, 18);
        if (!state.latest().isEmpty()) {
            TextView done = ui.text(getString(R.string.received_track, state.latest()), 15, UiKit.MUTED, false);
            done.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.add(receiveBody, done, MATCH, WRAP, 10);
        }
        if (!waiting && state.state() != PlayerViewModel.Receiving.OPENING) {
            ui.add(receiveBody, ui.button(getText(R.string.start_receiving), true,
                    () -> model.startReceiving(state.link())), WRAP, WRAP, 16);
        }
    }

    private void chooseLink(PlayerViewModel.Receiver state) {
        List<WifiLink> networks = state.networks();
        CharSequence[] addresses = new CharSequence[networks.size()];
        for (int i = 0; i < addresses.length; i++) addresses[i] = networks.get(i).address().getHostAddress();
        new AlertDialog.Builder(this).setTitle(R.string.car_address)
                .setSingleChoiceItems(addresses, networks.indexOf(state.link()), (chooser, index) -> {
                    chooser.dismiss();
                    if (!state.link().equals(networks.get(index))) model.startReceiving(networks.get(index));
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private LinearLayout receiveValue(int label, String value) {
        LinearLayout box = ui.card(16);
        box.setBackground(ui.box(UiKit.SURFACE, ui.edge, 14));
        ui.add(box, ui.text(getText(label), 14, UiKit.MUTED, true), MATCH, WRAP, 0);
        TextView number = ui.text(value, 28, UiKit.TEXT, true);
        number.setTypeface(Typeface.MONOSPACE);
        number.setTextIsSelectable(true);
        ui.add(box, number, MATCH, WRAP, 8);
        return box;
    }

    private void delete(Track track) {
        new AlertDialog.Builder(this).setTitle(R.string.delete_track).setMessage(getString(R.string.delete_hint, track.title()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_track, (dialog, item) -> model.delete(track,
                        () -> Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show())).show();
    }

    private final class TrackAdapter extends BaseAdapter {
        private List<Track> rows = Collections.emptyList();

        @Override public int getCount() { return rows.size(); }
        @Override public Track getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            TrackRow item = reusable instanceof TrackRow row ? row : new TrackRow();
            item.bind(getItem(position));
            return item;
        }
    }

    private final class TrackRow extends LinearLayout {
        private final LinearLayout row;
        private final TextView name, metadata, duration;
        private final ImageButton options;
        private final android.widget.ImageView mark;
        private Track track;

        TrackRow() {
            super(PlayerActivity.this);
            setOrientation(VERTICAL);
            setPadding(0, 0, 0, ui.dp(7));
            row = ui.row();
            row.setMinimumHeight(ui.dp(62));
            row.setPadding(ui.dp(11), ui.dp(5), ui.dp(5), ui.dp(5));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> { if (playback != null) playback.select(track.id()); });
            mark = ui.mark(UiKit.Icon.NOTE, 22);
            row.addView(mark, ui.dp(30), ui.dp(36));
            ui.gap(row, 8);
            LinearLayout description = ui.column();
            name = ui.text("", 17, UiKit.TEXT, true);
            metadata = ui.text("", 14, UiKit.MUTED, false);
            ui.truncate(name, 1);
            ui.truncate(metadata, 1);
            ui.add(description, name, MATCH, WRAP, 0);
            ui.add(description, metadata, MATCH, WRAP, 4);
            row.addView(description, ui.weighted());
            duration = ui.text("", 14, UiKit.MUTED, false);
            ui.gap(row, 6);
            row.addView(duration, WRAP, WRAP);
            options = ui.iconButton(UiKit.Icon.MORE, getText(R.string.track_options), false, this::openOptions);
            row.addView(options, ui.dp(48), ui.dp(48));
            addView(row, MATCH, WRAP);
        }

        private void openOptions() {
            PopupMenu popup = new PopupMenu(PlayerActivity.this, options);
            popup.getMenu().add(R.string.delete_track);
            Track target = track;
            popup.setOnMenuItemClickListener(option -> {
                delete(target);
                return true;
            });
            popup.show();
        }

        void bind(Track track) {
            this.track = track;
            boolean active = highlighted.equals(track.id());
            row.setBackground(ui.actionBackground(active ? ui.selected : UiKit.SURFACE,
                    active ? ui.selectedEdge : ui.edge, 13));
            row.setSelected(active);
            mark.setImageDrawable(ui.glyph(active ? UiKit.Icon.PLAY : UiKit.Icon.NOTE, active ? ui.accent : UiKit.MUTED, 20));
            name.setText(track.title());
            name.setTextColor(active ? ui.accent : UiKit.TEXT);
            String detail = details(track);
            metadata.setText(detail);
            metadata.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            duration.setText(track.duration() > 0 ? time(track.duration()) : "");
        }
    }
}
