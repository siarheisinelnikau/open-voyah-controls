package org.openvoyah.controls.data.frida;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.openvoyah.controls.core.Firmware;

/** OEM guest account protocol with a durable first-use backup and explicit recovery. */
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
        String backup = backupPath(base);
        String product = shell.run("getprop ro.product.name").trim();
        if (!Firmware.guestAccounts(product)) {
            if (Firmware.identify(product).equals("FREE_REST_OVERSEA"))
                throw new IOException("Guest profiles are unavailable on this firmware");
            throw new IOException("No verified guest account protocol for product " + product);
        }
        // Snapshot before any mutation. A completed snapshot is never overwritten.
        shell.run("set -e; test ! -L " + backup + "; mkdir -p " + backup + "; chmod 700 " + backup + "; "
            + "if [ ! -f " + backup + "/ready ]; then test -f " + ACCOUNT + "; cp -p " + ACCOUNT + " " + backup + "/accountInfo; "
            + "for p in qinggan.account.aid qinggan.account.uid qinggan.account.phone com.qinggan.user_started; do getprop \"$p\" > " + backup + "/\"$p\"; done; "
            + "ls -Zd " + ACCOUNT + " | awk '{print $1}' > " + backup + "/context; test -s " + backup + "/context; sync; touch " + backup + "/ready; sync; fi");
        captureSeats(shell);
        int slot = driver.charAt(0) - 'A' + 1;
        String uid = "1000000" + slot, aid = "613f734f8fb7462ebd1db95d" + uid;
        String account = "\u0412\u043e\u0434\u0438\u0442\u0435\u043b\u044c " + driver + "%0%1%" + aid + "%" + uid;
        try {
            shell.run("set -e; printf '%s' " + quote(account) + " > " + ACCOUNT + ".ovc-new; "
                + "chown system:system " + ACCOUNT + ".ovc-new; chmod 0444 " + ACCOUNT + ".ovc-new; "
                + preserveContext(backup) + "mv " + ACCOUNT + ".ovc-new " + ACCOUNT + "; "
                + "setprop qinggan.account.aid " + aid + "; setprop qinggan.account.uid " + uid + "; "
                + "setprop qinggan.account.phone 0; setprop com.qinggan.user_started closeLoginView; " + broadcasts()
                + "; test \"$(getprop qinggan.account.uid)\" = " + uid + "; sync");
        } catch (IOException ex) {
            // Do not replay a switch after an ambiguous transport failure.
            throw new IOException("Profile switch was not confirmed. Use Restore OEM account if needed", ex);
        }
        return "OEM driver " + driver + " selected; the OEM service applies its saved settings";
    }
    public static String restore(Shell shell) throws IOException {
        return restore(shell, FIXTURE_BASE);
    }
    static String restore(Shell shell, String base) throws IOException {
        String backup = backupPath(base);
        shell.run("set -e; test ! -L " + backup + "; test -f " + backup + "/ready; cp -p " + backup + "/accountInfo " + ACCOUNT + ".ovc-new; "
            + preserveContext(backup) + "mv " + ACCOUNT + ".ovc-new " + ACCOUNT + "; "
            + "for p in qinggan.account.aid qinggan.account.uid qinggan.account.phone com.qinggan.user_started; do "
            + "setprop \"$p\" \"$(cat " + backup + "/\"$p\")\"; done; " + broadcasts() + "; sync");
        return "Original OEM account restored";
    }
    private static String backupPath(String base) {
        if (!base.equals(FIXTURE_BASE) && !base.equals(ProductionRootAccess.BASE)) {
            throw new IllegalArgumentException("Unknown protected profile backup directory");
        }
        return base + "/profile-backup";
    }
    private static String preserveContext(String backup) {
        return "label=$(cat " + backup + "/context); case \"$label\" in u:object_r:*) chcon \"$label\" " + ACCOUNT + ".ovc-new ;; \"?\") [ \"$(getenforce)\" = Disabled ] ;; *) exit 1 ;; esac; ";
    }
    private static String broadcasts() {
        return "am broadcast -f 16777216 -a com.qinggan.account.userinfo.changed >/dev/null && "
            + "am broadcast -f 16777216 -a com.qinggan.account.logined >/dev/null";
    }
    private static void captureSeats(Shell shell) throws Exception {
        String account = shell.run("cat " + ACCOUNT).trim();
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
