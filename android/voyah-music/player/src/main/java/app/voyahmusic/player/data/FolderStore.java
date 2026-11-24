package app.voyahmusic.player.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import app.voyahmusic.shared.lan.MusicWire;
import app.voyahmusic.shared.lan.MusicWire.Folder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FolderStore extends SQLiteOpenHelper {
    FolderStore(Context context) {
        super(context, "music_folders.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, lookup TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE locations (track TEXT PRIMARY KEY, folder INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported music folder schema");
    }

    List<Folder> folders() {
        List<Folder> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id, name FROM folders ORDER BY lookup", null)) {
            while (cursor.moveToNext()) list.add(new Folder(cursor.getLong(0), cursor.getString(1)));
        }
        return list;
    }

    Map<String, Long> locations() {
        Map<String, Long> result = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT track, folder FROM locations", null)) {
            while (cursor.moveToNext()) result.put(cursor.getString(0), cursor.getLong(1));
        }
        return result;
    }

    boolean exists(long id) {
        if (id == 0) return true;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM folders WHERE id = ?",
                new String[] {Long.toString(id)})) {
            return cursor.moveToFirst();
        }
    }

    long create(String value) {
        String name = value.trim();
        if (!MusicWire.validFolder(name) || folders().size() >= MusicWire.MAX_FOLDERS) return -1;
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("lookup", name.toLowerCase(Locale.ROOT));
        return getWritableDatabase().insertWithOnConflict("folders", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    boolean move(String track, long folder) {
        if (!exists(folder)) return false;
        if (folder == 0) {
            getWritableDatabase().delete("locations", "track = ?", new String[] {track});
            return true;
        }
        ContentValues values = new ContentValues();
        values.put("track", track);
        values.put("folder", folder);
        return getWritableDatabase().insertWithOnConflict("locations", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }
}
