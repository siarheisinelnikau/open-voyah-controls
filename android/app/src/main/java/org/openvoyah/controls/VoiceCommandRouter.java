package org.openvoyah.controls;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import org.json.JSONObject;
import org.openvoyah.controls.core.VoiceCatalog;
import org.openvoyah.controls.core.VoiceSession;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Android and privileged transport adapters; no recognition or vocabulary logic. */
final class VoiceCommandRouter implements VoiceSession.Executor, AutoCloseable {
    private final Activity activity;
    private final VoiceSettings settings;
    private final java.util.List<LocalVoiceAction> handlers;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog confirmation;
    private boolean busy;
    VoiceCommandRouter(Activity activity, VoiceSettings settings) {
        this(activity,settings,java.util.List.of(new LaunchVoiceActions(activity,settings),new MediaVoiceActions(activity)));
    }
    VoiceCommandRouter(Activity activity,VoiceSettings settings,java.util.List<LocalVoiceAction> handlers) {
        this.activity=activity;this.settings=settings;this.handlers=java.util.List.copyOf(handlers);
    }
    @Override public void execute(VoiceCatalog.Command command, String phrase, Consumer<String> done) {
        if (busy) { done.accept(UiText.get(R.string.ui_199)); return; }
        try {
            if(command.id().startsWith("Call_")) {
                String phone=settings.phone(command.id());
                confirmation=new AlertDialog.Builder(activity).setTitle(command.title()).setMessage(phone)
                    .setNegativeButton(UiText.get(R.string.ui_89),(d,w)->done.accept(UiText.get(R.string.ui_148)))
                    .setPositiveButton(UiText.get(R.string.ui_200),(d,w)->{
                        try { activity.startActivity(new Intent(Intent.ACTION_DIAL,android.net.Uri.fromParts("tel",phone,null))); done.accept(UiText.get(R.string.ui_201)); }
                        catch(Exception ex) { done.accept(UiText.get(R.string.ui_202)+ex.getMessage()); }
                    }).create(); confirmation.show(); return;
            }
            for(LocalVoiceAction handler:handlers) if(handler.supports(command.id())) {
                done.accept(handler.execute(command.id()));return;
            }
            confirmation = new AlertDialog.Builder(activity).setTitle(command.title())
                .setMessage(UiText.get(R.string.ui_203))
                .setNegativeButton(UiText.get(R.string.ui_89),(d,w)->done.accept(UiText.get(R.string.ui_148)))
                .setPositiveButton(UiText.get(R.string.ui_187),(d,w)->send(command,phrase,done)).create();
            confirmation.show();
        } catch (SecurityException ex) { done.accept(UiText.get(R.string.ui_204)); }
        catch (Exception ex) { done.accept(UiText.get(R.string.ui_205)+ex.getMessage()); }
    }
    private void send(VoiceCatalog.Command command, String phrase, Consumer<String> done) {
        busy=true;
        worker.execute(() -> {
            String result;
            try {
                FridaBridge bridge = new FridaBridge(activity);
                result = command.id().matches("Driver[ABC]") ? bridge.switchDriver(command.id().substring(6)) : command.id().equals("RebootTbox") ? bridge.rebootTbox() :
                    bridge.sendVoice(command.id(),settings.options(phrase));
            } catch (Exception ex) { result=UiText.get(R.string.ui_206)+ex.getMessage()+UiText.get(R.string.ui_207); }
            String message=result;
            activity.runOnUiThread(() -> { busy=false; if (!activity.isDestroyed() && !activity.isFinishing()) done.accept(message); });
        });
    }
    void pause() { if (confirmation!=null) confirmation.dismiss(); }
    @Override public void close() { pause(); worker.shutdownNow(); }
}
