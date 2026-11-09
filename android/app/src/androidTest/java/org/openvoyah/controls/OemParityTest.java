package org.openvoyah.controls;

import android.test.AndroidTestCase;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import org.json.JSONObject;

public final class OemParityTest extends AndroidTestCase {
    private static final class FakeShell implements OemProfiles.Shell {
        String product="f0506h", failure=""; final List<String> commands=new ArrayList<>();
        public String run(String command) throws IOException {
            commands.add(command);
            if(!failure.isEmpty() && command.contains(failure)) throw new IOException("Disconnected");
            if(command.equals("getprop ro.product.name")) return product;
            if(command.startsWith("cat /private/")) return "Original%0%1%originalAid%originalUid";
            return "";
        }
    }
    public void testOriginalProfileIdsAndDurableBackupPrecedeMutation() throws Exception {
        FakeShell shell=new FakeShell(); OemProfiles.switchDriver(shell,"B");
        assertTrue(shell.commands.get(1).contains("/profile-backup/ready"));
        String mutation=shell.commands.get(shell.commands.size()-1);
        assertTrue(mutation.contains("613f734f8fb7462ebd1db95d10000002%10000002"));
        assertTrue(mutation.contains("setprop qinggan.account.uid 10000002"));
        assertTrue(mutation.contains("com.qinggan.account.userinfo.changed"));
        assertTrue(mutation.contains("chmod 0444"));
    }
    public void testUnsupportedFirmwareAndInvalidInputNeverMutateAccount() throws Exception {
        FakeShell shell=new FakeShell(); shell.product="f6811";
        try { OemProfiles.switchDriver(shell,"A");fail(); } catch(IOException expected) { assertTrue(expected.getMessage().contains("unavailable on this firmware")); }
        assertEquals(1,shell.commands.size());
        try { OemProfiles.switchDriver(shell,"A; reboot");fail(); } catch(IllegalArgumentException expected) { }
        assertEquals(1,shell.commands.size());
    }
    public void testBackupFailureAndAmbiguousSwitchAreNotRetried() throws Exception {
        FakeShell backup=new FakeShell(); backup.failure="profile-backup";
        try { OemProfiles.switchDriver(backup,"A");fail(); } catch(IOException expected) { }
        assertFalse(backup.commands.stream().anyMatch(s->s.contains("accountInfo.ovc-new")));
        FakeShell lost=new FakeShell(); lost.failure="accountInfo.ovc-new";
        try { OemProfiles.switchDriver(lost,"C");fail(); } catch(IOException expected) { assertTrue(expected.getMessage().contains("not confirmed")); }
        assertEquals(1L,lost.commands.stream().filter(s->s.contains("accountInfo.ovc-new")).count());
    }
    public void testSeatMemoryIsScopedToCurrentAccountAndXmlDecoded() throws Exception {
        JSONObject bean=OemProfiles.seatBean("<map><string name=\"seatPosition01aid\">{&quot;x&quot;:4}</string>"
            + "<string name=\"seatPosition02other\">{&quot;x&quot;:9}</string></map>","aid");
        assertEquals(4,bean.getJSONObject("seatMirrorBean1").getInt("x")); assertTrue(bean.isNull("seatMirrorBean2"));
        assertNull(OemProfiles.seatBean("<map/>","aid"));
    }
    public void testGeneratedProfileCommandsParseInAndroidShellWithoutExecution() throws Exception {
        FakeShell shell=new FakeShell(); OemProfiles.switchDriver(shell,"A"); OemProfiles.restore(shell);
        java.io.File script=java.io.File.createTempFile("profile-syntax-", ".sh",getContext().getCacheDir());
        try {
            for(String command:shell.commands) {
                try(var out=new java.io.FileOutputStream(script)) { out.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                Process parser=new ProcessBuilder("/system/bin/sh","-n",script.getAbsolutePath()).redirectErrorStream(true).start();
                assertEquals("Shell syntax failed",0,parser.waitFor());
            }
        } finally { script.delete(); }
    }
    public void testRestoreRequiresCompletedSnapshotAndKeepsIt() throws Exception {
        FakeShell shell=new FakeShell(); OemProfiles.restore(shell);
        String script=shell.commands.get(0);
        assertTrue(script.contains("test -f /data/local/tmp/open-voyah-controls/profile-backup/ready"));
        assertTrue(script.contains("setprop \"$p\""));assertFalse(script.contains("rm "));
    }
    public void testLocalizationRejectsExecutableOrUnboundedContent() throws Exception {
        var good=new JSONObject("{\"schema\":1,\"enabled\":true,\"products\":[\"f0506h\"],\"packages\":{\"com.qinggan.app.launcher\":{\"设置\":\"Settings\"}}}");
        assertTrue(LocalizationSettings.validate(good.toString()).getBoolean("enabled"));
        good.getJSONObject("packages").put("system_server",new JSONObject());
        try { LocalizationSettings.validate(good.toString());fail(); } catch(IOException expected) { }
        try { LocalizationSettings.validate("x".repeat(48001));fail(); } catch(IOException expected) { }
    }
    public void testLanguageSettingsAreIndependentAndInvalidImportIsAtomic() throws Exception {
        VoiceSettings voice=new VoiceSettings(getContext()); String old=voice.exportJson(),ui=UiText.language();
        try {
            voice.language("en"); UiText.language("ru");
            assertEquals("DriverB",voice.withContacts().match("driver b").id());assertEquals("ru",UiText.language());
            voice.level("EnableHeatDriverLevelDefault",3);assertEquals(3,voice.options("test").getJSONObject("levels").getInt("EnableHeatDriverLevelDefault"));
            String snapshot=voice.exportJson();
            try { voice.level("EnableHeatDriverLevelDefault",4);fail(); } catch(IllegalStateException expected) { }
            assertEquals(snapshot,voice.exportJson());
            UiText.language("en"); assertEquals("Voice commands",UiText.get(R.string.ui_34));
        } finally { voice.importJson(old);UiText.language(ui); }
    }
}
