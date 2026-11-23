package app.voyahradio.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Network;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import app.voyahradio.R;
import app.voyahradio.RadioApp;
import app.voyahradio.data.NowPlayingFeed;
import app.voyahradio.data.RadioCatalog;
import app.voyahradio.data.RadioPreferences;
import app.voyahradio.data.StreamProbe;
import app.voyahradio.domain.Availability;
import app.voyahradio.domain.CatalogOptions;
import app.voyahradio.domain.Playback;
import app.voyahradio.domain.RadioTab;
import app.voyahradio.domain.Station;
import app.voyahradio.domain.Track;
import app.voyahradio.ui.RadioActivity;
import app.voyahradio.ui.RadioLocale;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static app.voyahradio.domain.Playback.Status.*;

@OptIn(markerClass = UnstableApi.class)
public final class RadioService extends Service {
    public static final String PLAY = "app.voyahradio.PLAY";
    public static final String STATION_ID = "station_id";
    public static final String TAB = "tab";
    private static final String PAUSE = "app.voyahradio.PAUSE";
    private static final String PREVIOUS = "app.voyahradio.PREVIOUS";
    private static final String NEXT = "app.voyahradio.NEXT";
    private static final String CHANNEL = "radio_playback";
    private static final int NOTIFICATION_ID = 10;
    private static final long STREAM_TIMEOUT_MS = 12000;
    private static final long TRACK_REFRESH_MS = 25000;
    private static final String TAG = "VoyahRadio";

    public final class LocalBinder extends Binder {
        public RadioService service() {
            return RadioService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final AudioMeter meter = new AudioMeter();
    private final NowPlayingFeed trackFeed = new NowPlayingFeed();
    private final ExecutorService trackExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Consumer<Playback>> listeners = new LinkedHashSet<>();
    private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()) && foreground) pause();
        }
    };

    private RadioCatalog catalog;
    private RadioPreferences preferences;
    private StreamProbe streamProbe;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private MediaSession session;
    private PendingIntent openApp;
    private Station station;
    private RadioTab currentTab = RadioTab.BELARUS;
    private Track track;
    private boolean hasStreamTrack;
    private Future<?> trackLookup;
    private Runnable scheduledTrackLookup;
    private int trackGeneration;
    private Bitmap mediaArt;
    private Context notificationStrings;
    private ExoPlayer player;
    private Playback.Status status = READY;
    private boolean foreground;
    private boolean focused;
    private boolean waitingForFocus;
    private boolean retryUsed;
    private boolean allUrlsRejected;
    private Network startingNetwork;
    private int streamIndex;
    private Runnable deadline;
    private Runnable retry;

    @Override public void onCreate() {
        super.onCreate();
        RadioApp app = (RadioApp) getApplication();
        catalog = app.catalog();
        preferences = app.preferences();
        streamProbe = app.streamProbe();
        station = catalog.find(preferences.lastStation());
        if (station != null) currentTab = station.group;
        audio = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::focusChanged, handler)
                .build();

        updateNotificationLanguage();
        Drawable icon = getDrawable(R.drawable.radio_icon);
        if (icon != null) {
            mediaArt = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, mediaArt.getWidth(), mediaArt.getHeight());
            icon.draw(new Canvas(mediaArt));
        }
        openApp = PendingIntent.getActivity(this, 0, new Intent(this, RadioActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession(this, TAG);
        session.setSessionActivity(openApp);
        session.setPlaybackToLocal(audioAttributes);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                if (foreground && !snapshot().active()) play(station);
            }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { if (foreground) step(1); }
            @Override public void onSkipToPrevious() { if (foreground) step(-1); }
        }, handler);
        updateMetadata();
        updateSession();

        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, filter);
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            if (!foreground) stopSelf(startId);
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case PLAY -> {
                Station requested = catalog.find(intent.getStringExtra(STATION_ID));
                if (requested == null) requested = catalog.all().get(0);
                currentTab = RadioTab.parse(intent.getStringExtra(TAB), requested.group);
                play(requested);
            }
            case PAUSE -> pause();
            case PREVIOUS -> { if (foreground) step(-1); }
            case NEXT -> { if (foreground) step(1); }
            default -> { if (!foreground) stopSelf(startId); }
        }
        return START_NOT_STICKY;
    }

    public void observe(Consumer<Playback> listener) {
        listeners.add(listener);
        listener.accept(snapshot());
    }

    public void removeObserver(Consumer<Playback> listener) {
        listeners.remove(listener);
    }

    public void pause() {
        if (foreground) finishPlayback(PAUSED);
    }

    public AudioMeter meter() {
        return meter;
    }

    public void refreshLanguage() {
        if (notificationStrings.getResources().getConfiguration().getLocales().get(0)
                .getLanguage().equals(preferences.language())) return;
        updateNotificationLanguage();
        if (foreground) startForeground(NOTIFICATION_ID, notification());
    }

    private void updateNotificationLanguage() {
        notificationStrings = RadioLocale.apply(this, preferences);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, notificationStrings.getString(R.string.on_air_channel),
                        NotificationManager.IMPORTANCE_LOW));
    }

    private void step(int direction) {
        CatalogOptions options = preferences.catalogOptions();
        RadioTab tab = options.visibleTab(currentTab);
        List<Station> playlist = options.visibleStations(catalog.all(), tab, preferences.favorites());
        if (playlist.isEmpty()) return;
        int index = playlist.indexOf(station);
        int next = index < 0 ? (direction > 0 ? 0 : playlist.size() - 1)
                : Math.floorMod(index + direction, playlist.size());
        play(playlist.get(next));
    }

    private void play(Station requested) {
        if (requested == null) return;
        if (foreground && requested == station && snapshot().active()) return;

        cancelRetry();
        releasePlayer();
        station = requested;
        preferences.rememberStation(station.id);
        streamIndex = 0;
        retryUsed = false;
        allUrlsRejected = true;
        startingNetwork = streamProbe.validatedNetwork();
        updateMetadata();
        foreground = true;
        status = waitingForFocus ? PAUSED : CONNECTING;
        session.setActive(true);
        updateSession();
        startForeground(NOTIFICATION_ID, notification());
        notifyObservers();

        if (waitingForFocus) return;
        if (!focused) {
            focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        if (focused) connect(CONNECTING);
        else finishPlayback(FOCUS_ERROR);
    }

    private void connect(Playback.Status connectingStatus) {
        retry = null;
        if (!foreground || waitingForFocus) return;
        publish(connectingStatus);

        DefaultHttpDataSource.Factory network = new DefaultHttpDataSource.Factory()
                .setUserAgent(StreamProbe.USER_AGENT)
                .setConnectTimeoutMs(7000)
                .setReadTimeoutMs(9000)
                .setAllowCrossProtocolRedirects(true);
        ExoPlayer candidate = new ExoPlayer.Builder(this, new MeteringRenderersFactory(this, meter))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(network))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(4000, 12000, 1000, 2000)
                        .build())
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player = candidate;
        candidate.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (candidate != player) return;
                if (state == Player.STATE_BUFFERING && status == LIVE) {
                    cancelTrackLookup();
                    publish(BUFFERING);
                    armDeadline(candidate);
                } else if (state == Player.STATE_ENDED) {
                    streamFailed(candidate, "stream ended", false);
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (candidate == player && playing) {
                    cancelDeadline();
                    preferences.recordAvailability(station.id, Availability.AVAILABLE, System.currentTimeMillis());
                    publish(LIVE);
                    scheduleTrackLookup(0);
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (candidate == player) {
                    streamFailed(candidate, error.getErrorCodeName(), endpointRejected(error));
                }
            }

            @Override public void onMediaMetadataChanged(androidx.media3.common.MediaMetadata metadata) {
                if (candidate != player) return;
                Track updated = Track.from(metadata.title, metadata.artist, station.name);
                if (updated != null) {
                    hasStreamTrack = true;
                    cancelTrackLookup();
                    setTrack(updated);
                } else if (hasStreamTrack) {
                    hasStreamTrack = false;
                    setTrack(null);
                    scheduleTrackLookup(0);
                }
            }
        });
        candidate.setMediaItem(MediaItem.fromUri(station.streams.get(streamIndex)));
        candidate.setPlayWhenReady(true);
        armDeadline(candidate);
        candidate.prepare();
    }

    private boolean endpointRejected(PlaybackException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException response) {
                return response.dataSpec.uri.toString().equals(station.streams.get(streamIndex))
                        && Availability.isDefinitiveHttpFailure(response.responseCode);
            }
        }
        return false;
    }

    private void setTrack(Track updated) {
        if (Objects.equals(updated, track)) return;
        track = updated;
        updateMetadata();
        if (foreground) startForeground(NOTIFICATION_ID, notification());
        notifyObservers();
    }

    private void scheduleTrackLookup(long delay) {
        if (!foreground || status != LIVE || hasStreamTrack || station.trackSourceId <= 0
                || scheduledTrackLookup != null || trackLookup != null) return;
        Station requested = station;
        int generation = trackGeneration;
        scheduledTrackLookup = () -> {
            scheduledTrackLookup = null;
            trackLookup = trackExecutor.submit(() -> {
                Track result = trackFeed.read(requested.trackSourceId, requested.name);
                handler.post(() -> {
                    if (generation != trackGeneration) return;
                    trackLookup = null;
                    setTrack(result);
                    scheduleTrackLookup(TRACK_REFRESH_MS);
                });
            });
        };
        handler.postDelayed(scheduledTrackLookup, delay);
    }

    private void cancelTrackLookup() {
        trackGeneration++;
        if (scheduledTrackLookup != null) handler.removeCallbacks(scheduledTrackLookup);
        scheduledTrackLookup = null;
        if (trackLookup != null) trackLookup.cancel(true);
        trackLookup = null;
    }

    private void streamFailed(ExoPlayer source, String reason, boolean rejected) {
        if (source != player) return;
        allUrlsRejected &= rejected && streamProbe.isCurrent(startingNetwork);
        Log.w(TAG, "Stream failed for " + station.id + " (source " + (streamIndex + 1) + "): " + reason);
        releasePlayer();
        if (streamIndex + 1 < station.streams.size()) {
            streamIndex++;
        } else if ((status == LIVE || status == BUFFERING) && !retryUsed) {
            retryUsed = true;
            streamIndex = 0;
        } else {
            if (allUrlsRejected) {
                preferences.recordAvailability(station.id, Availability.UNAVAILABLE, System.currentTimeMillis());
            }
            finishPlayback(streamProbe.validatedNetwork() == null ? NETWORK_ERROR : STREAM_ERROR);
            return;
        }

        publish(RECONNECTING);
        retry = () -> connect(RECONNECTING);
        handler.postDelayed(retry, 800);
    }

    private void focusChanged(int change) {
        if (!foreground) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS -> finishPlayback(PAUSED);
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                waitingForFocus = true;
                cancelRetry();
                releasePlayer();
                publish(PAUSED);
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player != null) player.setVolume(0.2f);
            }
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (waitingForFocus) {
                    waitingForFocus = false;
                    connect(CONNECTING);
                } else if (player != null) {
                    player.setVolume(1f);
                }
            }
        }
    }

    private void finishPlayback(Playback.Status result) {
        cancelRetry();
        releasePlayer();
        if (focused) audio.abandonAudioFocusRequest(focusRequest);
        focused = false;
        waitingForFocus = false;
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        publish(result);
        session.setActive(false);
        stopSelf();
    }

    private Playback snapshot() {
        return new Playback(station == null ? "" : station.id, status, track);
    }

    private void publish(Playback.Status updated) {
        if (status == updated) return;
        status = updated;
        updateSession();
        if (foreground) startForeground(NOTIFICATION_ID, notification());
        notifyObservers();
    }

    private void notifyObservers() {
        Playback state = snapshot();
        for (Consumer<Playback> listener : new ArrayList<>(listeners)) listener.accept(state);
    }

    private void updateMetadata() {
        if (station == null) return;
        String title = track == null ? station.name : track.title;
        String artist = track == null || track.artist.isEmpty() ? station.name : track.artist;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, station.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, station.name)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, track == null ? station.label : artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, station.name)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1);
        if (mediaArt != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, mediaArt);
        session.setMetadata(metadata.build());
    }

    private void updateSession() {
        int platformState = switch (status) {
            case CONNECTING, BUFFERING, RECONNECTING -> PlaybackState.STATE_BUFFERING;
            case LIVE -> PlaybackState.STATE_PLAYING;
            case PAUSED -> PlaybackState.STATE_PAUSED;
            case STREAM_ERROR, NETWORK_ERROR, FOCUS_ERROR -> PlaybackState.STATE_ERROR;
            case READY -> PlaybackState.STATE_NONE;
        };
        long actions = PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(platformState, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        status == LIVE ? 1f : 0f)
                .build());
    }

    private Notification notification() {
        boolean active = snapshot().active();
        String subtitle = track == null ? notificationStrings.getString(PlaybackText.label(status))
                : track.artist.isEmpty() ? station.name
                        : notificationStrings.getString(R.string.track_notification, track.artist, station.name);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(track == null ? station.name : track.title)
                .setContentText(subtitle)
                .setContentIntent(openApp)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(action(android.R.drawable.ic_media_previous, R.string.previous, PREVIOUS))
                .addAction(action(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        active ? R.string.pause : R.string.play, active ? PAUSE : PLAY))
                .addAction(action(android.R.drawable.ic_media_next, R.string.next, NEXT))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action action(int icon, int title, String command) {
        Intent intent = new Intent(this, RadioService.class).setAction(command);
        if (PLAY.equals(command)) intent.putExtra(STATION_ID, station.id).putExtra(TAB, currentTab.name());
        PendingIntent pending = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(icon, notificationStrings.getString(title), pending).build();
    }

    private void armDeadline(ExoPlayer source) {
        cancelDeadline();
        deadline = () -> streamFailed(source, "timeout", false);
        handler.postDelayed(deadline, STREAM_TIMEOUT_MS);
    }

    private void cancelDeadline() {
        if (deadline != null) handler.removeCallbacks(deadline);
        deadline = null;
    }

    private void cancelRetry() {
        if (retry != null) handler.removeCallbacks(retry);
        retry = null;
    }

    private void releasePlayer() {
        cancelDeadline();
        cancelTrackLookup();
        hasStreamTrack = false;
        ExoPlayer released = player;
        player = null;
        if (released != null) released.release();
        if (track != null) {
            track = null;
            updateMetadata();
        }
    }

    @Override public void onDestroy() {
        unregisterReceiver(noisyReceiver);
        cancelRetry();
        releasePlayer();
        trackExecutor.shutdownNow();
        if (focused) audio.abandonAudioFocusRequest(focusRequest);
        session.release();
        listeners.clear();
        super.onDestroy();
    }
}
