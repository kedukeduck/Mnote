package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Account-bound task inbox. Results are read separately and imported only after explicit confirmation. */
public final class VideoTranscriptTasksActivity extends Activity {
    private String scope,captureId="",selected="";
    private LinearLayout body;
    private TextView status;
    private Button refresh;
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private boolean destroyed,registered,refreshing;
    private int filter,generation;
    private final BroadcastReceiver changes=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){load();}};
    static Intent open(Context context,String scope,String captureId){
        return new Intent(context,VideoTranscriptTasksActivity.class).putExtra("account_scope",scope)
                .putExtra("capture_id",captureId==null?"":captureId);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);scope=getIntent().getStringExtra("account_scope");
        if(scope==null)scope=CaptureAccountSession.scope(this);
        captureId=getIntent().getStringExtra("capture_id");if(captureId==null)captureId="";
        selected=state==null?getIntent().getStringExtra("task_id"):state.getString("selected","");if(selected==null)selected="";
        if(state!=null)filter=state.getInt("filter",0);
        if(!validScope()){finish();return;}
        LinearLayout root=AiUi.page(this,"解析任务");root.removeAllViews();JournalUi.header(this,root,"解析任务",this::back);
        body=AiUi.body(this,root);status=JournalUi.text(this,"正在读取任务…",13,R.color.ink_muted);body.addView(status);
        refresh=AiUi.button(body,"刷新解析情况",false,this::refresh);refresh.setId(R.id.transcript_tasks_refresh);
        LinearLayout content=JournalUi.column(this);content.setId(R.id.transcript_tasks_body);body.addView(content);body=content;
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::back);
        load();refresh();
    }
    private boolean validScope(){
        return scope!=null&&scope.matches("[a-f0-9]{64}")&&scope.equals(CaptureAccountSession.scope(this))&&CaptureAccountSession.hasAccount(this);
    }
    @Override protected void onStart(){super.onStart();
        if(!registered){ContextCompat.registerReceiver(this,changes,new IntentFilter(VideoTranscriptTasks.ACTION_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;}
    }
    @Override protected void onStop(){if(registered){unregisterReceiver(changes);registered=false;}super.onStop();}
    @Override protected void onResume(){super.onResume();if(!validScope()){finish();return;}if(body!=null)load();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("selected",selected);state.putInt("filter",filter);super.onSaveInstanceState(state);}
    private void load(){
        if(destroyed||body==null)return;if(!validScope()){finish();return;}
        final int request=++generation;final String chosen=selected;
        executor.execute(()->{try{
            List<VideoTranscriptTasks.Task> tasks=VideoTranscriptTasks.list(this,scope);
            VideoTranscriptTasks.Task detail=chosen.isEmpty()?null:VideoTranscriptTasks.read(this,scope,chosen);
            if(detail!=null&&detail.completed()&&!detail.resultFetched&&!detail.resultRequested){VideoTranscriptTasks.requestResult(this,scope,detail.id);}
            runOnUiThread(()->{if(destroyed||request!=generation||!validScope())return;
                body.removeAllViews();if(detail==null)showList(tasks);else showDetail(detail);
            });
        }catch(Exception error){runOnUiThread(()->{if(!destroyed&&request==generation){status.setText("任务暂不可读取，请刷新；其他记录没有变化。");}});}});
    }
    private void refresh(){
        if(refreshing||destroyed||!validScope())return;refreshing=true;refresh.setEnabled(false);status.setText("正在同步解析情况；可继续查看本机任务。");
        VideoTranscriptTasks.schedule(this);
        executor.execute(()->{String problem="";try{VideoTranscriptTasks.reconcile(this);}
            catch(Exception error){problem=VideoTranscriptClient.error(error);}
            final String text=problem;runOnUiThread(()->{if(destroyed)return;refreshing=false;refresh.setEnabled(true);
                status.setText(text.isEmpty()?"后台任务与记录独立保存；结果不会自动替换摘录。":text);load();});});
    }
    private void showList(List<VideoTranscriptTasks.Task> tasks){
        TextView title=JournalUi.text(this,captureId.isEmpty()?"把等待，留给后台。":"这条记录的解析",25,R.color.ink);body.addView(title);
        LinearLayout filters=new LinearLayout(this);body.addView(filters);
        String[] labels={"全部","进行中","已完成","未完成"};
        for(int i=0;i<labels.length;i++){final int choice=i;Button button=new Button(this);button.setText(labels[i]);button.setTextSize(12);JournalUi.quiet(button);
            button.setTextColor(getColor(filter==i?R.color.copper:R.color.ink_muted));button.setOnClickListener(v->{filter=choice;load();});filters.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
        int count=0;
        for(VideoTranscriptTasks.Task task:tasks){
            if(!captureId.isEmpty()&&!captureId.equals(task.captureId))continue;
            if(filter==1&&task.terminal()||filter==2&&!task.completed()||filter==3&&(!task.terminal()||task.completed()))continue;
            count++;LinearLayout card=JournalUi.column(this);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams spacing=new LinearLayout.LayoutParams(-1,-2);spacing.topMargin=dp(12);body.addView(card,spacing);
            card.addView(JournalUi.text(this,label(task),12,task.completed()?R.color.copper:R.color.ink_muted));
            TextView heading=JournalUi.text(this,task.title.isBlank()?"视频文字稿":task.title,17,R.color.ink);heading.setMaxLines(2);card.addView(heading);
            TextView link=JournalUi.text(this,task.sourceUrl,12,R.color.ink_muted);link.setMaxLines(1);card.addView(link);
            card.addView(JournalUi.text(this,android.text.format.DateFormat.format("MM-dd HH:mm",task.createdAt).toString()+(task.imported?" · 已导入":""),12,R.color.ink_muted));
            card.setFocusable(true);card.setClickable(true);card.setContentDescription(heading.getText()+"，"+label(task));card.setOnClickListener(v->{selected=task.id;load();});
        }
        if(count==0){TextView empty=JournalUi.text(this,"这里还没有符合条件的任务。\n添加分享链接时勾选「解析音频」，保存记录后也能在这里查看。",15,R.color.ink_muted);empty.setPadding(0,dp(24),0,dp(24));body.addView(empty);}
        AiUi.button(body,"新建解析任务",false,()->addLink(null));
        AiUi.button(body,"通知设置",false,this::notifications);
    }
    private void showDetail(VideoTranscriptTasks.Task task){
        body.addView(JournalUi.text(this,task.title.isBlank()?"视频文字稿":task.title,25,R.color.ink));
        body.addView(JournalUi.text(this,label(task),14,R.color.copper));
        if(!task.terminal()){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgress(task.progress);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));}
        if(!task.problemCode.isEmpty())body.addView(JournalUi.text(this,VideoTranscriptClient.error(new IOException(task.problemCode)),13,R.color.ink_muted));
        if(!task.sourceUrl.isEmpty())AiUi.button(body,"打开原始链接",false,()->openSource(task.sourceUrl));
        if(task.completed()){
            JournalUi.section(body,"文字稿 · 自动识别，尚未核验");
            TextView text=JournalUi.text(this,task.text.isEmpty()?"正在拉取完整文字稿，请刷新；已有记录不受影响。":task.text,16,R.color.ink);
            text.setTextIsSelectable(true);text.setLineSpacing(dp(4),1.2f);body.addView(text);
            if(!task.text.isEmpty()){
                AiUi.button(body,"复制文字稿",false,()->{ClipboardManager clipboard=getSystemService(ClipboardManager.class);if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("视频文字稿",task.text));toast("已复制文字稿，请校对后使用。");}});
                if(!task.captureId.isEmpty()){Button insert=AiUi.button(body,task.imported?"再次导入到关联记录":"导入到关联记录的摘录",true,()->prepareImport(task));insert.setId(R.id.transcript_tasks_import);}
                else body.addView(JournalUi.text(this,"任务尚未关联已保存的记录。可复制文字稿后自行记录；不会自动创建或改写笔记。",12,R.color.ink_muted));
            }
        }
        if(!task.captureId.isEmpty())AiUi.button(body,"查看 / 编辑关联记录",false,()->startActivity(new Intent(this,CaptureRecordEditActivity.class).putExtra(CaptureRecordEditActivity.ID,task.captureId).putExtra(CaptureRecordEditActivity.SCOPE,scope)));
        if(task.terminal())AiUi.button(body,"重新添加链接并选择解析",false,()->addLink(task));
        AiUi.button(body,task.terminal()?"删除解析任务":"取消并删除解析任务",false,()->new AlertDialog.Builder(this).setTitle("删除这条解析任务？")
                .setMessage("会移除任务历史和文字稿；已保存的记录及已导入的摘录不会被删除。联网后同步移除服务端任务。")
                .setNegativeButton("保留",null).setPositiveButton("删除任务",(d,w)->executor.execute(()->{try{VideoTranscriptTasks.remove(this,scope,task.id);runOnUiThread(()->{if(!destroyed){selected="";load();}});}catch(Exception e){runOnUiThread(()->toast("删除未完成，请重试。"));}})).show());
        AiUi.button(body,"返回任务列表",false,()->{selected="";load();});
    }
    private void prepareImport(VideoTranscriptTasks.Task task){
        executor.execute(()->{try{
            CaptureStore.CaptureRecord record=CaptureRecordEdits.latest(this,scope,task.captureId);String baseline=CaptureRecordEdits.fingerprint(record);
            runOnUiThread(()->{if(destroyed||!validScope())return;new AlertDialog.Builder(this).setTitle("导入这份文字稿？")
                    .setMessage(record.sourceText.isEmpty()?"仅填入关联记录的摘录。想法、原文、截图和标签保持不变；自动转写仍需校对。":"会替换关联记录的现有摘录。想法、原文、截图和标签保持不变；若记录期间有新修改，会停止导入。")
                    .setNegativeButton("保留当前摘录",null).setPositiveButton("确认导入",(d,w)->executor.execute(()->{try{
                        CaptureRecordEdits.importTranscript(this,scope,task.captureId,baseline,task.text,task.id);
                        VideoTranscriptTasks.markImported(this,scope,task.id);runOnUiThread(()->{toast("文字稿已导入，其他内容保持不变。");load();});
                    }catch(Exception error){runOnUiThread(()->toast(CaptureRecordEdits.errorMessage(error)));}})).show();});
        }catch(Exception error){runOnUiThread(()->toast(CaptureRecordEdits.errorMessage(error)));}});
    }
    private void addLink(VideoTranscriptTasks.Task task){try{
        VideoTranscriptState state=VideoTranscriptState.create(this,scope);state.backgroundFlow=true;state.audioRequested=true;state.importSource=true;
        if(task!=null){state.share=task.share;state.captureId=task.captureId;state.basis=task.submission.optString("rights_basis");state.note=task.submission.optString("rights_note");}
        state.consent=false;state.persist();startActivity(VideoTranscriptActivity.open(this,state.file.getAbsolutePath()).putExtra("task_only",true));
    }catch(Exception error){toast("暂时无法打开分享链接页面，请重试。");}}
    private void notifications(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},184);return;}
        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));
    }
    private void openSource(String url){if(!CaptureSourceUrl.clean(url).isEmpty())try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(RuntimeException error){toast("没有可打开链接的应用。");}}
    private static String label(VideoTranscriptTasks.Task task){
        if(task.completed())return "empty_transcript".equals(task.problemCode)?"处理完成 · 未识别到文字":"已完成";
        if("failed".equals(task.status))return "解析失败";if("cancelled".equals(task.status))return "已取消";
        if("blocked_login".equals(task.status))return "等待重新登录";if("uncertain".equals(task.status))return "正在确认提交结果";
        if("waiting_quota".equals(task.status))return "等待空闲名额 / 可用额度";
        return switch(task.stage){case "resolving"->"正在解析链接";case "downloading"->"正在获取音轨";case "transcribing"->"正在生成文字稿";default->"等待后台处理";};
    }
    private int dp(int value){return AiUi.dp(this,value);}
    private void toast(String text){if(!destroyed)Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void back(){if(!selected.isEmpty()){selected="";load();}else finish();}
    @Override public void onBackPressed(){back();}
    @Override protected void onDestroy(){destroyed=true;if(registered)unregisterReceiver(changes);executor.shutdownNow();super.onDestroy();}
}
