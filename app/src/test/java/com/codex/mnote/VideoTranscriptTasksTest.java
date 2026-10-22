package com.codex.mnote;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Offline outbox, account fencing and idempotent recovery use real private task files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,instrumentedPackages="com.codex.mnote")
public class VideoTranscriptTasksTest {
    static final String SCOPE="a".repeat(64),OTHER="b".repeat(64);
    static final String TASK="12345678-1234-4234-8234-123456789abc";
    static final String REMOTE="98765432-4321-4321-8321-cba987654321";
    static final String URL="https://v.douyin.com/test/";
    Context context;Fake transport;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();
        CaptureAccountSession.preferences(context).edit().clear().putString("scope",SCOPE).putString("account_id","a".repeat(32)).commit();
        deleteTree(new File(context.getFilesDir(),"video-transcript-tasks"));
        context.getSharedPreferences("video-task-history-scan",Context.MODE_PRIVATE).edit().clear().commit();
        context.getSystemService(android.app.NotificationManager.class).cancelAll();
        transport=new Fake();
    }
    @After public void clear()throws Exception {
        CaptureAccountSession.preferences(context).edit().clear().commit();
        context.getSystemService(android.app.NotificationManager.class).cancelAll();
    }
    static void deleteTree(File file)throws Exception {
        if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);}
        if(!file.delete())throw new IOException("test_cleanup_failed");
    }
    static JSONObject body(String id)throws Exception {
        return new JSONObject().put("share_text","我的视频 "+URL).put("rights_basis","owned")
                .put("rights_note","我的原创视频").put("agreement_version","2026-test-v1").put("consent",true).put("client_request_id",id);
    }
    VideoTranscriptTasks.Task enqueue()throws Exception {return VideoTranscriptTasks.enqueue(context,SCOPE,body(TASK),URL,"");}
    static JSONObject job(String id,String request,String status,String text)throws Exception {
        JSONObject result=new JSONObject().put("id",id).put("client_request_id",request).put("status",status).put("stage",status)
                .put("progress","completed".equals(status)?100:5).put("created_at",System.currentTimeMillis()/1000d)
                .put("updated_at",System.currentTimeMillis()/1000d).put("share_text","我的视频 "+URL).put("source_url",URL)
                .put("capture_id","").put("source",new JSONObject().put("platform","douyin").put("title","仅用于测试的自有音频"))
                .put("error",JSONObject.NULL);
        if(text!=null)result.put("text",text);return result;
    }
    @Test public void enqueueIsDurableOfflineHonorsRequestIdAndNeverSubmits()throws Exception {
        VideoTranscriptTasks.Task task=enqueue();assertEquals(TASK,task.id);assertEquals(TASK,task.requestId);
        assertEquals("local_queued",task.status);assertEquals(0,transport.posts);
        File dir=new File(context.getFilesDir(),"video-transcript-tasks/"+SCOPE);
        assertTrue(new File(dir,TASK+".json").isFile());assertFalse(new File(context.getCacheDir(),TASK+".json").exists());
        assertEquals(URL,VideoTranscriptTasks.read(context,SCOPE,TASK).sourceUrl);
        assertEquals(TASK,VideoTranscriptTasks.read(context,SCOPE,TASK).submission.getString("client_request_id"));
    }
    @Test public void duplicateClickReusesTaskButDifferentPayloadCannotReuseKey()throws Exception {
        enqueue();assertEquals(TASK,enqueue().id);assertEquals(1,VideoTranscriptTasks.list(context,SCOPE).size());
        JSONObject changed=body(TASK).put("share_text","https://v.douyin.com/other/");
        assertEquals("request_conflict",assertThrows(Exception.class,()->VideoTranscriptTasks.enqueue(context,SCOPE,changed,URL,"")).getMessage());
    }
    @Test public void missingRightsConsentAndAmbiguousShareNeverEnterQueue()throws Exception {
        assertThrows(Exception.class,()->VideoTranscriptTasks.enqueue(context,SCOPE,body(TASK).put("consent",false),URL,""));
        assertThrows(Exception.class,()->VideoTranscriptTasks.enqueue(context,SCOPE,body(TASK).put("rights_basis",""),URL,""));
        assertThrows(Exception.class,()->VideoTranscriptTasks.enqueue(context,SCOPE,body(TASK).put("share_text",URL+" https://b23.tv/second/"),URL,""));
        assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());
    }
    @Test public void completedTaskCachesFullTextButListContainsOnlyMetadata()throws Exception {
        enqueue();transport.completeAfterPost=true;assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));
        assertEquals("完整的测试文字稿",VideoTranscriptTasks.read(context,SCOPE,TASK).text);
        VideoTranscriptTasks.Task summary=VideoTranscriptTasks.list(context,SCOPE).get(0);
        assertEquals("",summary.text);assertTrue(summary.completed());assertTrue(summary.resultFetched);
        assertFalse(summary.job.has("text"));assertFalse(summary.job.has("segments"));
    }
    @Test public void successfulTaskOnlyNotifiesOnceEvenAfterLaterHistoryRefresh()throws Exception {
        enqueue();transport.completeAfterPost=true;VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        assertTrue(VideoTranscriptTasks.read(context,SCOPE,TASK).notified);
        android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
        assertEquals(1,manager.getActiveNotifications().length);manager.cancelAll();
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);assertEquals(0,manager.getActiveNotifications().length);
        assertEquals("",VideoTranscriptTasks.read(context,SCOPE,TASK).problemCode);
    }
    @Test public void uncertainAcceptedPostRecoversFromListWithoutSecondPost()throws Exception {
        enqueue();transport.failPostAfterAccept=true;assertTrue(VideoTranscriptTasks.reconcile(context,SCOPE,transport));
        assertEquals("uncertain",VideoTranscriptTasks.read(context,SCOPE,TASK).status);assertEquals(1,transport.posts);
        transport.remote.get(REMOTE).put("status","completed").put("stage","completed").put("text","恢复后的文字稿");
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));
        assertEquals(1,transport.posts);assertEquals(REMOTE,VideoTranscriptTasks.read(context,SCOPE,TASK).remoteId);
        assertEquals("恢复后的文字稿",VideoTranscriptTasks.read(context,SCOPE,TASK).text);
    }
    @Test public void uncertainUnacceptedPostRetriesOnlyTheSameRequestKey()throws Exception {
        enqueue();transport.failBeforeAccept=true;assertTrue(VideoTranscriptTasks.reconcile(context,SCOPE,transport));
        transport.failBeforeAccept=false;transport.completeAfterPost=true;
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(2,transport.posts);
        assertEquals(Arrays.asList(TASK,TASK),transport.requestIds);
    }
    @Test public void networkFailureRetainsQueueRatherThanSendingAnUnlistedDuplicate()throws Exception {
        enqueue();transport.failList=true;
        assertThrows(IOException.class,()->VideoTranscriptTasks.reconcile(context,SCOPE,transport));
        assertEquals(0,transport.posts);assertEquals("local_queued",VideoTranscriptTasks.read(context,SCOPE,TASK).status);
    }
    @Test public void accountSwitchBeforeSyncOrWhileRequestRunsDiscardsForeignResult()throws Exception {
        enqueue();CaptureAccountSession.preferences(context).edit().putString("scope",OTHER).commit();
        assertThrows(IOException.class,()->VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(0,transport.lists);
        CaptureAccountSession.preferences(context).edit().putString("scope",SCOPE).commit();
        transport.onList=()->CaptureAccountSession.preferences(context).edit().putString("scope",OTHER).commit();
        assertThrows(IOException.class,()->VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(0,transport.posts);
        assertTrue(VideoTranscriptTasks.list(context,OTHER).isEmpty());
        CaptureAccountSession.preferences(context).edit().putString("scope",SCOPE).commit();
        assertEquals("local_queued",VideoTranscriptTasks.read(context,SCOPE,TASK).status);
    }
    @Test public void queuedRemovalNeverCreatesRemoteTaskEvenIfWorkerAlreadyListedLocals()throws Exception {
        enqueue();transport.onList=()->{try{VideoTranscriptTasks.remove(context,SCOPE,TASK);}catch(Exception error){throw new RuntimeException(error);}};
        try{VideoTranscriptTasks.reconcile(context,SCOPE,transport);}catch(IOException ignored){}
        assertEquals(0,transport.posts);assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());
    }
    @Test public void removeUncertainTaskDoesNotSubmitToCancelAndLaterDeletesRecoveredJob()throws Exception {
        enqueue();transport.failBeforeAccept=true;VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        VideoTranscriptTasks.remove(context,SCOPE,TASK);assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);assertEquals(1,transport.posts);
        transport.remote.put(REMOTE,job(REMOTE,TASK,"processing",null));transport.failBeforeAccept=false;
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);assertEquals(1,transport.posts);assertEquals(1,transport.deletes);
        assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());assertTrue(transport.remote.isEmpty());
    }
    @Test public void captureAssociationDuringDetailFetchSurvivesAndNeverChangesSubmissionFingerprint()throws Exception {
        enqueue();transport.onDetail=()->{try{VideoTranscriptTasks.attachCapture(context,SCOPE,TASK,"capture-new");}catch(Exception error){throw new RuntimeException(error);}};
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        assertEquals("capture-new",VideoTranscriptTasks.read(context,SCOPE,TASK).captureId);
        assertEquals("capture-new",transport.attached);assertFalse(VideoTranscriptTasks.read(context,SCOPE,TASK).associationDirty);
        assertFalse(transport.postBody.has("capture_id"));assertFalse(VideoTranscriptTasks.read(context,SCOPE,TASK).submission.has("capture_id"));
    }
    @Test public void associationChangedDuringAcknowledgementRemainsDirtyForNextPass()throws Exception {
        enqueue();VideoTranscriptTasks.attachCapture(context,SCOPE,TASK,"capture-first");
        transport.onAssociation=()->{try{VideoTranscriptTasks.attachCapture(context,SCOPE,TASK,"capture-second");}catch(Exception error){throw new RuntimeException(error);}};
        assertTrue(VideoTranscriptTasks.reconcile(context,SCOPE,transport));
        VideoTranscriptTasks.Task task=VideoTranscriptTasks.read(context,SCOPE,TASK);assertEquals("capture-second",task.captureId);assertTrue(task.associationDirty);
        transport.onAssociation=null;VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        assertEquals("capture-second",transport.attached);assertFalse(VideoTranscriptTasks.read(context,SCOPE,TASK).associationDirty);
    }
    @Test public void remoteAssociationAppearsForExistingLocalTaskWithoutLocalOverride()throws Exception {
        enqueue();VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        transport.remote.get(REMOTE).put("capture_id","capture-from-other-device");
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        assertEquals("capture-from-other-device",VideoTranscriptTasks.read(context,SCOPE,TASK).captureId);
    }
    @Test public void historyRestoresAllPagesWithoutFetchingOldFullTranscriptsOrNotifying()throws Exception {
        for(int i=0;i<55;i++){
            String id=UUID.randomUUID().toString();transport.remote.put(id,job(id,UUID.randomUUID().toString(),"completed","不应自动拉取的旧全文"));
        }
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(55,VideoTranscriptTasks.list(context,SCOPE).size());
        assertEquals(2,transport.lists);assertEquals(0,transport.details);assertEquals(0,transport.posts);
        for(VideoTranscriptTasks.Task task:VideoTranscriptTasks.list(context,SCOPE)){assertTrue(task.notified);assertFalse(task.resultFetched);}
    }
    @Test public void openingOldResultRequestsOnlyThatTranscriptAndPreservesItsSource()throws Exception {
        String old=UUID.randomUUID().toString();transport.remote.put(old,job(old,UUID.randomUUID().toString(),"completed","按需获取的完整全文").put("capture_id","capture-old"));
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);VideoTranscriptTasks.Task task=VideoTranscriptTasks.list(context,SCOPE).get(0);
        assertEquals("capture-old",task.captureId);assertEquals(0,transport.details);
        VideoTranscriptTasks.requestResult(context,SCOPE,task.id);VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        assertEquals(1,transport.details);assertEquals("按需获取的完整全文",VideoTranscriptTasks.read(context,SCOPE,task.id).text);
        VideoTranscriptState state=VideoTranscriptTasks.resultAsState(context,SCOPE,task.id);
        assertEquals(URL,state.sourceUrl);assertTrue(state.imported);assertEquals("按需获取的完整全文",state.text);
        VideoTranscriptTasks.markImported(context,SCOPE,task.id);assertTrue(VideoTranscriptTasks.read(context,SCOPE,task.id).imported);
    }
    @Test public void oneCorruptFileCannotHideGoodQueueOrBreakSynchronization()throws Exception {
        enqueue();File dir=new File(context.getFilesDir(),"video-transcript-tasks/"+SCOPE);
        Files.write(new File(dir,UUID.randomUUID()+".json").toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        assertEquals(1,VideoTranscriptTasks.list(context,SCOPE).size());transport.completeAfterPost=true;
        boolean pending=VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        VideoTranscriptTasks.Task actual=VideoTranscriptTasks.read(context,SCOPE,TASK);
        assertFalse("status="+actual.status+", problem="+actual.problemCode+", age="+(System.currentTimeMillis()-actual.createdAt)+", posts="+transport.posts,pending);assertEquals(1,transport.posts);
    }
    @Test public void sourceFailureBecomesTerminalWhileQuotaRejectionRemainsRetryable()throws Exception {
        enqueue();transport.postRejection=new VideoTranscriptClient.Rejected("account_busy",429);
        assertTrue(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals("waiting_quota",VideoTranscriptTasks.read(context,SCOPE,TASK).status);
        transport.postRejection=new VideoTranscriptClient.Rejected("unsafe_url",400);
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals("failed",VideoTranscriptTasks.read(context,SCOPE,TASK).status);
        assertEquals("unsafe_url",VideoTranscriptTasks.read(context,SCOPE,TASK).problemCode);
    }
    @Test public void emptySpeechCompletesOnceWithoutEndlessPollingOrDiscardingHistory()throws Exception {
        enqueue();transport.completeAfterPost=true;transport.resultText="";
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));VideoTranscriptTasks.Task task=VideoTranscriptTasks.read(context,SCOPE,TASK);
        assertTrue(task.completed());assertTrue(task.resultFetched);assertEquals("empty_transcript",task.problemCode);
        int details=transport.details;assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(details,transport.details);
        assertEquals("empty_transcript",VideoTranscriptTasks.read(context,SCOPE,TASK).problemCode);
        assertThrows(IOException.class,()->VideoTranscriptTasks.resultAsState(context,SCOPE,TASK));
    }
    @Test public void transcriptExceedingExcerptLimitIsNeverTruncatedOrImported()throws Exception {
        enqueue();transport.completeAfterPost=true;transport.resultText="字".repeat(VideoTranscriptState.MAX_TEXT+1);
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));VideoTranscriptTasks.Task task=VideoTranscriptTasks.read(context,SCOPE,TASK);
        assertEquals("failed",task.status);assertEquals("response_too_large",task.problemCode);assertEquals("",task.text);
        assertThrows(IOException.class,()->VideoTranscriptTasks.resultAsState(context,SCOPE,TASK));
    }
    @Test public void noMoreThanEightActiveRequestsAreProcessedInOnePass()throws Exception {
        for(int i=0;i<12;i++)VideoTranscriptTasks.enqueue(context,SCOPE,body(UUID.randomUUID().toString()),URL,"");
        transport.uniquePostIds=true;assertTrue(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(8,transport.posts);
        assertEquals(12,VideoTranscriptTasks.list(context,SCOPE).size());
    }
    @Test public void uncertainSubmissionOlderThanTwentyFourHoursNeverPostsAgain()throws Exception {
        enqueue();transport.failBeforeAccept=true;VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        rewriteTask(TASK,new JSONObject().put("created_at",VideoTranscriptTasks.now()-java.util.concurrent.TimeUnit.HOURS.toMillis(25)));
        boolean pending=VideoTranscriptTasks.reconcile(context,SCOPE,transport);VideoTranscriptTasks.Task actual=VideoTranscriptTasks.read(context,SCOPE,TASK);
        assertFalse("status="+actual.status+", problem="+actual.problemCode+", age="+(System.currentTimeMillis()-actual.createdAt)+", posts="+transport.posts,pending);assertEquals(1,transport.posts);
        assertEquals("submission_unconfirmed",VideoTranscriptTasks.read(context,SCOPE,TASK).problemCode);
    }
    @Test public void oldOfflineQueueAndKnownQuotaRejectionAreNotTreatedAsUncertainExpiry()throws Exception {
        enqueue();rewriteTask(TASK,new JSONObject().put("created_at",VideoTranscriptTasks.now()-java.util.concurrent.TimeUnit.HOURS.toMillis(25)));
        transport.postRejection=new VideoTranscriptClient.Rejected("account_busy",429);
        assertTrue(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals("waiting_quota",VideoTranscriptTasks.read(context,SCOPE,TASK).status);
        transport.postRejection=null;transport.completeAfterPost=true;
        assertFalse(VideoTranscriptTasks.reconcile(context,SCOPE,transport));assertEquals(2,transport.posts);
        assertTrue(VideoTranscriptTasks.read(context,SCOPE,TASK).completed());
    }
    @Test public void nullRemoteCaptureIdIsEmptyRatherThanLiteralNull()throws Exception {
        transport.remote.put(REMOTE,job(REMOTE,TASK,"completed","旧文字稿").put("capture_id",JSONObject.NULL));
        VideoTranscriptTasks.reconcile(context,SCOPE,transport);assertEquals("",VideoTranscriptTasks.read(context,SCOPE,TASK).captureId);
    }
    @Test public void unrecognizedCredentialAndOwnerFieldsAreNeverPersistedOrSent()throws Exception {
        JSONObject input=body(TASK).put("access_token","SECRET_NOT_FOR_OUTBOX").put("owner","FORGED_OWNER").put("capture_id","untrusted-association");
        VideoTranscriptTasks.enqueue(context,SCOPE,input,URL,"");
        JSONObject saved=VideoTranscriptTasks.read(context,SCOPE,TASK).submission;
        assertFalse(saved.has("access_token"));assertFalse(saved.has("owner"));assertFalse(saved.has("capture_id"));
        transport.completeAfterPost=true;VideoTranscriptTasks.reconcile(context,SCOPE,transport);
        assertFalse(transport.postBody.toString().contains("SECRET_NOT_FOR_OUTBOX"));assertFalse(transport.postBody.has("owner"));
    }
    void rewriteTask(String id,JSONObject changes)throws Exception {
        File file=new File(context.getFilesDir(),"video-transcript-tasks/"+SCOPE+"/"+id+".json");
        JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        for(Iterator<String> keys=changes.keys();keys.hasNext();){String key=keys.next();json.put(key,changes.get(key));}
        CaptureStore.writeJson(json,file,VideoTranscriptTasks.MAX_BYTES);
    }

    final class Fake implements VideoTranscriptTasks.Transport {
        final LinkedHashMap<String,JSONObject> remote=new LinkedHashMap<>();
        final List<String> requestIds=new ArrayList<>();
        int posts,lists,details,deletes;boolean failList,failBeforeAccept,failPostAfterAccept,completeAfterPost,uniquePostIds;
        String resultText="完整的测试文字稿",attached="";JSONObject postBody;Exception postRejection;
        Runnable onList,onDetail,onAssociation;
        @Override public JSONObject request(Context ctx,String scope,String method,String path,JSONObject payload)throws Exception {
            assertEquals(SCOPE,scope);
            if("GET".equals(method)&&path.startsWith("/jobs?")){
                lists++;if(failList)throw new IOException("network_failed");if(onList!=null){Runnable next=onList;onList=null;next.run();}
                int offset=Integer.parseInt(path.substring(path.indexOf("offset=")+7));JSONArray values=new JSONArray();List<JSONObject> all=new ArrayList<>(remote.values());
                for(int i=offset;i<Math.min(offset+50,all.size());i++){
                    JSONObject summary=new JSONObject(all.get(i).toString());summary.remove("text");summary.remove("segments");values.put(summary);
                }
                return new JSONObject().put("jobs",values).put("has_more",offset+50<all.size()).put("next_offset",offset+values.length());
            }
            if("POST".equals(method)&&"/jobs".equals(path)){
                posts++;requestIds.add(payload.getString("client_request_id"));postBody=new JSONObject(payload.toString());
                if(postRejection!=null)throw postRejection;if(failBeforeAccept)throw new IOException("request_timeout");
                String id=uniquePostIds?UUID.randomUUID().toString():REMOTE;
                JSONObject value=job(id,payload.getString("client_request_id"),completeAfterPost?"completed":"processing",completeAfterPost?resultText:null);
                remote.put(id,value);if(failPostAfterAccept){failPostAfterAccept=false;throw new IOException("request_timeout");}
                return new JSONObject().put("job",new JSONObject(value.toString()));
            }
            String id=path.substring("/jobs/".length()).split("/")[0];
            if("POST".equals(method)&&path.endsWith("/capture")){
                attached=payload.getString("capture_id");if(onAssociation!=null)onAssociation.run();remote.get(id).put("capture_id",attached);
                return new JSONObject().put("job",new JSONObject(remote.get(id).toString()));
            }
            if("GET".equals(method)){
                details++;if(onDetail!=null){Runnable next=onDetail;onDetail=null;next.run();}
                return new JSONObject().put("job",new JSONObject(remote.get(id).toString()));
            }
            if("DELETE".equals(method)){deletes++;remote.remove(id);return new JSONObject().put("deleted",true);}
            throw new AssertionError("Unexpected operation "+method+" "+path);
        }
    }
}
