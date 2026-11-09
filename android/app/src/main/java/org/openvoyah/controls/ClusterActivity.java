package org.openvoyah.controls;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.concurrent.*;

/** No installation is scheduled on boot. Interrupted writes require status inspection. */
public final class ClusterActivity extends Activity {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status;
    private ClusterController controller;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); controller=new ClusterController(this);
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(24*getResources().getDisplayMetrics().density); root.setPadding(pad,pad,pad,pad); scroll.addView(root); setContentView(scroll);
        TextView title=new TextView(this); title.setText(UiText.get(R.string.cluster_title)); title.setTextSize(26); root.addView(title);
        TextView info=new TextView(this); info.setText(UiText.get(R.string.cluster_info)); info.setTextSize(17); root.addView(info);
        status=new TextView(this); status.setTextSize(17); status.setText(getSharedPreferences("cluster",0).getString("status",UiText.get(R.string.cluster_initial))); root.addView(status);
        add(root,R.string.cluster_inspect,()->run(controller::inspect));
        add(root,R.string.cluster_import,()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),71));
        add(root,R.string.cluster_prepare,()->run(controller::prepare));
        add(root,R.string.cluster_export,()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"ovc-cluster-backup.zip"),72));
        add(root,R.string.cluster_apply,()->confirm(R.string.cluster_apply,controller::apply));
        add(root,R.string.cluster_restore,()->confirm(R.string.cluster_restore,controller::restore));
    }
    private void add(LinearLayout root,int label,Runnable action) {
        Button button=new Button(this); button.setText(UiText.get(label)); button.setAllCaps(false); root.addView(button);
        button.setOnClickListener(v->{ if(!BUSY.get()) { try { action.run(); } catch(Exception ex) { status.setText(ex.getMessage()); } } });
    }
    private void confirm(int label,Operation operation) {
        new AlertDialog.Builder(this).setTitle(UiText.get(label)).setMessage(UiText.get(R.string.cluster_confirm))
            .setNegativeButton(UiText.get(R.string.ui_89),null).setPositiveButton(UiText.get(label),(d,w)->run(operation)).show();
    }
    interface Operation { String run() throws Exception; }
    private void run(Operation operation) {
        if(!BUSY.compareAndSet(false,true)) return;
        String working=UiText.get(R.string.cluster_working); status.setText(working);
        getSharedPreferences("cluster",0).edit().putString("status",working).commit();
        WORKER.execute(()->{
            String result;
            try { result=operation.run(); } catch(Exception ex) { result=UiText.get(R.string.cluster_error)+"\n"+ex.getMessage(); }
            getSharedPreferences("cluster",0).edit().putString("status",result).commit();
            String message=result; BUSY.set(false);
            runOnUiThread(()->{ if(!isDestroyed() && !isFinishing()) status.setText(message); });
        });
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null) return;
        run(()->{
            if(request==72) { try(var out=getContentResolver().openOutputStream(data.getData(),"wt")) { if(out==null) throw new java.io.IOException("File unavailable"); controller.exportBackup(out); } return UiText.get(R.string.cluster_exported); }
            synchronized(ClusterController.LOCK) { try(var input=getContentResolver().openInputStream(data.getData())) {
                if(input==null) throw new java.io.IOException("File unavailable");
                return UiText.get(R.string.cluster_imported)+"\n"+ClusterPackage.importZip(this,input).firmware();
            } }
        });
    }
}
