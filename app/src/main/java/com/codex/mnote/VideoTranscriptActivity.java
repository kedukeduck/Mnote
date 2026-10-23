package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit, account-bound video-to-text import. No automatic clipboard reads or source-app access. */
public final class VideoTranscriptActivity extends Activity {
    static final String STATE="video_transcript_state";
    private Session session;
    private EditText link,note;
    private Spinner basis;
    private CheckBox consent,includeSource,audio;
    private LinearLayout authorization,legacyPreview;
    private TextView status,preview;
    private ProgressBar progress;
    private Button parse,refresh,insert;
    private Button reset;
    private boolean destroyed;
    static Intent open(Context context,String path){return new Intent(context,VideoTranscriptActivity.class).putExtra(STATE,path);}

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            session=(Session)getLastNonConfigurationInstance();
            if(session==null)session=new Session(getApplicationContext(),VideoTranscriptState.read(this,
                    saved==null?getIntent().getStringExtra(STATE):saved.getString(STATE)));
            CaptureAccountSession.requireScope(this,session.state.scope);
        }catch(Exception error){Toast.makeText(this,"文字稿草稿无法恢复，请返回记录页重试。",Toast.LENGTH_LONG).show();finish();return;}
        build();restore();session.receiver=new WeakReference<>(this);render();
        if(session.state.imported||(session.state.backgroundFlow&&(session.state.linkOnly||!session.state.backgroundTaskId.isEmpty()))){returnResult();return;}
        activateSession();
        if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::onBackPressed);
    }
    private void build() {
        LinearLayout root=AiUi.page(this,"视频文字稿");
        // Use our explicit exit flow instead of Activity.finish from the shared page helper.
        root.removeAllViews();JournalUi.header(this,root,"视频文字稿",this::onBackPressed);
        LinearLayout body=AiUi.body(this,root);
        TextView headline=JournalUi.text(this,session.state.backgroundFlow?"先记下，慢慢听。":"让声音，成为摘录。",26,R.color.ink);
        headline.setTypeface(android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL));body.addView(headline);
        TextView description=JournalUi.text(this,session.state.backgroundFlow
                ?"粘贴分享链接即可返回保存记录。只有选择解析音频，才会在后台获取并转写；已有想法和摘录保持不变。"
                :"粘贴一条公开的 B 站或抖音视频分享链接。处理在服务端继续，无需停留在原视频。图文作品暂不支持。",13,R.color.ink_muted);
        description.setPadding(0,AiUi.dp(this,10),0,AiUi.dp(this,18));body.addView(description);
        link=AiUi.input(body,"分享链接","可粘贴含链接的完整分享文案",false);link.setId(R.id.video_transcript_link);
        link.setSingleLine(false);link.setMinLines(2);link.setMaxLines(4);link.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if(session.state.backgroundFlow){
            audio=new CheckBox(this);audio.setId(R.id.video_transcript_audio);audio.setText("解析音频 · 在后台生成文字稿");
            audio.setTextColor(getColor(R.color.ink));audio.setTextSize(15);audio.setMinHeight(AiUi.dp(this,52));body.addView(audio);
            TextView hint=JournalUi.text(this,"完成或失败会通知你，也可到「设置 → 解析任务」查看。拒绝通知权限不影响解析；省电或强制停止 APP 时通知可能延迟。",12,R.color.ink_muted);
            body.addView(hint);authorization=JournalUi.column(this);body.addView(authorization);
        }else authorization=body;
        JournalUi.section(authorization,"这段内容的使用依据");
        basis=new Spinner(this);basis.setId(R.id.video_transcript_basis);
        basis.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"请选择，不会自动替你声明","我拥有这段内容","我已取得授权","内容采用开放许可"}));
        basis.setMinimumHeight(AiUi.dp(this,48));authorization.addView(basis,new LinearLayout.LayoutParams(-1,-2));
        note=AiUi.input(authorization,"说明","例如：我的原创视频，或授权 / 开放许可说明",false);note.setId(R.id.video_transcript_note);
        note.setSingleLine(false);note.setMaxLines(3);note.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        consent=new CheckBox(this);consent.setId(R.id.video_transcript_consent);consent.setText("我确认上述声明真实，拥有处理此内容的权利，并同意将本次链接和音频交给服务器转写。声明不会被视为平台已验证授权。");
        consent.setTextSize(13);consent.setTextColor(getColor(R.color.ink_muted));consent.setMinHeight(AiUi.dp(this,48));authorization.addView(consent);
        parse=AiUi.button(body,"解析视频文字稿",true,this::submit);parse.setId(R.id.video_transcript_parse);
        status=JournalUi.text(this,"正在连接解析服务…",14,R.color.copper);status.setId(R.id.video_transcript_status);
        status.setPadding(0,AiUi.dp(this,16),0,AiUi.dp(this,8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setId(R.id.video_transcript_progress);progress.setMax(100);body.addView(progress,new LinearLayout.LayoutParams(-1,AiUi.dp(this,5)));
        refresh=AiUi.button(body,"刷新任务进度",false,()->session.refresh());refresh.setId(R.id.video_transcript_refresh);
        reset=AiUi.button(body,"更换链接重新解析",false,()->{session.reset();restore();render();});reset.setId(R.id.video_transcript_reset);
        legacyPreview=JournalUi.column(this);body.addView(legacyPreview);
        JournalUi.section(legacyPreview,"文字稿预览");
        preview=JournalUi.text(this,"完成后会在这里展示，确认前不会替换记录中的任何内容。",15,R.color.ink);
        preview.setId(R.id.video_transcript_preview);preview.setTextIsSelectable(true);preview.setPadding(AiUi.dp(this,14),AiUi.dp(this,14),AiUi.dp(this,14),AiUi.dp(this,14));
        preview.setBackgroundResource(R.drawable.bg_card);legacyPreview.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        TextView warning=JournalUi.text(this,"自动转写可能有误，尤其是人名、数字、方言和歌词。导入后请在摘录中校对。临时任务最多保留 24 小时，导入的记录会按账号正常保存。",12,R.color.ink_muted);
        warning.setPadding(0,AiUi.dp(this,12),0,AiUi.dp(this,8));legacyPreview.addView(warning);
        includeSource=new CheckBox(this);includeSource.setId(R.id.video_transcript_source);includeSource.setText(session.state.backgroundFlow?"返回后将链接加入来源模块（可在记录页取消）":"同时将视频链接填入来源链接（不改变来源模块的勾选）");includeSource.setTextSize(13);body.addView(includeSource);
        insert=AiUi.button(body,"确认并导入摘录",true,this::importResult);insert.setId(R.id.video_transcript_import);
        AiUi.button(body,getIntent().getBooleanExtra("task_only",false)?"返回任务列表":"返回记录，稍后继续",false,this::keepAndReturn);
        AiUi.button(body,"取消并移除临时任务",false,this::confirmCancel).setId(R.id.video_transcript_cancel);
        if(audio!=null)audio.setOnCheckedChangeListener((button,checked)->{
            session.state.audioRequested=checked;
            if(checked){requestNotifications();if(CaptureAccountSession.hasAccount(this)&&!session.ready&&!session.busy)session.configuration();}
            render();
        });
    }
    private void restore() {
        VideoTranscriptState state=session.state;link.setText(state.share);note.setText(state.note);
        String[] choices={"","owned","authorized","open_license"};int selected=0;for(int i=0;i<choices.length;i++)if(choices[i].equals(state.basis))selected=i;
        basis.setSelection(selected);consent.setChecked(state.consent);includeSource.setChecked(state.importSource);
        if(audio!=null)audio.setChecked(state.audioRequested);
    }
    private void collect() {
        if(session==null||link==null)return;VideoTranscriptState state=session.state;
        // Do not allow form changes to mutate provenance of an already submitted job.
        if(state.id().isEmpty()&&!state.creating){state.share=link.getText().toString();state.note=note.getText().toString();
            state.basis=new String[]{"","owned","authorized","open_license"}[basis.getSelectedItemPosition()];state.consent=consent.isChecked();}
        state.importSource=includeSource.isChecked();
        if(audio!=null)state.audioRequested=audio.isChecked();
    }
    private void submit() {
        collect();VideoTranscriptState state=session.state;
        if(state.backgroundFlow){submitBackground();return;}
        if(session.busy||!state.id().isEmpty()||state.creating)return;
        if(!session.ready||state.agreement.isEmpty()){toast("请先连接到可用的解析服务。");return;}
        if(state.share.trim().isEmpty()||state.share.length()>8192||CaptureSourceUrl.fromTranscriptShareText(state.share).isEmpty()){link.setError("请粘贴一条有效的视频分享链接；多个不同链接请分别解析");return;}
        if(state.basis.isEmpty()){toast("请选择本次内容的使用依据。");return;}
        if(state.note.trim().isEmpty()||state.note.length()>1000||(!"owned".equals(state.basis)&&state.note.trim().length()<8)){note.setError("请填写 1–1000 字说明；授权和开放许可说明至少 8 个字");return;}
        if(!state.consent){toast("请确认本次内容的权利声明与服务器转写说明。");return;}
        session.create();
    }
    private void submitBackground(){
        VideoTranscriptState state=session.state;
        String source=CaptureSourceUrl.fromTranscriptShareText(state.share);
        if(source.isEmpty()){link.setError("请粘贴一条有效的 HTTPS 分享链接；多个不同链接请分别添加");return;}
        try{
            CaptureAccountSession.requireScope(this,state.scope);state.sourceUrl=source;
            if(!state.backgroundTaskId.isEmpty()||state.linkOnly){returnResult();return;}
            if(!state.audioRequested){
                if(getIntent().getBooleanExtra("task_only",false)){toast("本次没有创建解析任务。分享链接可在记录页保存。");session.cancelled=true;state.discard();finish();return;}
                state.linkOnly=true;state.persist();returnResult();return;
            }
            if(!CaptureAccountSession.hasAccount(this)){toast("解析音频需要先登录；也可关闭解析，只保存链接。");return;}
            if(!session.ready||state.agreement.isEmpty()){toast("请等待连接服务，或关闭音频解析后先保存链接。");return;}
            if(state.basis.isEmpty()){toast("请选择本次内容的使用依据。");return;}
            if(state.note.trim().isEmpty()||state.note.length()>1000||(!"owned".equals(state.basis)&&state.note.trim().length()<8)){note.setError("请填写 1–1000 字说明；授权和开放许可说明至少 8 个字");return;}
            if(!state.consent){toast("请确认本次内容的权利声明与服务器转写说明。");return;}
            JSONObject submission=new JSONObject().put("share_text",state.share.trim()).put("rights_basis",state.basis)
                    .put("rights_note",state.note.trim()).put("agreement_version",state.agreement).put("consent",true).put("client_request_id",state.requestId);
            VideoTranscriptTasks.Task task=VideoTranscriptTasks.enqueue(this,state.scope,submission,source,state.captureId);
            state.backgroundTaskId=task.id;state.persist();toast("已加入后台解析，可继续保存记录。");returnResult();
        }catch(Exception error){toast("暂时无法保存链接或创建任务，输入仍保留，请重试。");}
    }
    private void requestNotifications(){
        if(android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        android.content.SharedPreferences preferences=getSharedPreferences("mnote-transcript-notifications",MODE_PRIVATE);
        if(!preferences.getBoolean("asked",false)){preferences.edit().putBoolean("asked",true).apply();requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},183);}
    }
    private void importResult() {
        collect();VideoTranscriptState state=session.state;
        if(!state.completed()||state.text.trim().isEmpty()||state.text.length()>VideoTranscriptState.MAX_TEXT)return;
        try{CaptureAccountSession.requireScope(this,state.scope);state.imported=true;state.persist();returnResult();session.cleanupJob();}
        catch(Exception error){toast("未能交回文字稿，内容仍保留，请重试。");}
    }
    private void returnResult(){setResult(RESULT_OK,new Intent().putExtra(STATE,session.state.file.getAbsolutePath()));
        if(getIntent().getBooleanExtra("task_only",false)&&!session.state.backgroundTaskId.isEmpty()){session.cancelled=true;session.state.discard();}finish();}
    private void keepAndReturn(){
        collect();VideoTranscriptState state=session.state;
        if(state.backgroundFlow&&state.audioRequested&&state.backgroundTaskId.isEmpty()&&!state.linkOnly){
            new AlertDialog.Builder(this).setTitle("还没有加入后台解析")
                    .setMessage("仅勾选音频解析还不会创建任务。加入后台解析后即可返回保存记录，无需等待文字稿完成。")
                    .setPositiveButton("加入后台解析并返回",(dialog,which)->submit())
                    .setNegativeButton("继续设置",null)
                    .setNeutralButton("返回，暂不解析",(dialog,which)->leaveWithoutSubmitting()).show();
            return;
        }
        leaveWithoutSubmitting();
    }
    private void leaveWithoutSubmitting(){
        if(getIntent().getBooleanExtra("task_only",false)&&session.state.backgroundFlow){session.cancelled=true;session.state.discard();finish();}
        else if(session.persist())finish();
    }
    private void confirmCancel() {
        new AlertDialog.Builder(this).setTitle("取消文字稿解析？").setMessage("会尝试删除服务器上的临时任务，不会修改已经写下的想法和摘录。")
                .setNegativeButton("继续",null).setPositiveButton("取消任务",(d,w)->{session.cancelled=true;session.cleanupJob();session.state.discard();setResult(RESULT_CANCELED);finish();}).show();
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void render() {
        if(destroyed||isFinishing())return;VideoTranscriptState state=session.state;
        if(state.backgroundFlow){
            boolean enabled=state.backgroundTaskId.isEmpty()&&!state.linkOnly;
            boolean selected=audio!=null&&audio.isChecked();
            link.setEnabled(enabled);audio.setEnabled(enabled);authorization.setVisibility(selected?View.VISIBLE:View.GONE);
            includeSource.setVisibility(selected&&!getIntent().getBooleanExtra("task_only",false)?View.VISIBLE:View.GONE);legacyPreview.setVisibility(View.GONE);
            insert.setVisibility(View.GONE);reset.setVisibility(View.GONE);progress.setVisibility(selected&&session.busy?View.VISIBLE:View.GONE);
            progress.setIndeterminate(true);refresh.setVisibility(selected?View.VISIBLE:View.GONE);refresh.setEnabled(!session.busy);refresh.setText("重新连接服务");
            parse.setText(selected?"加入后台解析并返回":getIntent().getBooleanExtra("task_only",false)?"暂不解析并返回":"保存链接并返回");parse.setVisibility(View.VISIBLE);
            parse.setEnabled(enabled&&(!selected||session.ready));
            status.setText(!selected?"仅保存链接，不会读取或上传音频。":!CaptureAccountSession.hasAccount(this)
                    ?"解析音频需先登录账号；关闭此选项仍可保存链接。":!state.problem.isEmpty()?state.problem:session.message);
            return;
        }
        boolean locked=session.busy||state.creating||!state.id().isEmpty();
        link.setEnabled(!locked);note.setEnabled(!locked);basis.setEnabled(!locked);consent.setEnabled(!locked);
        parse.setEnabled(!locked&&session.ready);parse.setVisibility(state.id().isEmpty()?View.VISIBLE:View.GONE);
        refresh.setEnabled(!session.busy);refresh.setVisibility(View.VISIBLE);
        refresh.setText(state.creating&&state.id().isEmpty()?"恢复本次提交（不会新建任务）":state.id().isEmpty()?"重新连接服务":"刷新任务进度");
        reset.setVisibility(state.terminal()?View.VISIBLE:View.GONE);reset.setEnabled(!session.busy);
        progress.setVisibility(session.busy||!state.id().isEmpty()&&!state.terminal()?View.VISIBLE:View.GONE);
        progress.setIndeterminate(state.job==null||!state.job.has("progress"));if(state.job!=null)progress.setProgress(Math.max(0,Math.min(100,state.job.optInt("progress"))));
        String label=session.message;
        if(!state.problem.isEmpty())label=state.problem;
        else if(state.creating&&state.id().isEmpty())label=session.busy?"正在提交，请勿重复点击…":"提交结果尚未确认。为避免创建重复任务，不会自动再次提交；可先返回记录稍后再查看。";
        else if(state.job!=null){String stage=state.job.optString("stage",state.job.optString("status"));
            label=switch(stage){case "queued"->"已进入队列，正在等待转写";case "resolving"->"正在解析视频链接";case "downloading"->"正在获取音频";case "transcribing"->"正在识别语音";case "completed"->"转写完成，请校对后导入";case "failed"->"转写未完成";default->"正在处理视频";};
            if("failed".equals(state.job.optString("status"))){JSONObject error=state.job.optJSONObject("error");label=VideoTranscriptClient.error(new IOException(error==null?"failed":error.optString("code")));}}
        status.setText(label);preview.setText(state.text.isEmpty()?"完成后会在这里展示，确认前不会替换记录中的任何内容。":state.text);
        insert.setEnabled(state.completed()&&!state.text.trim().isEmpty()&&state.text.length()<=VideoTranscriptState.MAX_TEXT&&!session.busy);
    }
    @Override public void onBackPressed(){keepAndReturn();}
    @Override protected void onResume(){super.onResume();if(session!=null){
        if(!session.state.scope.equals(CaptureAccountSession.scope(this))){toast("账号已变化，文字稿导入已停止。");finish();return;}
        session.receiver=new WeakReference<>(this);activateSession();}}
    private void activateSession(){if(!session.state.backgroundFlow)session.activate();
        else if(session.state.audioRequested&&CaptureAccountSession.hasAccount(this)&&!session.ready&&!session.busy)session.configuration();}
    @Override protected void onPause(){if(session!=null){if(!session.cancelled){collect();session.persist();}session.pause();}super.onPause();}
    @Override public Object onRetainNonConfigurationInstance(){return session;}
    @Override protected void onSaveInstanceState(Bundle state){if(session!=null&&!session.cancelled){collect();session.persist();state.putString(STATE,session.state.file.getAbsolutePath());}super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;if(session!=null&&session.receiver.get()==this){session.receiver=new WeakReference<>(null);session.pause();}super.onDestroy();}

    /** Survives rotation without holding an Activity. A persisted job ID is resumed after process death. */
    static final class Session {
        static final long REQUEST_TIMEOUT_MILLIS=45_000;
        final Context context;final VideoTranscriptState state;final Handler main=new Handler(Looper.getMainLooper());
        final ExecutorService executor=executor();
        WeakReference<VideoTranscriptActivity> receiver=new WeakReference<>(null);
        boolean ready,busy,cancelled;String message="正在连接解析服务…";int polls,failures;boolean active;
        long generation;Future<?> request;Runnable timeout;
        final Runnable next=this::poll;
        Session(Context context,VideoTranscriptState state){this.context=context.getApplicationContext();this.state=state;}
        private static ExecutorService executor(){ThreadPoolExecutor pool=new ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,new LinkedBlockingQueue<>());pool.allowCoreThreadTimeOut(true);return pool;}
        boolean persist(){try{state.persist();return true;}catch(Exception error){state.problem="文字稿草稿暂时无法保存，请保持此页面并重试。";notifyUi();return false;}}
        void activate(){active=true;if(!busy){if(!state.id().isEmpty()&&!state.terminal())poll();else if(!ready&&state.id().isEmpty()&&!state.creating)configuration();}}
        void pause(){active=false;main.removeCallbacks(next);}
        void refresh(){polls=0;failures=0;state.problem="";if(!busy){if(state.creating&&state.id().isEmpty())create();else if(state.id().isEmpty())configuration();else poll();}}
        void reset(){if(busy||!state.terminal())return;cleanupJob();state.job=null;state.text="";state.share="";state.sourceUrl="";
            state.creating=false;state.imported=false;state.consent=false;state.problem="";state.requestId=UUID.randomUUID().toString();polls=0;failures=0;persist();configuration();}
        void configuration(){if(busy||cancelled)return;ready=false;state.problem="";message="正在连接解析服务…";run("GET","/config",null,(response)->{
            ready=response.optBoolean("ready");state.agreement=response.optString("agreement_version");
            state.problem="";
            message=ready?"已连接 · 最长 "+Math.max(1,response.optInt("max_duration_seconds",1800)/60)+" 分钟。请确认本次授权后开始解析。":"当前服务器尚未启用视频文字稿服务。";persist();
        });}
        void create(){
            try{state.sourceUrl=CaptureSourceUrl.fromTranscriptShareText(state.share);state.creating=true;state.problem="";if(!persist()){state.creating=false;return;}
                // Keep the original payload on retries: a persisted request ID
                // must never be reused with a newly normalized request body.
                JSONObject body=new JSONObject().put("share_text",state.share.trim()).put("rights_basis",state.basis)
                        .put("rights_note",state.note.trim()).put("agreement_version",state.agreement).put("consent",true).put("client_request_id",state.requestId);
                run("POST","/jobs",body,response->{acceptJob(response);state.creating=false;persist();schedule();});
            }catch(Exception error){state.creating=false;state.problem="暂时无法准备任务，请重试。";persist();notifyUi();}
        }
        void poll(){if(busy||cancelled||state.id().isEmpty())return;
            if(++polls>900){state.problem="已暂停自动刷新。任务仍在服务器处理，点击刷新可继续查看。";notifyUi();return;}
            run("GET","/jobs/"+state.id(),null,response->{acceptJob(response);failures=0;persist();schedule();});}
        private void acceptJob(JSONObject response)throws Exception {
            JSONObject job=response.getJSONObject("job");String id=job.optString("id");if(!id.matches("[a-f0-9-]{36}"))throw new IOException("invalid_job");
            if(!state.id().isEmpty()&&!state.id().equals(id))throw new IOException("invalid_job");
            state.job=job;state.problem="";
            if(state.completed()){
                String text=job.optString("text");
                if(text.length()>VideoTranscriptState.MAX_TEXT){state.text="";state.problem="文字稿超过摘录 10 万字上限，未截断或导入任何内容，请改用较短的视频。";}
                else {state.text=text;if(text.trim().isEmpty())state.problem="视频中没有识别到可读的语音文字。";}
                // Segments are not needed for an excerpt; avoid storing a duplicate full transcript.
                job.remove("segments");job.remove("text");
            }
        }
        private void schedule(){main.removeCallbacks(next);if(active&&!cancelled&&!state.id().isEmpty()&&!state.terminal())main.postDelayed(next,2000);}
        void cleanupJob(){main.removeCallbacks(next);if(state.id().isEmpty())return;String id=state.id();
            executor.execute(()->{try{VideoTranscriptClient.request(context,state.scope,"DELETE","/jobs/"+id,null);}catch(Exception ignored){/* Server TTL is the final cleanup boundary. */}});}
        interface Completion {void accept(JSONObject response)throws Exception;}
        void run(String method,String suffix,JSONObject body,Completion callback){
            if(busy||cancelled)return;busy=true;final long attempt=++generation;notifyUi();
            timeout=()->{
                if(attempt!=generation||!busy)return;
                ++generation;busy=false;if(request!=null)request.cancel(true);request=null;timeout=null;
                if(cancelled||!state.file.isFile())return;
                if("POST".equals(method)){
                    // The server may have accepted the idempotent submission. Never invent a new key or retry automatically.
                    state.problem="提交结果暂未确认。点击“恢复本次提交”可安全查询同一次提交，不会新建重复任务。";
                }else if("/config".equals(suffix)){
                    ready=false;state.problem="连接解析服务超时。请检查网络，然后点击“重新连接服务”；已填写的内容会保留。";
                }else state.problem="刷新任务超时。任务可能仍在服务器处理，点击“刷新任务进度”可继续查看。";
                persist();notifyUi();
            };
            main.postDelayed(timeout,REQUEST_TIMEOUT_MILLIS);
            request=executor.submit(()->{JSONObject response=null;Exception failure=null;
                try{CaptureAccountSession.requireScope(context,state.scope);response=VideoTranscriptClient.request(context,state.scope,method,suffix,body);}
                catch(Exception error){failure=error;}
                JSONObject value=response;Exception problem=failure;
                main.post(()->{
                    // A timed-out POST and its explicit recovery can refer to the same server job.
                    // A stale completion must not clear the newer request, import text, or delete that shared job.
                    if(attempt!=generation){if(cancelled||!state.file.isFile())cleanupAbandonedPost(method,value);return;}
                    if(timeout!=null)main.removeCallbacks(timeout);timeout=null;request=null;busy=false;
                    if(cancelled||!state.file.isFile()){
                        cancelled=true;
                        if(value!=null&&"POST".equals(method))cleanupAbandonedPost(method,value);else cleanupJob();return;
                    }
                    try{CaptureAccountSession.requireScope(context,state.scope);if(problem!=null)throw problem;callback.accept(value);}
                    catch(Exception error){if("POST".equals(method)&&error instanceof VideoTranscriptClient.Rejected
                                &&((VideoTranscriptClient.Rejected)error).status>=400&&((VideoTranscriptClient.Rejected)error).status<500
                                &&!"submission_uncertain".equals(error.getMessage())){state.creating=false;state.requestId=UUID.randomUUID().toString();}
                        String code=error.getMessage()==null?"":error.getMessage();
                        boolean uncertainPost="POST".equals(method)&&state.creating
                                &&!"login_required".equals(code)&&!"invalid_session".equals(code)&&!"account_changed".equals(code);
                        state.problem=uncertainPost?"提交结果暂未确认。请点击“恢复本次提交”重试同一次请求，不会新建重复任务。":VideoTranscriptClient.error(error);persist();
                        if(!state.id().isEmpty()&&!state.terminal()&&++failures<3)schedule();}
                    notifyUi();});
            });
        }
        private void cleanupAbandonedPost(String method,JSONObject response){
            if(response==null||!"POST".equals(method))return;
            try{acceptJob(response);cleanupJob();}catch(Exception ignored){/* Invalid/expired jobs still have the server TTL. */}
        }
        void notifyUi(){VideoTranscriptActivity activity=receiver.get();if(activity!=null)activity.render();}
    }
}
