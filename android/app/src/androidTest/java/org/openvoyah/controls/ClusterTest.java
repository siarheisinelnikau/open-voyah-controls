package org.openvoyah.controls;

import android.test.InstrumentationTestCase;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

public final class ClusterTest extends InstrumentationTestCase {
    private Context context() { return getInstrumentation().getTargetContext(); }
    public void testBothOriginalFirmwareMappings() throws Exception {
        for(String fw:new String[]{"H97CSA8155-DAILY-20260104134234-USR","H97CSA8155-DAILY-20260530180446-USR"}) {
            var p=ClusterPackage.profile(context(),fw);
            assertEquals(4,p.getJSONArray("files").length());
            assertEquals("0b4c2cb76a134c276f8cfe9e0b1282555a8b82159d850a289e1274b0e5fec5df",p.getJSONArray("files").getJSONObject(3).getString("sha256"));
        }
        try { ClusterPackage.profile(context(),"FREE_318"); fail(); } catch(IOException expected) { assertTrue(expected.getMessage().contains("Unsupported")); }
    }
    private byte[] zip(String... names) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) { for(String name:names) { out.putNextEntry(new ZipEntry(name)); out.write("invalid".getBytes(StandardCharsets.UTF_8)); out.closeEntry(); } }
        return bytes.toByteArray();
    }
    public void testZipTraversalAndMissingEntriesDoNotReplaceSelection() throws Exception {
        String previous=context().getSharedPreferences("cluster",0).getString("pack", "");
        for(byte[] data:new byte[][]{zip("../escape"),zip("gauge"),zip("unknown")}) {
            try { ClusterPackage.importZip(context(),new ByteArrayInputStream(data)); fail(); } catch(IOException expected) { /* Rejected before activation. */ }
            assertEquals(previous,context().getSharedPreferences("cluster",0).getString("pack", ""));
        }
    }
    public void testZipBombIsBounded() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("gauge")); byte[] zero=new byte[65536]; for(int i=0;i<184;i++) zip.write(zero); zip.closeEntry(); }
        try { ClusterPackage.importZip(context(),new ByteArrayInputStream(bytes.toByteArray())); fail(); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
    }
    public void testPosixChecksumMatchesKnownVector() throws Exception {
        File file=new File(context().getCacheDir(),"crc-test");
        try { try(FileOutputStream out=new FileOutputStream(file)) { out.write("123456789".getBytes(StandardCharsets.UTF_8)); }
            assertEquals("930766865 9",ClusterController.posixChecksum(file));
        } finally { file.delete(); }
    }
    public void testStagingCannotOverwriteAnUnfinishedTransaction() throws Exception {
        ClusterController.requireReadyForStaging("PREPARED\nBACKUP_VALID");
        ClusterController.requireReadyForStaging("RESTORED\nBACKUP_VALID");
        for(String status:new String[]{"PREPARED\nBACKUP_VALID\nLOCK_PRESENT", "apply_IN_PROGRESS\nBACKUP_VALID", "RECOVERY_REQUIRED\nBACKUP_VALID", "PREPARED\nBACKUP_ABSENT_OR_INVALID", "APPLIED\nBACKUP_VALID"}) {
            try { ClusterController.requireReadyForStaging(status); fail(status); } catch(IOException expected) { /* No staging write may follow. */ }
        }
    }
    public void testPackRoundTripAndRevalidation() throws Exception {
        // Local payload fixtures are prepared separately and are never committed as MIT assets.
        File directory=context().getExternalFilesDir(null);
        for(String version:new String[]{"20260104134234","20260530180446"}) {
            File fixture=new File(directory,"cluster-ru-H97CSA8155-DAILY-"+version+"-USR.zip");
            assertTrue("Prepare the two local cluster fixtures before this instrumentation suite",fixture.isFile());
            ClusterPackage pack;
            try(InputStream input=new FileInputStream(fixture)) { pack=ClusterPackage.importZip(context(),input); }
            assertEquals(pack.firmware(),ClusterPackage.current(context()).firmware());
            try(FileOutputStream out=new FileOutputStream(new File(pack.directory,"gauge"),true)) { out.write(1); }
            try { ClusterPackage.current(context()); fail(); } catch(IOException expected) { assertTrue(expected.getMessage().contains("integrity")); }
        }
    }
    private void protocol(String mode) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> fixture=worker.submit(()->{
                try(Socket peer=server.accept()) {
                    peer.setSoTimeout(5000);
                    BufferedReader input=new BufferedReader(new InputStreamReader(peer.getInputStream(),StandardCharsets.UTF_8));
                    OutputStream out=peer.getOutputStream();
                    assertEquals("service launcher",input.readLine()); out.write("QCONN\n<qconn> OK\n<qconn-launcher> ".getBytes(StandardCharsets.UTF_8)); out.flush();
                    assertEquals("start /bin/sh /var/cmd.sh",input.readLine()); out.write("OK 1234\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                    String handshake=input.readLine(), token=token(handshake);
                    // Echoed commands must not count as acknowledgements.
                    out.write((handshake+"\n"+token+"\n").getBytes(StandardCharsets.UTF_8)); out.flush();
                    String line; do { line=input.readLine(); if(line==null) throw new EOFException(); } while(!line.startsWith("); ovc_rc="));
                    String end=token(line);
                    if(mode.equals("disconnect")) return;
                    String reply="stage verified\n"+end+":"+(mode.equals("error")?"42":"0")+"\n";
                    for(byte b:reply.getBytes(StandardCharsets.UTF_8)) { out.write(b); out.flush(); }
                } catch(Exception ex) { throw new RuntimeException(ex); }
            });
            try {
                try(ClusterConnection connection=new ClusterConnection("127.0.0.1",server.getLocalPort(),5000)) {
                    String reply=connection.run("echo stage verified");
                    assertEquals("ok",mode); assertEquals("stage verified",reply);
                } catch(IOException ex) { assertFalse(mode.equals("ok")); }
                fixture.get(7,TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
        }
    }
    private static String token(String line) {
        Matcher m=Pattern.compile("'([a-f0-9]{16})'").matcher(line); if(!m.find()) throw new AssertionError(line); String first=m.group(1);
        if(!m.find()) throw new AssertionError(line); return first+m.group(1);
    }
    public void testFragmentedClusterReply() throws Exception { protocol("ok"); }
    public void testClusterFailureIsNotSuccess() throws Exception { protocol("error"); }
    public void testLostReplyIsNotReplayed() throws Exception { protocol("disconnect"); }
}
