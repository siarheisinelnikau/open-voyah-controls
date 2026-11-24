package app.voyahmusic.shared.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateFiles extends ContentProvider {
    private static final String NAME = "update.apk";
    private static final String TYPE = "application/vnd.android.package-archive";

    public static Uri uri(Context context) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(NAME).build();
    }

    static File apk(Context context) {
        return new File(context.getCacheDir(), "updates/" + NAME);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return matches(uri) ? TYPE : null; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!matches(uri) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(apk(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!matches(uri)) return null;
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        File file = apk(getContext());
        if (file.isFile()) {
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(NAME);
                else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
                else row.add(null);
            }
        }
        return result;
    }

    private boolean matches(Uri uri) {
        return ("/" + NAME).equals(uri.getPath());
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
