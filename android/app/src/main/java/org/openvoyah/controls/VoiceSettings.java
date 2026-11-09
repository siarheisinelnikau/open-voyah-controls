package org.openvoyah.controls;

import android.content.Context;
import android.content.SharedPreferences;
import org.openvoyah.controls.core.VoiceCatalog;
import java.io.IOException;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.*;

/** Voice preferences have independent ownership; a panel failure cannot corrupt them. */
final class VoiceSettings {
    private final SharedPreferences prefs;
    VoiceSettings(Context context) { prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE); }
    static VoiceCatalog catalog() throws IOException {
        return new VoiceCatalog(VoiceSettings.class.getResourceAsStream("/voice-catalog.tsv"));
    }
    String language() { return prefs.getString("language", "ru"); }
    void language(String value) { update("language", value); }
    int level(String command) { return Integer.parseInt(prefs.getString("level."+command,"2")); }
    void level(String command,int value) { update("level."+command,Integer.toString(value)); }
    boolean option(String key) { return prefs.getString(key,"false").equals("true"); }
    void option(String key,boolean value) { update(key,Boolean.toString(value)); }
    JSONObject options(String phrase) throws Exception {
        JSONObject levels=new JSONObject();
        for(var command:catalog().all()) if(command.id().endsWith("LevelDefault")) levels.put(command.id(),level(command.id()));
        return new JSONObject().put("profile",profile()).put("phrase",phrase).put("levels",levels)
            .put("doubleSmoke",option("doubleSmoke")).put("doubleVentilation",option("doubleVentilation"));
    }
    private String callPrefix() { return language().equals("en") ? "call " : "\u043f\u043e\u0437\u0432\u043e\u043d\u0438 "; }
    private String catalogPath() { return language().equals("en") ? "/voice-catalog-en.tsv" : "/voice-catalog.tsv"; }
    VoiceCatalog withContacts() throws IOException {
        List<VoiceCatalog.Command> extra=new ArrayList<>();
        for(var entry:prefs.getAll().entrySet()) if(entry.getKey().startsWith("contact.")) {
            try {
                JSONObject contact=new JSONObject((String)entry.getValue()); String name=contact.getString("name");
                extra.add(new VoiceCatalog.Command("Call_"+entry.getKey().substring(8),"Contacts",List.of(callPrefix()+name,(language().equals("en")?"dial ":"\u043d\u0430\u0431\u0435\u0440\u0438 ")+name)));
            } catch(Exception ex) { throw new IOException(UiText.get(R.string.ui_210),ex); }
        }
        return new VoiceCatalog(VoiceSettings.class.getResourceAsStream(catalogPath()),extra);
    }
    String phone(String id) throws JSONException { return new JSONObject(prefs.getString("contact."+id.substring(5),"{}")).getString("phone"); }
    void contact(String name,String phone) throws Exception {
        name=VoiceCatalog.normalize(name);
        if(name.isBlank() || name.length()>60 || !phone.matches("\\+?[0-9]{3,15}")) throw new IllegalArgumentException(UiText.get(R.string.ui_211));
        for(var c:withContacts().all()) if(c.phrases().contains(callPrefix()+name)) throw new IllegalArgumentException(UiText.get(R.string.ui_212));
        JSONObject root=new JSONObject(exportJson());
        root.getJSONObject("values").put("contact."+UUID.randomUUID(),new JSONObject().put("name",name).put("phone",phone).toString());
        importJson(root.toString());
    }
    String exportJson() {
        JSONObject values=new JSONObject();
        try {
            for(var e:prefs.getAll().entrySet()) if(!e.getKey().equals("backup")) values.put(e.getKey(),e.getValue());
            return new JSONObject().put("schema",1).put("values",values).toString(2);
        } catch(JSONException ex) { throw new IllegalStateException(ex); }
    }
    void importJson(String raw) throws Exception {
        if(raw.length()>1048576) throw new IllegalArgumentException(UiText.get(R.string.ui_154));
        JSONObject root=new JSONObject(raw), values=root.getJSONObject("values");
        if(root.getInt("schema")!=1 || values.length()>512) throw new IllegalArgumentException(UiText.get(R.string.ui_213));
        Set<String> names=new HashSet<>();
        VoiceCatalog commands=catalog();
        Iterator<String> keys=values.keys();
        while(keys.hasNext()) {
            String key=keys.next(), value=values.getString(key);
            if(value.length()>1024) throw new IllegalArgumentException(UiText.get(R.string.ui_214));
            if(key.equals("language")) { if(!Set.of("ru","en").contains(value)) throw new IllegalArgumentException("Invalid voice language"); }
            else if(key.startsWith("level.")) {
                if(!key.endsWith("LevelDefault") || commands.byId(key.substring(6))==null || !Set.of("1","2","3").contains(value)) throw new IllegalArgumentException("Invalid default level");
            }
            else if(Set.of("doubleSmoke","doubleVentilation").contains(key)) {
                if(!Set.of("true","false").contains(value)) throw new IllegalArgumentException("Invalid window option");
            }
            else if(key.equals("profile")) { if(!value.equals("UNKNOWN") && !org.openvoyah.controls.core.Firmware.PRODUCTS.containsValue(value)) throw new IllegalArgumentException(UiText.get(R.string.ui_215)); }
            else if(key.startsWith("app.")) {
                if(commands.byId(key.substring(4))!=null && !value.isEmpty() && !value.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) throw new IllegalArgumentException(UiText.get(R.string.ui_216));
            } else if(key.startsWith("contact.")) {
                JSONObject c=new JSONObject(value); String name=VoiceCatalog.normalize(c.getString("name"));
                if(name.isBlank() || name.length()>60 || !names.add(name) || !c.getString("phone").matches("\\+?[0-9]{3,15}")) throw new IllegalArgumentException(UiText.get(R.string.ui_217));
            } else throw new IllegalArgumentException(UiText.get(R.string.ui_218));
        }
        String backup=exportJson(); SharedPreferences.Editor editor=prefs.edit().clear().putString("backup",backup);
        keys=values.keys();
        while(keys.hasNext()) {
            String key=keys.next();
            if(key.startsWith("app.") && commands.byId(key.substring(4))==null) continue;
            editor.putString(key,values.getString(key));
        }
        if(!editor.commit()) throw new IOException(UiText.get(R.string.ui_219));
    }
    void restore() throws Exception {
        String backup=prefs.getString("backup",null); if(backup==null) throw new IOException(UiText.get(R.string.ui_220)); importJson(backup);
    }
    String app(String command) { return prefs.getString("app."+command, ""); }
    void app(String command, String component) { update("app."+command,component); }
    String profile() { return prefs.getString("profile", org.openvoyah.controls.core.Firmware.identify(android.os.Build.PRODUCT)); }
    String profileLabel() { return switch(profile()) { case "FREE"->UiText.get(R.string.ui_221);case "FREE_REST"->UiText.get(R.string.ui_222);case "DREAMER"->"Dreamer";default->profile(); }; }
    void profile(String value) { update("profile",value); }
    private void update(String key,String value) {
        try { JSONObject root=new JSONObject(exportJson());root.getJSONObject("values").put(key,value);importJson(root.toString()); }
        catch(Exception ex) { throw new IllegalStateException(UiText.get(R.string.ui_219),ex); }
    }
}
