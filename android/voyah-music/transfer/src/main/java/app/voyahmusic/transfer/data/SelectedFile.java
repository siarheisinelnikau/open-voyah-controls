package app.voyahmusic.transfer.data;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

public record SelectedFile(Uri uri, String name, long size) {
    public static SelectedFile read(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int size = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (name >= 0 && !cursor.isNull(name)) {
                    return new SelectedFile(uri, cursor.getString(name),
                            size < 0 || cursor.isNull(size) ? -1 : cursor.getLong(size));
                }
            }
        } catch (RuntimeException ignored) {
        }
        String name = uri.getLastPathSegment();
        return new SelectedFile(uri, name == null ? "" : name.substring(name.lastIndexOf('/') + 1), -1);
    }
}
