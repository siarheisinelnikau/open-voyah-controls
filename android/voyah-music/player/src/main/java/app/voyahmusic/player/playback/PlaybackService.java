package app.voyahmusic.player.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import app.voyahmusic.player.domain.Track;
import app.voyahmusic.player.ui.PlayerActivity;
import app.voyahmusic.shared.R;
import app.voyahmusic.shared.data.MusicPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@UnstableApi
public final class PlaybackService extends Service {
    public record State(String id, boolean wantsPlay, boolean playing, boolean shuffle, int repeat, boolean failed) {}
    private static final String CHANNEL = "music_playback";
    private static final int NOTIFICATION = 1;
    private static final String PREVIOUS = "previous";
    private static final String TOGGLE = "toggle";
    private static final String NEXT = "next";

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new HashSet<>();
    private final Runnable checkpoint = () -> {
        savePosition();
        scheduleCheckpoint();
    };
    private ExoPlayer player;
    private MediaSession session;
    private PendingIntent open;
    private SharedPreferences resume;
    private boolean started;
    private boolean foreground;
    private boolean syncing;

    public final class LocalBinder extends Binder {
        public PlaybackService service() {
            return PlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        resume = getSharedPreferences("playback", MODE_PRIVATE);
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.setShuffleModeEnabled(resume.getBoolean("shuffle", false));
        player.setRepeatMode(resume.getInt("repeat", Player.REPEAT_MODE_OFF));
        player.addListener(new Player.Listener() {
            @Override
            public void onEvents(Player source, Player.Events events) {
                if (!syncing) publish(events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                        || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                        || events.contains(Player.EVENT_POSITION_DISCONTINUITY));
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                player.pause();
            }
        });
        open = PendingIntent.getActivity(this, 0, new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession(this, "Voyah Music");
        session.setSessionActivity(open);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { player.pause(); }
            @Override public void onSeekTo(long position) { seek(position); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onStop() { player.pause(); }
        });
        Context localized = MusicPreferences.localized(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, localized.getString(R.string.now_playing), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        String action = intent == null ? null : intent.getAction();
        if (PREVIOUS.equals(action)) previous();
        if (TOGGLE.equals(action)) toggle();
        if (NEXT.equals(action)) next();
        publish(false);
        return START_NOT_STICKY;
    }

    public void setTracks(List<Track> tracks) {
        if (player.getMediaItemCount() == 0 && tracks.isEmpty()) return;
        syncing = true;
        try {
            if (player.getMediaItemCount() == 0) {
                String last = resume.getString("track", "");
                int start = 0;
                for (int i = 0; i < tracks.size(); i++) {
                    if (tracks.get(i).id().equals(last)) start = i;
                    player.addMediaItem(item(tracks.get(i)));
                }
                long position = tracks.get(start).id().equals(last) ? resume.getLong("position", 0) : 0;
                player.seekTo(start, position);
                player.prepare();
            } else {
                Set<String> ids = new HashSet<>();
                for (Track track : tracks) ids.add(track.id());
                for (int i = player.getMediaItemCount() - 1; i >= 0; i--) {
                    if (!ids.contains(player.getMediaItemAt(i).mediaId)) player.removeMediaItem(i);
                }
                for (int i = 0; i < tracks.size(); i++) {
                    Track track = tracks.get(i);
                    int existing = indexOf(track.id(), i);
                    if (existing == -1) player.addMediaItem(i, item(track));
                    else if (existing != i) player.moveMediaItem(existing, i);
                }
                if (tracks.isEmpty()) player.pause();
            }
        } finally {
            syncing = false;
        }
        publish(true);
    }

    private int indexOf(String id, int start) {
        for (int i = start; i < player.getMediaItemCount(); i++) {
            if (player.getMediaItemAt(i).mediaId.equals(id)) return i;
        }
        return -1;
    }

    private static MediaItem item(Track track) {
        return new MediaItem.Builder().setMediaId(track.id()).setUri(Uri.fromFile(track.file()))
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title()).setArtist(track.artist()).setAlbumTitle(track.album()).build())
                .build();
    }

    public void select(String id) {
        int index = indexOf(id, 0);
        if (index < 0) return;
        if (index != player.getCurrentMediaItemIndex()) player.seekToDefaultPosition(index);
        play();
    }

    public void toggle() {
        if (state().wantsPlay()) player.pause();
        else play();
    }

    private void play() {
        if (player.getCurrentMediaItem() == null) return;
        if (!started) startService(new Intent(this, PlaybackService.class));
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        player.play();
    }

    public void previous() {
        player.seekToPrevious();
    }

    public void next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem();
    }

    public void seek(long position) {
        if (duration() > 0) player.seekTo(Math.max(0, Math.min(position, duration())));
    }

    public void shuffle() {
        player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
        resume.edit().putBoolean("shuffle", player.getShuffleModeEnabled()).apply();
    }

    public void repeat() {
        int next = switch (player.getRepeatMode()) {
            case Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL;
            case Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE;
            case Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF;
            default -> Player.REPEAT_MODE_OFF;
        };
        player.setRepeatMode(next);
        resume.edit().putInt("repeat", next).apply();
    }

    public long position() { return Math.max(0, player.getCurrentPosition()); }
    public long duration() { return Math.max(0, player.getDuration()); }
    public boolean hasNext() { return player.hasNextMediaItem(); }

    public State state() {
        MediaItem item = player.getCurrentMediaItem();
        boolean wantsPlay = item != null && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED && player.getPlayerError() == null;
        return new State(item == null ? "" : item.mediaId, wantsPlay, player.isPlaying(),
                player.getShuffleModeEnabled(), player.getRepeatMode(), player.getPlayerError() != null);
    }

    public void observe(Runnable listener) { listeners.add(listener); }
    public void removeObserver(Runnable listener) { listeners.remove(listener); }

    private void publish(boolean checkpoint) {
        State state = state();
        if (checkpoint) savePosition();
        scheduleCheckpoint();
        MediaItem current = player.getCurrentMediaItem();
        session.setActive(current != null && (started || state.wantsPlay()));
        if (current != null) {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, text(current.mediaMetadata.title))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, text(current.mediaMetadata.artist))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, text(current.mediaMetadata.albumTitle))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build();
            session.setMetadata(metadata);
            int status = state.failed() ? PlaybackState.STATE_ERROR : state.playing() ? PlaybackState.STATE_PLAYING
                    : state.wantsPlay() ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED;
            long actions = PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
            if (hasNext()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
            session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                    .setState(status, position(), state.playing() ? 1f : 0f).build());
        }
        if (started && state.wantsPlay()) {
            startForeground(NOTIFICATION, notification(current));
            foreground = true;
        } else if (!state.wantsPlay()) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
            }
            if (started) {
                started = false;
                stopSelf();
                session.setActive(false);
            }
        }
        for (Runnable listener : listeners) listener.run();
    }

    @SuppressWarnings("deprecation")
    private Notification notification(MediaItem current) {
        Context localized = MusicPreferences.localized(this);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_music_notification)
                .setContentTitle(current.mediaMetadata.title)
                .setContentText(current.mediaMetadata.artist)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(android.R.drawable.ic_media_previous, localized.getString(R.string.previous), command(PREVIOUS))
                .addAction(android.R.drawable.ic_media_pause, localized.getString(R.string.pause), command(TOGGLE))
                .addAction(android.R.drawable.ic_media_next, localized.getString(R.string.next), command(NEXT))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private PendingIntent command(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private void scheduleCheckpoint() {
        main.removeCallbacks(checkpoint);
        if (player.isPlaying()) main.postDelayed(checkpoint, 15_000);
    }

    private void savePosition() {
        MediaItem item = player.getCurrentMediaItem();
        if (item != null) resume.edit().putString("track", item.mediaId).putLong("position", position()).apply();
    }

    @Override
    public void onDestroy() {
        savePosition();
        main.removeCallbacks(checkpoint);
        listeners.clear();
        player.release();
        session.release();
        super.onDestroy();
    }
}
