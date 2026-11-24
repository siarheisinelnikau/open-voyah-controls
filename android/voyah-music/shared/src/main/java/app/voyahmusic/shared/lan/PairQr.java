package app.voyahmusic.shared.lan;

import android.graphics.Bitmap;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;

public record PairQr(String host, String fingerprint) {
    public String encode() {
        return new Uri.Builder().scheme("voyahmusic").authority("pair")
                .appendQueryParameter("v", "2").appendQueryParameter("ip", host)
                .appendQueryParameter("id", fingerprint).build().toString();
    }

    public static PairQr parse(String data) {
        if (data == null || data.length() > 200) return null;
        Uri uri = Uri.parse(data);
        if (!"voyahmusic".equals(uri.getScheme()) || !"pair".equals(uri.getAuthority())
                || !uri.isHierarchical() || !"2".equals(uri.getQueryParameter("v"))) return null;
        String host = uri.getQueryParameter("ip");
        String fingerprint = uri.getQueryParameter("id");
        if (host == null || fingerprint == null || MusicWire.address(host) == null
                || !MusicWire.fingerprint(fingerprint)) return null;
        return new PairQr(host, fingerprint);
    }

    public Bitmap bitmap(int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(encode(), BarcodeFormat.QR_CODE, size, size);
            int side = matrix.getWidth();
            int[] pixels = new int[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    pixels[y * side + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
                }
            }
            return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decodeFrame(byte[] frame, int width, int height) {
        var luminance = new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
        try {
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(luminance))).getText();
        } catch (ReaderException e) {
            return null;
        }
    }
}
