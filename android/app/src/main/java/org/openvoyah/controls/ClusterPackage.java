package org.openvoyah.controls;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Only catalog-pinned payloads are accepted. ZIP metadata never supplies shell paths. */
final class ClusterPackage {
    final JSONObject profile;
    final File directory;
    ClusterPackage(JSONObject profile, File directory) { this.profile = profile; this.directory = directory; }
    String firmware() throws JSONException { return profile.getString("firmware"); }
    JSONArray files() throws JSONException { return profile.getJSONArray("files"); }
    static JSONObject profile(Context context, String firmware) throws Exception {
        try (InputStream in = context.getAssets().open("cluster/profiles.json")) {
            JSONArray catalog = new JSONArray(new String(read(in, 16000), StandardCharsets.UTF_8));
            for (int i=0; i<catalog.length(); i++) if (catalog.getJSONObject(i).getString("firmware").equals(firmware)) return catalog.getJSONObject(i);
        }
        throw new IOException("Unsupported cluster firmware: " + firmware);
    }
    static ClusterPackage current(Context context) throws Exception {
        String id = context.getSharedPreferences("cluster",0).getString("pack", "");
        if (!id.matches("[a-f0-9]{32}")) throw new IOException("Import a cluster package first");
        File directory = new File(context.getFilesDir(), "cluster-packs/" + id);
        JSONObject manifest = new JSONObject(new String(read(new FileInputStream(new File(directory,"profile.json")),4096),StandardCharsets.UTF_8));
        ClusterPackage pack = new ClusterPackage(profile(context,manifest.getString("firmware")),directory);
        pack.verify(); return pack;
    }
    static ClusterPackage importZip(Context context, InputStream input) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        File dir = new File(context.getFilesDir(),"cluster-packs/" + id);
        if (!dir.mkdirs()) throw new IOException("Cannot create import directory");
        boolean complete = false;
        try {
            Set<String> expected = new HashSet<>(Arrays.asList("profile.json","gauge","light","point","point_normal.kzb"));
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry; long total=0;
                while ((entry=zip.getNextEntry())!=null) {
                    if (entry.isDirectory() || !expected.remove(entry.getName())) throw new IOException("Unexpected or duplicate ZIP entry");
                    long limit=entry.getName().equals("profile.json")?4096:12000000, size=0;
                    try (FileOutputStream out = new FileOutputStream(new File(dir,entry.getName()))) {
                        byte[] buffer=new byte[65536]; int n;
                        while ((n=zip.read(buffer))!=-1) {
                            size+=n; total+=n;
                            if (size>limit || total>35000000) throw new IOException("Cluster package exceeds size limit");
                            out.write(buffer,0,n);
                        }
                        out.getFD().sync();
                    }
                }
            }
            if (!expected.isEmpty()) throw new IOException("Cluster package is incomplete");
            JSONObject manifest = new JSONObject(new String(read(new FileInputStream(new File(dir,"profile.json")),4096),StandardCharsets.UTF_8));
            if (manifest.getInt("schema")!=1 || !manifest.getString("language").equals("ru")) throw new IOException("Unsupported cluster package format");
            ClusterPackage pack = new ClusterPackage(profile(context,manifest.getString("firmware")),dir);
            pack.verify();
            if (!context.getSharedPreferences("cluster",0).edit().putString("pack",id).commit()) throw new IOException("Cannot save imported package");
            complete=true; return pack;
        } finally {
            if (!complete) { File[] files=dir.listFiles(); if(files!=null) for(File f:files) f.delete(); dir.delete(); }
        }
    }
    void verify() throws Exception {
        for (int i=0;i<files().length();i++) {
            JSONObject entry=files().getJSONObject(i); File file=new File(directory,entry.getString("name"));
            if (file.length()!=entry.getLong("size") || !sha256(file).equals(entry.getString("sha256"))) throw new IOException("Payload integrity failure: " + file.getName());
        }
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        StringBuilder text=new StringBuilder(); for(byte b:digest.digest()) text.append(String.format(Locale.ROOT,"%02x",b&255)); return text.toString();
    }
    static byte[] read(InputStream input,int limit) throws IOException {
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int n; while((n=in.read(buffer))!=-1) { if(out.size()+n>limit) throw new IOException("Input exceeds limit"); out.write(buffer,0,n); } return out.toByteArray();
        }
    }
}
