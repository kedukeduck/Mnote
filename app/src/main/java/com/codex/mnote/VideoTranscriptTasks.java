package com.codex.mnote;

import android.content.Context;
import android.content.Intent;
import androidx.work.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Private account-scoped outbox/results. Network processing never owns or edits a capture. */
final class VideoTranscriptTasks {
    static final String ACTION_CHANGED="com.codex.mnote.VIDEO_TRANSCRIPT_TASKS_CHANGED";
    static final String WORK_SCOPE="scope";
    static final int MAX_BYTES=2*1024*1024,MAX_TASKS=5000;
    private static final Object FILE_LOCK=new Object();
    private static final ReentrantLock RUNNING=new ReentrantLock();
    private static final String PREFIX="mnote-video-tasks-";
    interface Transport {JSONObject request(Context context,String scope,String method,String path,JSONObject body)throws Exception;}
    static final class Task {
        String id,requestId,remoteId="",scope,status="local_queued",stage="queued",text="",title="",sourceUrl="",captureId="",problemCode="",share="";
        long createdAt,updatedAt;int progress;boolean imported,notified,associationDirty,deletionPending,resultFetched,resultRequested;
        JSONObject submission=new JSONObject(),job;
        boolean terminal(){return "completed".equals(status)||"failed".equals(status)||"cancelled".equals(status);}
        boolean completed(){return "completed".equals(status);}
        JSONObject json()throws Exception {
            return new JSONObject().put("id",id).put("request_id",requestId).put("remote_id",remoteId).put("scope",scope)
                    .put("status",status).put("stage",stage).put("text",text).put("title",title).put("source_url",sourceUrl)
                    .put("capture_id",captureId).put("problem_code",problemCode).put("share",share).put("created_at",createdAt)
                    .put("updated_at",updatedAt).put("progress",progress).put("imported",imported).put("notified",notified)
                    .put("association_dirty",associationDirty).put("deletion_pending",deletionPending).put("result_fetched",resultFetched).put("result_requested",resultRequested).put("submission",submission).put("job",job==null?JSONObject.NULL:job);
        }
    }
    private VideoTranscriptTasks(){}
    static long now(){return System.currentTimeMillis();}

    /** Commits the outbox before scheduling. No network or record changes occur on this path. */
    static Task enqueue(Context context,String scope,JSONObject submission,String sourceUrl,String captureId)throws Exception {
        CaptureAccountSession.requireScope(context,scope);validateSubmission(submission);
        String clean=CaptureSourceUrl.fromTranscriptShareText(submission.getString("share_text"));
        if(clean.isEmpty())throw new IOException("invalid_url");
        String suppliedId=submission.optString("client_request_id");
        String id=suppliedId.isEmpty()?UUID.randomUUID().toString():uuid(suppliedId);
        Task result;
        synchronized(FILE_LOCK){
            File destination=file(context,scope,id);JSONObject body=new JSONObject().put("client_request_id",id);
            for(String key:new String[]{"share_text","rights_basis","rights_note","agreement_version","consent"})body.put(key,submission.get(key));
            // Association is patched separately. It must never alter an uncertain submission's fingerprint.
            body.remove("capture_id");
            if(destination.isFile()){
                result=readUnlocked(context,scope,id);
                if(result.deletionPending||!result.submission.toString().equals(body.toString()))throw new IOException("request_conflict");
                schedule(context);
                return result;
            }
            if(listUnlocked(context,scope).size()>=MAX_TASKS)throw new IOException("history_full");
            result=new Task();result.id=result.requestId=id;result.scope=scope;result.submission=body;
            result.share=body.getString("share_text");result.sourceUrl=clean;
            result.captureId=recordId(captureId);result.associationDirty=!result.captureId.isEmpty();
            result.createdAt=result.updatedAt=System.currentTimeMillis();writeUnlocked(context,result);
        }
        changed(context);schedule(context);return result;
    }
    static List<Task> list(Context context,String scope)throws Exception {
        synchronized(FILE_LOCK){List<Task> result=listUnlocked(context,scope);result.removeIf(task->task.deletionPending);return result;}
    }
    static Task read(Context context,String scope,String id)throws Exception {
        synchronized(FILE_LOCK){return readUnlocked(context,scope,id);}
    }
    static void attachCapture(Context context,String scope,String id,String captureId)throws Exception {
        synchronized(FILE_LOCK){Task task=readUnlocked(context,scope,id);task.captureId=recordId(captureId);
            task.associationDirty=!task.captureId.isEmpty();task.updatedAt=System.currentTimeMillis();writeUnlocked(context,task);}
        changed(context);schedule(context);
    }
    static void markImported(Context context,String scope,String id)throws Exception {
        synchronized(FILE_LOCK){Task task=readUnlocked(context,scope,id);task.imported=true;writeUnlocked(context,task);}
        changed(context);
    }
    static void requestResult(Context context,String scope,String id)throws Exception {
        synchronized(FILE_LOCK){Task task=readUnlocked(context,scope,id);task.resultRequested=true;writeUnlocked(context,task);}
        schedule(context);
    }
    /** Local removal is immediate; accepted remote work/history is deleted when connectivity returns. */
    static void remove(Context context,String scope,String id)throws Exception {
        synchronized(FILE_LOCK){Task task=readUnlocked(context,scope,id);
            if(task.remoteId.isEmpty()&&"local_queued".equals(task.status)){
                if(!file(context,scope,id).delete())throw new IOException("storage_unavailable");
            }else{task.deletionPending=true;task.text="";task.notified=true;writeUnlocked(context,task);}}
        VideoTranscriptNotifications.cancelTask(context,scope,id);changed(context);schedule(context);
    }
    /** Explicit, small path-only handoff to the capture editor, never an automatic excerpt replacement. */
    static VideoTranscriptState resultAsState(Context context,String scope,String id)throws Exception {
        Task task=read(context,scope,id);
        if(!task.completed()||task.text.trim().isEmpty()||task.text.length()>VideoTranscriptState.MAX_TEXT)throw new IOException("not_completed");
        VideoTranscriptState state=VideoTranscriptState.create(context,scope);
        state.share=task.share;state.requestId=task.requestId;state.sourceUrl=task.sourceUrl;state.text=task.text;
        state.job=task.job==null?new JSONObject().put("id",task.remoteId).put("status","completed"):new JSONObject(task.job.toString());
        state.basis=task.submission.optString("rights_basis");state.note=task.submission.optString("rights_note");
        state.agreement=task.submission.optString("agreement_version");state.consent=task.submission.optBoolean("consent");
        state.imported=true;state.persist();return state;
    }
    static void schedule(Context context){
        if(!CaptureAccountSession.hasAccount(context))return;
        String scope=CaptureAccountSession.scope(context);if(!scope.matches("[a-f0-9]{64}"))return;
        Context app=context.getApplicationContext();Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        Data data=new Data.Builder().putString(WORK_SCOPE,scope).build();
        try{
            WorkManager work=WorkManager.getInstance(app);
            work.enqueueUniqueWork(PREFIX+scope,ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(VideoTranscriptWorker.class)
                    .setInputData(data).setConstraints(network).addTag(PREFIX+scope)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());
            work.enqueueUniquePeriodicWork(PREFIX+scope+"-periodic",ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(VideoTranscriptWorker.class,15,TimeUnit.MINUTES)
                            .setInputData(data).setConstraints(network).addTag(PREFIX+scope).build());
        }catch(RuntimeException ignored){/* The durable outbox is still committed; app-open can schedule again. */}
    }
    static void cancelForScope(Context context,String scope){
        if(scope==null||!scope.matches("[a-f0-9]{64}"))return;
        try{WorkManager work=WorkManager.getInstance(context);work.cancelUniqueWork(PREFIX+scope);work.cancelUniqueWork(PREFIX+scope+"-periodic");}
        catch(RuntimeException ignored){}
        VideoTranscriptNotifications.cancelScope(context,scope);
    }

    /** One bounded reconciliation pass. Called by WorkManager, never the main thread. */
    static boolean reconcile(Context context)throws Exception {
        return reconcile(context,CaptureAccountSession.scope(context),VideoTranscriptClient::request);
    }
    static boolean reconcile(Context context,String scope,Transport transport)throws Exception {
        CaptureAccountSession.requireScope(context,scope);
        if(!RUNNING.tryLock())return true;
        try{
            List<Task> local; synchronized(FILE_LOCK){local=listUnlocked(context,scope);}Map<String,Task> byRemote=new HashMap<>(),byRequest=new HashMap<>();
            for(Task task:local){byRequest.put(task.requestId,task);if(!task.remoteId.isEmpty())byRemote.put(task.remoteId,task);}
            android.content.SharedPreferences scans=context.getSharedPreferences("video-task-history-scan",Context.MODE_PRIVATE);
            int offset=Math.max(0,Math.min(MAX_TASKS,scans.getInt(scope,0)));Set<String> seen=new HashSet<>();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            boolean moreHistory=false;
            for(int page=0;page<100&&System.nanoTime()<deadline;page++){
                JSONObject response=transport.request(context,scope,"GET","/jobs?limit=50&offset="+offset,null);
                JSONArray summaries=response.optJSONArray("jobs");if(summaries==null)throw new IOException("invalid_job");
                for(int i=0;i<summaries.length();i++){
                    JSONObject remote=summaries.getJSONObject(i);String remoteId=uuid(remote.optString("id"));
                    if(!seen.add(remoteId))continue;
                    String request=remote.optString("client_request_id");
                    Task task=byRemote.get(remoteId);if(task==null)task=byRequest.get(request);
                    boolean unknown=task==null;
                    if(unknown){
                        if(local.size()>=MAX_TASKS)continue;
                        task=new Task();task.id=task.requestId=request.matches("[a-f0-9-]{36}")?uuid(request):remoteId;
                        task.scope=scope;task.createdAt=timestamp(remote.opt("created_at"),System.currentTimeMillis());
                        task.share=remote.optString("share_text");task.sourceUrl=CaptureSourceUrl.clean(remote.optString("source_url"));
                        task.captureId=recordId(remote.isNull("capture_id")?"":remote.optString("capture_id"));
                    }
                    merge(context,task,remote,false);
                    // A new installation should not replay notifications for old completed history.
                    if(unknown&&task.terminal())task.notified=true;
                    save(context,task,unknown);byRemote.put(remoteId,task);byRequest.put(task.requestId,task);
                    if(unknown)local.add(task);
                }
                moreHistory=response.optBoolean("has_more");
                if(!moreHistory){scans.edit().putInt(scope,0).apply();break;}
                int next=response.optInt("next_offset",offset+summaries.length());if(next<=offset)throw new IOException("invalid_job");offset=next;
                scans.edit().putInt(scope,offset).apply();
            }
            boolean pending=moreHistory;int processed=0;
            local.sort(Comparator.comparingLong(task->task.createdAt));
            for(Task stale:local){
                CaptureAccountSession.requireScope(context,scope);Task task=read(context,scope,stale.id);
                boolean wantsResult=task.completed()&&!task.resultFetched&&(task.resultRequested||task.submission.length()>0);
                boolean needsNetwork=task.deletionPending&&!task.remoteId.isEmpty()||!task.terminal()
                        ||wantsResult||task.associationDirty;
                if(System.nanoTime()>=deadline&&needsNetwork){pending=true;continue;}
                if(needsNetwork&&++processed>8){pending=true;continue;}
                try{
                    if(task.deletionPending){
                        // Never submit a job merely to cancel an uncertain POST. Reconciliation
                        // above will recover its existing UUID if the server accepted it.
                        if(!task.remoteId.isEmpty()){
                            try{transport.request(context,scope,"DELETE","/jobs/"+task.remoteId,null);}
                            catch(VideoTranscriptClient.Rejected rejected){if(rejected.status!=404)throw rejected;}
                            synchronized(FILE_LOCK){if(!file(context,scope,task.id).delete())throw new IOException("storage_unavailable");}
                        }
                        continue;
                    }
                    if(task.remoteId.isEmpty()&&!task.terminal()){
                        if(Set.of("uncertain","submitting").contains(task.status)&&now()-task.createdAt>TimeUnit.HOURS.toMillis(24)){
                            task.status="failed";task.stage="failed";task.problemCode="submission_unconfirmed";save(context,task);notifyTerminal(context,scope,task.id);continue;
                        }
                        validateSubmission(task.submission);task.status="submitting";task.problemCode="";save(context,task);
                        JSONObject response=transport.request(context,scope,"POST","/jobs",new JSONObject(task.submission.toString()));
                        merge(context,task,response.getJSONObject("job"),true);save(context,task);
                    }
                    if(!task.remoteId.isEmpty()&&(!task.terminal()||wantsResult)){
                        JSONObject response=transport.request(context,scope,"GET","/jobs/"+task.remoteId,null);
                        merge(context,task,response.getJSONObject("job"),true);save(context,task);
                    }
                    // Always reload the association: capture saving can race an in-flight request.
                    task=read(context,scope,task.id);
                    if(task.associationDirty&&!task.remoteId.isEmpty()){
                        String attached=task.captureId;
                        transport.request(context,scope,"POST","/jobs/"+task.remoteId+"/capture",new JSONObject().put("capture_id",attached));
                        synchronized(FILE_LOCK){task=readUnlocked(context,scope,task.id);if(attached.equals(task.captureId))task.associationDirty=false;writeUnlocked(context,task);}
                    }
                    if(!task.deletionPending)notifyTerminal(context,scope,task.id);
                }catch(Exception failure){
                    CaptureAccountSession.requireScope(context,scope);String code=safeCode(failure);
                    if("account_changed".equals(code))throw failure;
                    synchronized(FILE_LOCK){
                        Task latest=readUnlocked(context,scope,task.id);latest.problemCode=code;
                        if(isAuthentication(code))latest.status="blocked_login";
                        else if(isTerminal(failure)){
                            // A missing completed result does not erase the cached text/history.
                            if(!latest.completed()||!latest.resultFetched){latest.status="failed";latest.stage="failed";}
                        }else if(latest.remoteId.isEmpty()){
                            latest.status=Set.of("account_busy","account_limit","daily_limit","queue_full","browser_busy","http_429").contains(code)?"waiting_quota":"uncertain";
                        }
                        latest.updatedAt=System.currentTimeMillis();writeUnlocked(context,latest);task=latest;
                    }
                    if(!task.deletionPending)notifyTerminal(context,scope,task.id);
                    if(isAuthentication(code))throw failure;
                }
                if(!task.terminal()||task.associationDirty||task.completed()&&!task.resultFetched&&(task.resultRequested||task.submission.length()>0))pending=true;
            }
            changed(context);return pending;
        }finally{RUNNING.unlock();}
    }
    private static void merge(Context context,Task task,JSONObject remote,boolean full)throws Exception {
        String id=uuid(remote.optString("id"));if(!task.remoteId.isEmpty()&&!id.equals(task.remoteId))throw new IOException("invalid_job");
        String status=remote.optString("status");
        if(!Set.of("queued","running","processing","completed","failed","cancelled").contains(status))throw new IOException("invalid_job");
        // A delayed summary must never regress a locally cached terminal result.
        if(task.terminal()&&!status.equals(task.status))return;
        task.remoteId=id;task.status=status;task.stage=remote.optString("stage",status);task.progress=Math.max(0,Math.min(100,remote.optInt("progress")));
        task.updatedAt=timestamp(remote.opt("updated_at"),System.currentTimeMillis());
        JSONObject source=remote.optJSONObject("source");if(source!=null)task.title=limited(source.optString("title"),1000);
        if(task.share.isEmpty())task.share=limited(remote.optString("share_text"),8192);
        if(task.sourceUrl.isEmpty())task.sourceUrl=CaptureSourceUrl.clean(remote.optString("source_url"));
        if(!task.associationDirty&&remote.has("capture_id"))task.captureId=recordId(remote.isNull("capture_id")?"":remote.optString("capture_id"));
        String previousProblem=task.problemCode;
        JSONObject error=remote.optJSONObject("error");task.problemCode=error==null?"":safeCode(new IOException(error.optString("code")));
        if(!full&&task.completed()&&task.resultFetched&&"empty_transcript".equals(previousProblem))task.problemCode=previousProblem;
        if(full&&"completed".equals(status)){
            String text=remote.optString("text");if(text.length()>VideoTranscriptState.MAX_TEXT)throw new IOException("response_too_large");
            task.text=text;task.resultFetched=true;if(text.trim().isEmpty())task.problemCode="empty_transcript";
        }
        JSONObject copy=new JSONObject(remote.toString());copy.remove("text");copy.remove("segments");task.job=copy;
    }
    private static void notifyTerminal(Context context,String scope,String id)throws Exception {
        synchronized(FILE_LOCK){Task task=readUnlocked(context,scope,id);if(!task.terminal()||task.notified)return;
            if(task.completed()&&!task.resultFetched)return;
            CaptureAccountSession.requireScope(context,scope);
            if(VideoTranscriptNotifications.show(context,task)){task.notified=true;writeUnlocked(context,task);}}
    }
    private static void save(Context context,Task task)throws Exception {save(context,task,false);}
    private static void save(Context context,Task task,boolean allowCreate)throws Exception {
        synchronized(FILE_LOCK){
            File target=file(context,task.scope,task.id);
            if(!target.isFile()&&!allowCreate)throw new IOException("task_removed");
            if(target.isFile()){
                Task latest=readUnlocked(context,task.scope,task.id);
                if(latest.associationDirty){task.captureId=latest.captureId;task.associationDirty=true;}
                task.imported=latest.imported;task.notified=latest.notified;
                task.deletionPending=latest.deletionPending;
                task.resultRequested=latest.resultRequested;
                if(task.text.isEmpty()&&!latest.text.isEmpty()&&!latest.deletionPending)task.text=latest.text;
                if(task.deletionPending)task.text="";
            }
            writeUnlocked(context,task);
        }
    }
    private static File directory(Context context,String scope)throws Exception {
        CaptureAccountSession.requireScope(context,scope);if(!scope.matches("[a-f0-9]{64}"))throw new IOException("login_required");
        File root=new File(context.getFilesDir(),"video-transcript-tasks");File dir=new File(root,scope);
        if(Files.isSymbolicLink(root.toPath())||Files.isSymbolicLink(dir.toPath()))throw new IOException("invalid_job");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("storage_unavailable");return dir.getCanonicalFile();
    }
    private static File file(Context context,String scope,String id)throws Exception {
        File dir=directory(context,scope),candidate=new File(dir,uuid(id)+".json");
        if(Files.isSymbolicLink(candidate.toPath())||!candidate.getCanonicalFile().getParentFile().equals(dir))throw new IOException("invalid_job");
        return candidate;
    }
    private static void writeUnlocked(Context context,Task task)throws Exception {CaptureAccountSession.requireScope(context,task.scope);CaptureStore.writeJson(task.json(),file(context,task.scope,task.id),MAX_BYTES);}
    private static Task readUnlocked(Context context,String scope,String id)throws Exception {
        File file=file(context,scope,id);if(!file.isFile()||file.length()>MAX_BYTES)throw new IOException("invalid_job");
        JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));Task task=new Task();
        task.id=uuid(json.getString("id"));task.requestId=uuid(json.getString("request_id"));task.scope=json.getString("scope");
        if(!id.equals(task.id)||!scope.equals(task.scope))throw new IOException("invalid_job");
        task.remoteId=json.optString("remote_id");if(!task.remoteId.isEmpty())uuid(task.remoteId);
        task.status=json.optString("status");task.stage=json.optString("stage");task.text=limited(json.optString("text"),VideoTranscriptState.MAX_TEXT);
        task.title=limited(json.optString("title"),1000);task.sourceUrl=CaptureSourceUrl.clean(json.optString("source_url"));task.captureId=recordId(json.optString("capture_id"));
        task.problemCode=json.optString("problem_code");task.share=limited(json.optString("share"),8192);task.createdAt=json.optLong("created_at");task.updatedAt=json.optLong("updated_at");
        task.progress=json.optInt("progress");task.imported=json.optBoolean("imported");task.notified=json.optBoolean("notified");task.associationDirty=json.optBoolean("association_dirty");task.deletionPending=json.optBoolean("deletion_pending");task.resultFetched=json.optBoolean("result_fetched");
        task.resultRequested=json.optBoolean("result_requested");
        task.submission=json.optJSONObject("submission");if(task.submission==null)task.submission=new JSONObject();task.job=json.optJSONObject("job");return task;
    }
    private static List<Task> listUnlocked(Context context,String scope)throws Exception {
        File[] files=directory(context,scope).listFiles();List<Task> result=new ArrayList<>();if(files!=null)for(File candidate:files){
            if(!candidate.getName().matches("[a-f0-9-]{36}\\.json"))continue;
            try{Task task=readUnlocked(context,scope,candidate.getName().substring(0,36));task.text="";result.add(task);}catch(Exception ignored){/* One corrupt entry never hides other history or gets sent. */}
        }
        result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));return result;
    }
    private static void validateSubmission(JSONObject body)throws Exception {
        if(body==null||!body.optBoolean("consent")||body.optString("agreement_version").isBlank())throw new IOException("consent_required");
        if(!Set.of("owned","authorized","open_license").contains(body.optString("rights_basis")))throw new IOException("rights_required");
        String note=body.optString("rights_note").trim();if(note.isEmpty()||note.length()>1000||(!"owned".equals(body.optString("rights_basis"))&&note.length()<8))throw new IOException("rights_required");
        if(body.optString("share_text").isEmpty()||body.optString("share_text").length()>8192)throw new IOException("invalid_url");
    }
    private static String uuid(String id)throws IOException {
        try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(IllegalArgumentException error){throw new IOException("invalid_job");}
    }
    private static String recordId(String id)throws IOException {
        if(id==null||id.isEmpty())return "";if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")||id.contains(".."))throw new IOException("invalid_capture_id");return id;
    }
    private static String limited(String value,int max)throws IOException {if(value.length()>max)throw new IOException("response_too_large");return value;}
    private static long timestamp(Object value,long fallback){
        if(value instanceof Number){double numeric=((Number)value).doubleValue();return numeric<100_000_000_000d?(long)(numeric*1000):(long)numeric;}
        if(value instanceof String)try{return java.time.Instant.parse((String)value).toEpochMilli();}catch(Exception ignored){}return fallback;
    }
    static String safeCode(Exception error){
        if(error instanceof VideoTranscriptClient.Rejected){int status=((VideoTranscriptClient.Rejected)error).status;if(status==401)return "unauthorized";}
        String value=error.getMessage();return value!=null&&value.matches("[a-z][a-z0-9_]{0,63}")?value:"network_failed";
    }
    static boolean isAuthentication(String code){return Set.of("login_required","unauthorized","invalid_session","account_changed","http_401","forbidden","http_403").contains(code);}
    private static boolean isTerminal(Exception failure){
        String code=safeCode(failure);
        if(Set.of("invalid_url","unsafe_url","unsupported_source","consent_required","rights_required","agreement_required","request_conflict","not_found","task_expired","history_full","response_too_large").contains(code))return true;
        if(failure instanceof VideoTranscriptClient.Rejected){int status=((VideoTranscriptClient.Rejected)failure).status;return status>=400&&status<500&&status!=401&&status!=403&&status!=408&&status!=429;}
        return false;
    }
    private static void changed(Context context){context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));}
}
