package org.openvoyah.controls;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.*;

/** Cluster operations do not depend on Frida or the launcher hook being available. */
final class ClusterController {
    static final Object LOCK=new Object();
    private static final String SHARED="/mnt/qnx/var/ovc-cluster";
    private final Context context;
    private final FridaBridge root;
    ClusterController(Context context) { this.context=context.getApplicationContext(); root=new FridaBridge(context); }
    private static String quote(String text) { return "'"+text.replace("'", "'\"'\"'")+"'"; }
    private String firmware() throws Exception {
        String value=root.clusterRoot("getprop persist.qg.canbus.ipk_software").trim();
        ClusterPackage.profile(context,value); return value;
    }
    private String script(String firmware,String action) throws Exception {
        JSONObject profile=ClusterPackage.profile(context,firmware);
        StringBuilder prefix=new StringBuilder("OVC_ROOT=''\nOVC_FW="+quote(firmware)+"\nOVC_ACTION="+quote(action)+"\n");
        String[] vars={"GAUGE","LIGHT","POINT","THEME"};
        JSONArray files=profile.getJSONArray("files");
        for(int i=0;i<4;i++) prefix.append("OVC_").append(vars[i]).append('=').append(quote(files.getJSONObject(i).getLong("crc")+" "+files.getJSONObject(i).getLong("size"))).append('\n');
        return prefix+new String(ClusterPackage.read(context.getAssets().open("cluster/transaction.sh"),20000),StandardCharsets.UTF_8);
    }
    private String remote(String fw,String action) throws Exception {
        try(ClusterConnection connection=new ClusterConnection()) { return connection.run(script(fw,action)); }
    }
    String inspect() throws Exception { synchronized(LOCK) { String fw=firmware(); return remote(fw,"inspect"); } }
    private void sharedProbe() throws Exception {
        String token=UUID.randomUUID().toString().replace("-", "");
        try(ClusterConnection connection=new ClusterConnection()) {
            connection.run("set -e\n[ ! -L /var/ovc-cluster ]\numask 077\nmkdir -p /var/ovc-cluster\n[ ! -L /var/ovc-cluster/probe ]\nprintf '%s' '"+token+"' > /var/ovc-cluster/probe\nsync");
        }
        if(!root.clusterRoot("cat "+SHARED+"/probe").equals(token)) throw new IOException("QNX shared directory is not mounted at /mnt/qnx/var");
    }
    String prepare() throws Exception { synchronized(LOCK) {
        String fw=firmware(); sharedProbe();
        String result=remote(fw,"prepare");
        File snapshot=copyBackup(fw);
        if(!context.getSharedPreferences("cluster",0).edit().putString("backup",snapshot.getName()).commit()) throw new IOException("Cannot record backup");
        return result+"\n"+UiText.get(R.string.cluster_backup_saved);
    } }
    private File copyBackup(String fw) throws Exception {
        JSONObject profile=ClusterPackage.profile(context,fw);
        File dir=new File(context.getFilesDir(),"cluster-backup-"+UUID.randomUUID().toString().replace("-", ""));
        if(!dir.mkdir()) throw new IOException("Cannot create local backup");
        JSONObject hashes=new JSONObject();
        for(int i=0;i<4;i++) {
            String name=profile.getJSONArray("files").getJSONObject(i).getString("name");
            File file=new File(dir,name);
            copyToApp(SHARED+"/backup/"+name,file);
            String crc=root.clusterRoot("cat "+SHARED+"/backup/"+name+".crc").trim();
            if(!crc.equals(posixChecksum(file))) throw new IOException("Backup transport integrity failure: "+name);
            hashes.put(name,ClusterPackage.sha256(file));
        }
        JSONObject manifest=new JSONObject().put("schema",1).put("firmware",fw).put("sha256",hashes);
        try(FileOutputStream out=new FileOutputStream(new File(dir,"backup.json"))) { out.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        return dir;
    }
    private File backup() throws IOException {
        String name=context.getSharedPreferences("cluster",0).getString("backup", "");
        if(!name.matches("cluster-backup-[a-f0-9]{32}")) throw new IOException("Prepare and export a backup before installing");
        return new File(context.getFilesDir(),name);
    }
    private void verifyBackup(String fw) throws Exception {
        File local=backup();
        JSONObject manifest=new JSONObject(new String(ClusterPackage.read(new FileInputStream(new File(local,"backup.json")),4096),StandardCharsets.UTF_8));
        if(!fw.equals(manifest.getString("firmware"))) throw new IOException("Backup firmware mismatch");
        JSONArray files=ClusterPackage.profile(context,fw).getJSONArray("files");
        File temp=new File(context.getCacheDir(),"cluster-backup-check");
        try {
            for(int i=0;i<4;i++) {
                String name=files.getJSONObject(i).getString("name"), hash=manifest.getJSONObject("sha256").getString(name);
                if(!ClusterPackage.sha256(new File(local,name)).equals(hash)) throw new IOException("Local backup integrity failure");
                copyToApp(SHARED+"/backup/"+name,temp);
                if(!ClusterPackage.sha256(temp).equals(hash)) throw new IOException("QNX backup differs from local snapshot");
            }
        } finally { temp.delete(); }
    }
    private void copyToApp(String from,File to) throws Exception {
        // Create the destination as the app so its SELinux label is inherited correctly.
        if(!to.exists() && !to.createNewFile()) throw new IOException("Cannot create backup destination");
        String path=quote(to.getAbsolutePath());
        root.clusterRoot("cp "+quote(from)+" "+path+"\nchown "+android.os.Process.myUid()+":"+android.os.Process.myUid()+" "+path+"\nchmod 600 "+path);
    }
    String apply() throws Exception { synchronized(LOCK) {
        String fw=firmware(); ClusterPackage pack=ClusterPackage.current(context);
        if(!fw.equals(pack.firmware())) throw new IOException("Package firmware mismatch");
        // Do not truncate staging files that a disconnected QNX transaction may still be using.
        requireReadyForStaging(remote(fw,"inspect"));
        sharedProbe(); verifyBackup(fw);
        root.clusterRoot("[ ! -L "+SHARED+"/stage ]\nmkdir -p "+SHARED+"/stage\nchmod 700 "+SHARED+"/stage");
        for(int i=0;i<4;i++) {
            String name=pack.files().getJSONObject(i).getString("name");
            root.clusterRoot("[ ! -L "+SHARED+"/stage/"+name+" ]\ncp "+quote(new File(pack.directory,name).getAbsolutePath())+" "+SHARED+"/stage/"+name);
        }
        if(!fw.equals(firmware())) throw new IOException("Firmware changed during preflight");
        return remote(fw,"apply")+"\n"+UiText.get(R.string.cluster_done);
    } }
    static void requireReadyForStaging(String inspection) throws IOException {
        java.util.Set<String> lines=new java.util.HashSet<>(java.util.Arrays.asList(inspection.split("\\r?\\n")));
        if(lines.contains("LOCK_PRESENT") || !lines.contains("BACKUP_VALID") || !(lines.contains("PREPARED") || lines.contains("RESTORED")))
            throw new IOException("Cluster is not ready for staging; inspect status and restore if required");
    }
    String restore() throws Exception { synchronized(LOCK) {
        String fw=firmware(); sharedProbe(); verifyBackup(fw);
        return remote(fw,"restore")+"\n"+UiText.get(R.string.cluster_done);
    } }
    void exportBackup(OutputStream output) throws Exception { synchronized(LOCK) {
        File directory=backup();
        JSONObject manifest=new JSONObject(new String(ClusterPackage.read(new FileInputStream(new File(directory,"backup.json")),4096),StandardCharsets.UTF_8));
        JSONArray files=ClusterPackage.profile(context,manifest.getString("firmware")).getJSONArray("files");
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            for(int i=-1;i<4;i++) {
                String name=i==-1?"backup.json":files.getJSONObject(i).getString("name"); File file=new File(directory,name);
                if(i>=0 && !ClusterPackage.sha256(file).equals(manifest.getJSONObject("sha256").getString(name))) throw new IOException("Local backup integrity failure");
                zip.putNextEntry(new ZipEntry(name));
                try(InputStream in=new FileInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) zip.write(buffer,0,n); }
                zip.closeEntry();
            }
        }
    } }
    static String posixChecksum(File file) throws IOException {
        int[] table=new int[256]; for(int i=0;i<256;i++) { int c=i<<24; for(int j=0;j<8;j++) c=(c<<1)^((c<0)?0x04c11db7:0); table[i]=c; }
        int crc=0; long size=0;
        try(InputStream in=new FileInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) { size+=n; for(int i=0;i<n;i++) crc=(crc<<8)^table[((crc>>>24)^(buffer[i]&255))&255]; } }
        for(long n=size;n!=0;n>>>=8) crc=(crc<<8)^table[((crc>>>24)^(int)n)&255];
        return Integer.toUnsignedString(~crc)+" "+size;
    }
}
