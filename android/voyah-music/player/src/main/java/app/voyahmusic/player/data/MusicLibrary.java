package app.voyahmusic.player.data;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.StatFs;

import app.voyahmusic.player.domain.Track;
import app.voyahmusic.shared.lan.MusicWire;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MusicLibrary {
    public record Snapshot(List<Track> tracks, List<MusicWire.Folder> folders, Map<String, Long> locations) {
        public long folderOf(Track track) {
            return locations.getOrDefault(track.id(), 0L);
        }
    }

    private static final long RESERVE_BYTES = 32L * 1024 * 1024;
    private final File directory;
    private final FolderStore folders;
    private final Map<String, Track> metadata = new HashMap<>();

    public MusicLibrary(Context context) {
        directory = new File(context.getFilesDir(), "music");
        folders = new FolderStore(context.getApplicationContext());
        directory.mkdirs();
        File[] interrupted = directory.listFiles(file -> file.getName().startsWith(".incoming"));
        if (interrupted != null) {
            for (File file : interrupted) discard(file);
        }
    }

    public File staging() throws IOException {
        return File.createTempFile(".incoming-", "", directory);
    }

    public boolean hasSpace(long bytes) {
        return new StatFs(directory.getPath()).getAvailableBytes() - RESERVE_BYTES >= bytes;
    }

    public synchronized MusicWire.Status publish(File staging, String id, String name, long destination) throws IOException {
        if (!folders.exists(destination)) return MusicWire.Status.FOLDER;
        File folder = new File(directory, id);
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null && files.length > 0) return MusicWire.Status.DUPLICATE;
        } else if (!folder.mkdir()) {
            throw new IOException("Could not create track directory");
        }
        if (!folders.move(id, destination) || !staging.renameTo(new File(folder, name))) {
            folders.move(id, 0);
            discard(folder);
            throw new IOException("Could not publish track");
        }
        return MusicWire.Status.OK;
    }

    public synchronized boolean remove(Track track) {
        if (!track.file().delete() && track.file().exists()) return false;
        folders.move(track.id(), 0);
        metadata.remove(track.id());
        discard(track.file().getParentFile());
        return true;
    }

    public List<MusicWire.Folder> folders() {
        return Collections.unmodifiableList(folders.folders());
    }

    public boolean hasFolder(long id) {
        return folders.exists(id);
    }

    public synchronized long createFolder(String name) {
        return folders.create(name);
    }

    public synchronized boolean moveTrack(Track track, long folder) {
        return track.file().isFile() && folders.move(track.id(), folder);
    }

    public Snapshot snapshot() {
        return new Snapshot(tracks(), folders(), Collections.unmodifiableMap(folders.locations()));
    }

    private List<Track> tracks() {
        List<Track> result = new ArrayList<>();
        File[] folders = directory.listFiles(file -> file.isDirectory() && file.getName().matches("[a-f0-9]{64}"));
        if (folders == null) return Collections.emptyList();
        for (File folder : folders) {
            File[] files = folder.listFiles(file -> file.isFile() && MusicWire.validName(file.getName()));
            if (files == null || files.length == 0) continue;
            String id = folder.getName();
            Track track;
            synchronized (this) {
                track = metadata.get(id);
            }
            if (track == null) {
                track = read(id, files[0]);
                synchronized (this) {
                    if (files[0].isFile()) metadata.put(id, track);
                }
            }
            result.add(track);
        }
        result.sort(Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::id));
        return Collections.unmodifiableList(result);
    }

    public static boolean isAudio(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            boolean audio = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String type = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("video/")) return false;
                if (type != null && type.startsWith("audio/")) audio = true;
            }
            return audio;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            extractor.release();
        }
    }

    public static void discard(File file) {
        if (file.exists()) file.delete();
    }

    private static Track read(String id, File file) {
        String name = file.getName();
        String title = name.substring(0, name.lastIndexOf('.'));
        String artist = "";
        String album = "";
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            String tag = value(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (!tag.isEmpty()) title = tag;
            artist = value(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST);
            album = value(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String length = value(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!length.isEmpty()) duration = Math.max(0, Long.parseLong(length));
        } catch (RuntimeException ignored) {
            // The original filename remains usable when tags are missing or malformed.
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
            }
        }
        return new Track(id, file, title, artist, album, duration);
    }

    private static String value(MediaMetadataRetriever retriever, int key) {
        String text = retriever.extractMetadata(key);
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").trim();
    }
}
