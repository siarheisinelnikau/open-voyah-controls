package org.openvoyah.controls;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.os.Bundle;
import android.text.*;
import android.widget.*;
import org.openvoyah.controls.core.*;
import java.util.*;

/** Push-to-talk screen also exposes the complete vocabulary and app assignments. */
public final class VoiceActivity extends Activity {
    private VoiceCatalog catalog;
    private VoiceSettings settings;
    private VoiceSession session;
    private VoiceCommandRouter router;
    private OfflineVoiceInput input;
    private TextView status;
    private EditText phrase;
    private boolean visible;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new VoiceSettings(this); router = new VoiceCommandRouter(this,settings); input = new OfflineVoiceInput(this);
        try { catalog = settings.withContacts(); session = new VoiceSession(catalog,router,key -> UiText.get(switch(key) { case "Cancelled" -> R.string.ui_148; case "Unknown command" -> R.string.unknown_command; default -> R.string.ui_205; })); }
        catch (Exception ex) {
            LinearLayout recovery=new LinearLayout(this);recovery.setOrientation(LinearLayout.VERTICAL);recovery.setPadding(dp(24),dp(24),dp(24),dp(24));
            status=text(UiText.get(R.string.ui_164)+ex.getMessage(),18);recovery.addView(status);
            recovery.addView(button(UiText.get(R.string.ui_165),()->{ try { settings.restore();recreate(); } catch(Exception failure) { status.setText(failure.getMessage()); } }));
            recovery.addView(button(UiText.get(R.string.ui_166),()->startActivity(new Intent(this,MainActivity.class))));
            setContentView(recovery);return;
        }
        LinearLayout outer=new LinearLayout(this),root=new LinearLayout(this),catalogue=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);catalogue.setOrientation(LinearLayout.VERTICAL);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        outer.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        outer.setPadding(dp(16),dp(16),dp(16),dp(16));
        if(landscape) {
            ScrollView scroll=new ScrollView(this);scroll.addView(root);outer.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
            catalogue.setPadding(dp(20),0,0,0);outer.addView(catalogue,new LinearLayout.LayoutParams(0,-1,1));
        } else { outer.addView(root);outer.addView(catalogue,new LinearLayout.LayoutParams(-1,0,1)); }
        outer.setOnApplyWindowInsetsListener((v,i) -> { v.setPadding(dp(16)+i.getSystemWindowInsetLeft(),dp(16)+i.getSystemWindowInsetTop(),dp(16)+i.getSystemWindowInsetRight(),dp(16)+i.getSystemWindowInsetBottom()); return i; });
        root.addView(text(UiText.get(R.string.ui_167),26));
        root.addView(text(UiText.get(R.string.ui_168)+catalog.all().size()+UiText.get(R.string.ui_169),15));
        status = text(UiText.get(R.string.ui_170),18);status.setMaxLines(5);status.setEllipsize(TextUtils.TruncateAt.END);root.addView(status);
        status.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_171)).setMessage(status.getText()).setPositiveButton(UiText.get(R.string.ui_172),null).show());
        LinearLayout controls = new LinearLayout(this);
        controls.addView(button(UiText.get(R.string.ui_173),this::microphone),new LinearLayout.LayoutParams(0,dp(56),1));
        controls.addView(button(UiText.get(R.string.ui_89),() -> { input.cancel(); session.cancel(); router.pause(); status.setText(UiText.get(R.string.ui_148)); }),new LinearLayout.LayoutParams(0,dp(56),1)); root.addView(controls);
        phrase = new EditText(this); phrase.setSingleLine(true); phrase.setHint(catalog.byId("EnableHeatDriverLevelTwo").title()); root.addView(phrase);
        root.addView(button(UiText.get(R.string.ui_175),() -> submit(phrase.getText().toString())));
        root.addView(button(UiText.get(R.string.voice_language) + ": " + settings.language(), () ->
            new AlertDialog.Builder(this).setTitle(UiText.get(R.string.voice_language)).setItems(new String[]{"Русский", "English"}, (d,n) -> {
                settings.language(n==0?"ru":"en"); recreate();
            }).show()));
        root.addView(button(UiText.get(R.string.ui_176)+settings.profileLabel(),this::profile));
        root.addView(button(UiText.get(R.string.ui_177),()->new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_178))
            .setItems(new String[]{UiText.get(R.string.ui_179),UiText.get(R.string.ui_180),UiText.get(R.string.ui_181),UiText.get(R.string.ui_182),UiText.get(R.string.ui_183),UiText.get(R.string.ui_165)},(d,n)->{
                if(n==0) { session.cancel(); input.repair(()->status.setText(UiText.get(R.string.ui_184))); }
                if(n==1) startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                if(n==2) addContact();
                if(n==3) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"controls-voice.json"),31);
                if(n==4) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),32);
                if(n==5) { try { settings.restore(); recreate(); } catch(Exception ex) { status.setText(ex.getMessage()); } }
            }).show()));
        root.addView(button(UiText.get(R.string.default_levels), () -> {
            var defaults = catalog.all().stream().filter(c -> c.id().endsWith("LevelDefault")).collect(java.util.stream.Collectors.toList());
            String[] labels = defaults.stream().map(c -> c.title() + ": " + settings.level(c.id())).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle(UiText.get(R.string.default_levels)).setItems(labels,(d,n) ->
                new AlertDialog.Builder(this).setTitle(defaults.get(n).title()).setItems(new String[]{"1","2","3"},(dialog,level) -> {
                    settings.level(defaults.get(n).id(),level+1);
                }).show()).show();
        }));
        root.addView(button(UiText.get(R.string.window_options), () -> new AlertDialog.Builder(this).setTitle(UiText.get(R.string.window_options))
            .setMultiChoiceItems(new String[]{UiText.get(R.string.double_smoke),UiText.get(R.string.double_ventilation)},
                new boolean[]{settings.option("doubleSmoke"),settings.option("doubleVentilation")},(d,n,checked) -> settings.option(n==0?"doubleSmoke":"doubleVentilation",checked))
            .setPositiveButton(UiText.get(R.string.ui_172),null).show()));
        EditText search = new EditText(this); search.setSingleLine(true); search.setHint(UiText.get(R.string.ui_185)); catalogue.addView(search);
        ListView list = new ListView(this); catalogue.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        List<VoiceCatalog.Command> filtered = new ArrayList<>(catalog.all());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());
        Runnable refresh = () -> {
            String query = VoiceCatalog.normalize(search.getText().toString()); filtered.clear(); adapter.clear();
            for (var command : catalog.all()) if (query.isBlank() || VoiceCatalog.normalize(String.join(" ",command.phrases())).contains(query)) {
                filtered.add(command); adapter.add(command.title()+(settings.app(command.id()).isEmpty()?"":UiText.get(R.string.ui_186)));
            }
        };
        refresh.run(); list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f) { }
            public void onTextChanged(CharSequence s,int a,int b,int c) { refresh.run(); }
            public void afterTextChanged(Editable e) { }
        });
        list.setOnItemClickListener((p,v,pos,id) -> {
            var command = filtered.get(pos);
            new AlertDialog.Builder(this).setTitle(command.title()).setMessage(String.join("\n",command.phrases()))
                .setPositiveButton(UiText.get(R.string.ui_187),(d,w)->submit(command.title()))
                .setNeutralButton(UiText.get(R.string.ui_188),(d,w)->assign(command,refresh))
                .setNegativeButton(UiText.get(R.string.ui_172),null).show();
        });
        setContentView(outer); outer.requestApplyInsets();
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.WHITE); t.setPadding(0,8,0,8); return t; }
    private Button button(String label,Runnable action) { Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v->action.run()); return b; }
    private void profile() {
        String[] names={"UNKNOWN","FREE","FREE_REST","FREE_REST_BY","FREE_REST_OVERSEA","NXP","FREE_NORWAY","DREAMER","DREAMER_REST","DREAMER_REST_MI","DREAMER_REST_MI_V2"};
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_189)).setItems(names,(d,n)->{
            try { settings.profile(names[n]); recreate(); } catch(Exception ex) { status.setText(ex.getMessage()); }
        }).show();
    }
    private void assign(VoiceCatalog.Command command,Runnable refresh) {
        if(command.id().startsWith("Call_")) { status.setText(UiText.get(R.string.ui_190)); return; }
        List<ResolveInfo> apps = new ArrayList<>(getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0));
        apps.removeIf(a->a.activityInfo==null || !a.activityInfo.exported || !a.activityInfo.enabled);
        apps.sort(Comparator.comparing(a->a.loadLabel(getPackageManager()).toString()));
        String[] labels=new String[apps.size()+1]; labels[0]=UiText.get(R.string.ui_191);
        for(int n=0;n<apps.size();n++) labels[n+1]=apps.get(n).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_192)+command.title()+"»").setItems(labels,(d,n)->{
            settings.app(command.id(),n==0?"":new ComponentName(apps.get(n-1).activityInfo.packageName,apps.get(n-1).activityInfo.name).flattenToString()); refresh.run();
        }).show();
    }
    private void addContact() {
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        EditText name=new EditText(this);name.setHint(UiText.get(R.string.ui_193));fields.addView(name);
        EditText phone=new EditText(this);phone.setHint(UiText.get(R.string.ui_194));phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);fields.addView(phone);
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_195)).setView(fields).setNegativeButton(UiText.get(R.string.ui_89),null)
            .setPositiveButton(UiText.get(R.string.ui_104),(d,w)->{ try { settings.contact(name.getText().toString(),phone.getText().toString()); recreate(); } catch(Exception ex) { status.setText(ex.getMessage()); } }).show();
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            if(request==31) {
                try(var output=getContentResolver().openOutputStream(data.getData(),"wt")) { if(output==null)throw new java.io.IOException(UiText.get(R.string.ui_123)); output.write(settings.exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                status.setText(UiText.get(R.string.ui_196));
            }
            if(request==32) {
                try(var stream=getContentResolver().openInputStream(data.getData()); var output=new java.io.ByteArrayOutputStream()) {
                    if(stream==null)throw new java.io.IOException(UiText.get(R.string.ui_123)); byte[] bytes=new byte[8192];int n;
                    while((n=stream.read(bytes))!=-1) { if(output.size()+n>1048576)throw new java.io.IOException(UiText.get(R.string.ui_154));output.write(bytes,0,n); }
                    settings.importJson(output.toString("UTF-8")); recreate();
                }
            }
        } catch(Exception ex) { status.setText(UiText.get(R.string.ui_197)+ex.getMessage()); }
    }
    private void submit(String text) { input.cancel(); router.pause(); phrase.setText(text); session.accept(session.begin(),text,status::setText); }
    private void microphone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},20); return; }
        long token=session.begin();
        input.start(new OfflineVoiceInput.Listener() {
            public void status(String text) { status.setText(text); }
            public void result(String text) { phrase.setText(text); session.accept(token,text,status::setText); }
            public void error(String text) { session.cancel(); status.setText(text); }
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==20 && visible && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) microphone();
        else if(status!=null) status.setText(UiText.get(R.string.ui_198));
    }
    @Override public void onResume() { super.onResume(); visible=true; }
    @Override public void onPause() { visible=false; if(input!=null)input.cancel(); if(session!=null)session.cancel(); if(router!=null)router.pause(); super.onPause(); }
    @Override public void onDestroy() { if(input!=null)input.close(); if(router!=null)router.close(); super.onDestroy(); }
}
