package org.openvoyah.controls.data.frida;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.openvoyah.controls.core.Firmware;

/** OEM guest protocol with verified recovery of its observed file and four properties. */
public final class OemProfiles {
    private static final String ACCOUNT = "/private/configs/token/accountInfo";
    private static final String FIXTURE_BASE = "/data/local/tmp/open-voyah-controls";
    private static final String URI = "content://com.qinggan.usersettingtracking.datacenterprovider/tb_state";
    public interface Shell { String run(String command) throws IOException; }
    public static String switchDriver(Shell shell, String driver) throws Exception {
        return switchDriver(shell, driver, FIXTURE_BASE);
    }
    static String switchDriver(Shell shell, String driver, String base) throws Exception {
        if (!driver.matches("[ABC]")) throw new IllegalArgumentException("Invalid driver");
        backupPath(base);
        String product = shell.run("getprop ro.product.name").trim();
        if (!Firmware.guestAccounts(product)) {
            if (Firmware.identify(product).equals("FREE_REST_OVERSEA"))
                throw new IOException("Guest profiles are unavailable on this firmware");
            throw new IOException("No verified guest account protocol for product " + product);
        }
        // The first verified snapshot is immutable. Legacy/incomplete snapshots are never promoted.
        GuestAccountState.requireReceipt(shell.run(GuestAccountState.prepare(base)), "prepare");
        captureSeats(shell);
        int slot = driver.charAt(0) - 'A' + 1;
        String uid = "1000000" + slot, aid = "613f734f8fb7462ebd1db95d" + uid;
        String account = "\u0412\u043e\u0434\u0438\u0442\u0435\u043b\u044c " + driver + "%0%1%" + aid + "%" + uid;
        try {
            GuestAccountState.requireReceipt(shell.run(GuestAccountState.switchTo(base, account, aid, uid)), "switch");
        } catch (IOException ex) {
            // Do not replay a switch after an ambiguous transport failure.
            throw new IOException("Profile switch was not confirmed. Use Restore saved account record if needed", ex);
        }
        return "Guest account file and properties for driver " + driver + " written and verified; OEM settings still need confirmation";
    }
    public static String restore(Shell shell) throws IOException {
        return restore(shell, FIXTURE_BASE);
    }
    static String restore(Shell shell, String base) throws IOException {
        String product = shell.run("getprop ro.product.name").trim();
        if (!Firmware.guestAccounts(product)) throw new IOException("No guest account recovery protocol for this product");
        backupPath(base);
        try {
            GuestAccountState.requireReceipt(shell.run(GuestAccountState.restore(base)), "restore");
        } catch (IOException ex) {
            throw new IOException("Saved account record restoration was not verified. Retain the backup and check the connection before retrying recovery", ex);
        }
        return "Original OEM account file and properties restored and verified";
    }
    private static String backupPath(String base) {
        if (!base.equals(FIXTURE_BASE) && !base.equals(ProductionRootAccess.BASE)) {
            throw new IllegalArgumentException("Unknown protected profile backup directory");
        }
        return base + "/profile-backup";
    }
    private static void captureSeats(Shell shell) throws Exception {
        String account = shell.run("set -e; test ! -L " + ACCOUNT + "; test -f " + ACCOUNT + "; "
            + "stamp=$(stat -c '%d:%i:%s:%Y:%Z' " + ACCOUNT + "); size=$(stat -c '%s' " + ACCOUNT + "); "
            + "test \"$size\" -ge 1; test \"$size\" -le 8192; dd if=" + ACCOUNT + " bs=8193 count=1 2>/dev/null; "
            + "test \"$(stat -c '%d:%i:%s:%Y:%Z' " + ACCOUNT + ")\" = \"$stamp\"").trim();
        if (account.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IOException("Oversized OEM account; refusing to lose seat settings");
        String[] fields = account.split("%");
        if (fields.length < 4 || !fields[3].matches("[A-Za-z0-9_-]{1,128}"))
            throw new IOException("Malformed OEM account; refusing to lose seat settings");
        String aid = fields[3];
        String xml = shell.run("if [ -f /data/data/com.qinggan.app.vehicle/shared_prefs/SaveVehicleData.xml ]; then cat /data/data/com.qinggan.app.vehicle/shared_prefs/SaveVehicleData.xml; fi");
        JSONObject bean = seatBean(xml, aid);
        if (bean == null) return;
        String json = bean.toString(), key = hash("com.qinggan.app.vehicle.second-SeatMirrorCloudBean-" + aid);
        String bindings = " --bind " + quote("key:s:" + key) + " --bind " + quote("userid:s:" + aid)
            + " --bind " + quote("json:s:" + json) + " --bind " + quote("md5:s:" + hash(json))
            + " --bind type:s:1 --bind packageName:s:com.qinggan.app.vehicle.second --bind beanName:s:SeatMirrorCloudBean";
        // Update preserves the old row if the provider rejects a write; no delete-before-insert gap.
        String updated = shell.run("content update --uri " + URI + " --where " + quote("key='" + key + "'") + bindings);
        if (updated.contains("Updated 0 rows")) {
            String inserted = shell.run("content insert --uri " + URI + bindings);
            if (inserted.contains("Error") || inserted.contains("Exception")) throw new IOException("Seat memory provider rejected insert");
        } else if (!updated.contains("Updated 1 rows")) throw new IOException("Seat memory provider did not confirm update");
    }
    public static JSONObject seatBean(String xml, String aid) throws Exception {
        JSONObject bean = new JSONObject(); boolean found = false;
        for (int n = 1; n <= 3; n++) {
            var match = Pattern.compile("<string name=\"" + Pattern.quote("seatPosition0" + n + aid) + "\"\\s*>(.*?)</string>", Pattern.DOTALL).matcher(xml);
            Object value = JSONObject.NULL;
            if (match.find()) {
                String raw = match.group(1).replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
                if (!raw.isEmpty() && !raw.equals("null")) { value = new JSONObject(raw); found = true; }
            }
            bean.put("seatMirrorBean" + n, value);
        }
        return found ? bean : null;
    }
    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder(); for (byte b : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.substring(8, 24);
    }
    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
