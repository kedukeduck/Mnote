package com.codex.mnote;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,instrumentedPackages="com.codex.mnote")
public class VideoTranscriptSchedulerTest {
    static final String SCOPE="a".repeat(64),OTHER="b".repeat(64);
    static final String TASK="12345678-1234-4234-8234-123456789abc";
    static final String REMOTE="98765432-4321-4321-8321-cba987654321";
    static final String URL="https://v.douyin.com/example/";
    Context context;HeldExecutor executor;RecordingBackend backend;
    VideoTranscriptScheduler.Dispatcher previous,dispatcher;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();
        CaptureAccountSession.preferences(context).edit().clear().putString("scope",SCOPE).putString("account_id","a".repeat(32)).commit();
        VideoTranscriptTasksTest.deleteTree(new File(context.getFilesDir(),"video-transcript-tasks"));
        context.getSharedPreferences("video-task-history-scan",Context.MODE_PRIVATE).edit().clear().commit();
        context.getSystemService(NotificationManager.class).cancelAll();
        executor=new HeldExecutor();backend=new RecordingBackend();
    }
    @After public void clear(){
        if(previous!=null)VideoTranscriptScheduler.installForTests(previous);
        CaptureAccountSession.preferences(context).edit().clear().commit();
        context.getSystemService(NotificationManager.class).cancelAll();
    }
    void install(VideoTranscriptScheduler.Runner runner){
        dispatcher=new VideoTranscriptScheduler.Dispatcher(executor,runner,backend);
        previous=VideoTranscriptScheduler.installForTests(dispatcher);
    }
    @Test public void workRequestsUseConnectedNetworkAndFixedShortDelay(){
        OneTimeWorkRequest immediate=VideoTranscriptScheduler.request(SCOPE,0);
        OneTimeWorkRequest later=VideoTranscriptScheduler.request(SCOPE,VideoTranscriptScheduler.FOLLOW_UP_SECONDS);
        assertEquals(0,immediate.getWorkSpec().initialDelay);
        assertEquals(15_000,later.getWorkSpec().initialDelay);
        assertEquals(NetworkType.CONNECTED,later.getWorkSpec().constraints.getRequiredNetworkType());
        assertEquals(SCOPE,later.getWorkSpec().input.getString(VideoTranscriptTasks.WORK_SCOPE));
        assertTrue(later.getTags().contains(VideoTranscriptScheduler.PREFIX+SCOPE));
        assertNotEquals(VideoTranscriptScheduler.PREFIX,VideoTranscriptScheduler.LEGACY_PREFIX);
        assertEquals(0,later.getWorkSpec().runAttemptCount);
    }
    @Test public void periodicRecoveryRetainsFifteenMinuteInterval(){
        PeriodicWorkRequest work=VideoTranscriptScheduler.periodic(SCOPE);
        assertEquals(TimeUnit.MINUTES.toMillis(15),work.getWorkSpec().intervalDuration);
        assertEquals(NetworkType.CONNECTED,work.getWorkSpec().constraints.getRequiredNetworkType());
        assertEquals(SCOPE,work.getWorkSpec().input.getString(VideoTranscriptTasks.WORK_SCOPE));
    }
    @Test public void immediatePassIsNotRunOnCallingThreadAndUsesApplicationContext(){
        int[] passes={0};install((app,scope)->{assertSame(context,app);passes[0]++;return false;});
        try(var activity=Robolectric.buildActivity(Activity.class).setup()){
            VideoTranscriptScheduler.schedule(activity.get());assertEquals(0,passes[0]);assertEquals(1,backend.schedules);
        }
        executor.runNext();assertEquals(1,passes[0]);assertEquals(0,backend.followUps);
    }
    @Test public void multipleQueuedKicksCoalesceToOneBoundedPass(){
        int[] passes={0};install((app,scope)->{passes[0]++;return false;});
        for(int i=0;i<20;i++)VideoTranscriptScheduler.schedule(context);
        assertEquals(1,executor.size());executor.runNext();assertEquals(1,passes[0]);assertEquals(0,executor.size());
    }
    @Test public void kickArrivingDuringFinishingPassIsNeverLost(){
        int[] passes={0};install((app,scope)->{
            passes[0]++;if(passes[0]==1)for(int i=0;i<10;i++)VideoTranscriptScheduler.schedule(context);return false;
        });
        VideoTranscriptScheduler.schedule(context);executor.runNext();
        assertEquals(1,passes[0]);assertEquals(1,executor.size());executor.runNext();assertEquals(2,passes[0]);
        assertEquals(0,executor.size());
    }
    @Test public void normalPendingPassSchedulesFixedFollowUpInsteadOfRetry(){
        install((app,scope)->true);VideoTranscriptScheduler.schedule(context);executor.runNext();
        assertEquals(1,backend.followUps);assertEquals(0,executor.size());
        assertEquals(VideoTranscriptScheduler.Outcome.PENDING,VideoTranscriptScheduler.run(context,SCOPE));
    }
    @Test public void transientFailureUsesFollowUpButDoesNotSpinInProcess(){
        int[] passes={0};install((app,scope)->{passes[0]++;throw new IOException("network_failed");});
        VideoTranscriptScheduler.schedule(context);executor.runNext();
        assertEquals(1,passes[0]);assertEquals(1,backend.followUps);assertEquals(0,executor.size());
    }
    @Test public void authenticationFailureDoesNotStartAnEndlessFollowUpLoop(){
        install((app,scope)->{throw new IOException("login_required");});
        VideoTranscriptScheduler.schedule(context);executor.runNext();assertEquals(0,backend.followUps);
        assertEquals(VideoTranscriptScheduler.Outcome.BLOCKED,VideoTranscriptScheduler.run(context,SCOPE));
    }
    @Test public void logoutCancelsQueuedPassAndDurableScopeWithoutForeignRequests(){
        int[] passes={0};install((app,scope)->{passes[0]++;return true;});
        VideoTranscriptScheduler.schedule(context);VideoTranscriptScheduler.cancel(context,SCOPE);
        CaptureAccountSession.preferences(context).edit().clear().commit();executor.runNext();
        assertEquals(0,passes[0]);assertEquals(1,backend.cancellations);assertEquals(0,backend.followUps);
    }
    @Test public void cancellationDuringRunningPassSuppressesLaterFollowUp(){
        install((app,scope)->{VideoTranscriptScheduler.cancel(app,scope);return true;});
        VideoTranscriptScheduler.schedule(context);executor.runNext();assertEquals(0,backend.followUps);
    }
    @Test public void accountChangeBeforeExecutionFencesOutOldQueue(){
        int[] passes={0};install((app,scope)->{assertEquals(OTHER,scope);passes[0]++;return false;});
        VideoTranscriptScheduler.schedule(context);
        CaptureAccountSession.preferences(context).edit().putString("scope",OTHER).commit();VideoTranscriptScheduler.schedule(context);
        executor.runNext();executor.runNext();assertEquals(1,passes[0]);
        assertEquals(VideoTranscriptScheduler.Outcome.BLOCKED,VideoTranscriptScheduler.run(context,SCOPE));
    }
    @Test public void durableBackendFailureDoesNotDisableProcessOwnedImmediatePass(){
        int[] passes={0};backend.fail=true;install((app,scope)->{passes[0]++;return false;});
        VideoTranscriptScheduler.schedule(context);executor.runNext();assertEquals(1,passes[0]);
    }
    @Test public void processExecutorFailureStillLeavesDurableRecoveryScheduled(){
        dispatcher=new VideoTranscriptScheduler.Dispatcher(task->{throw new RejectedExecutionException();},(app,scope)->false,backend);
        previous=VideoTranscriptScheduler.installForTests(dispatcher);VideoTranscriptScheduler.schedule(context);
        assertEquals(1,backend.schedules);
    }
    @Test public void guestNeverDispatchesNetworkOrCreatesWork(){
        int[] passes={0};install((app,scope)->{passes[0]++;return false;});
        CaptureAccountSession.preferences(context).edit().clear().commit();VideoTranscriptScheduler.schedule(context);
        VideoTranscriptScheduler.followUp(context,SCOPE);assertEquals(0,backend.schedules);assertEquals(0,backend.followUps);assertEquals(0,executor.size());
    }
    @Test public void savedNoteAndDestroyedActivityDoNotStopAutomaticDispatchOrOverwriteExcerpt()throws Exception {
        FakeTransport transport=new FakeTransport();transport.complete=true;
        install((app,scope)->VideoTranscriptTasks.reconcile(app,scope,transport));
        String recordId;
        try(var activity=Robolectric.buildActivity(Activity.class).setup()){
            VideoTranscriptTasks.enqueue(activity.get(),SCOPE,body(),URL,"");
            assertEquals(0,transport.posts);
            CaptureStore.CaptureRecord record=CaptureStore.save(activity.get(),null,null,null,null,"thought","保留我的想法","quick_note","已有摘录",URL);
            recordId=record.id;VideoTranscriptTasks.attachCapture(activity.get(),SCOPE,TASK,recordId);
            // The activity is gone before any HTTP executes. No inbox is opened.
        }
        executor.runNext();
        VideoTranscriptTasks.Task task=VideoTranscriptTasks.read(context,SCOPE,TASK);
        assertTrue(task.completed());assertEquals("后台自动完成的文字稿",task.text);assertEquals(recordId,task.captureId);
        assertEquals(1,transport.posts);assertTrue(task.notified);assertEquals(1,context.getSystemService(NotificationManager.class).getActiveNotifications().length);
        CaptureStore.CaptureRecord record=CaptureRecordEdits.latest(context,SCOPE,recordId);
        assertEquals("保留我的想法",record.comment);assertEquals("已有摘录",record.sourceText);
        assertEquals(0,backend.followUps);
    }
    private static JSONObject body()throws Exception {
        return new JSONObject().put("share_text",URL).put("rights_basis","owned").put("rights_note","我的原创录音")
                .put("agreement_version","test-v1").put("consent",true).put("client_request_id",TASK);
    }
    static final class HeldExecutor implements Executor {
        private final Queue<Runnable> queue=new ArrayDeque<>();
        @Override public void execute(Runnable runnable){queue.add(runnable);}
        void runNext(){assertFalse(queue.isEmpty());queue.remove().run();}
        int size(){return queue.size();}
    }
    static final class RecordingBackend implements VideoTranscriptScheduler.Backend {
        int schedules,followUps,cancellations;boolean fail;
        @Override public void schedule(Context context,String scope){schedules++;if(fail)throw new IllegalStateException("work_unavailable");}
        @Override public void followUp(Context context,String scope){followUps++;}
        @Override public void cancel(Context context,String scope){cancellations++;}
    }
    static final class FakeTransport implements VideoTranscriptTasks.Transport {
        int posts;boolean complete;String captureId="";JSONObject remote;
        @Override public JSONObject request(Context app,String scope,String method,String path,JSONObject body)throws Exception {
            if("POST".equals(method)&&"/jobs".equals(path)){
                posts++;remote=new JSONObject().put("id",REMOTE).put("client_request_id",TASK).put("status","processing").put("stage","processing")
                        .put("progress",5).put("source",new JSONObject().put("platform","douyin").put("title","自有测试录音"))
                        .put("source_url",URL).put("share_text",URL).put("capture_id",captureId).put("error",JSONObject.NULL);
                return new JSONObject().put("job",new JSONObject(remote.toString()));
            }
            if(path.endsWith("/capture")){
                captureId=body.getString("capture_id");remote.put("capture_id",captureId);return new JSONObject().put("job",remote);
            }
            if(path.startsWith("/jobs?")){
                JSONArray jobs=new JSONArray();if(remote!=null){JSONObject summary=new JSONObject(remote.toString());summary.remove("text");summary.remove("segments");jobs.put(summary);}
                return new JSONObject().put("jobs",jobs).put("has_more",false);
            }
            if(path.equals("/jobs/"+REMOTE)){
                if(complete)remote.put("status","completed").put("stage","completed").put("progress",100).put("text","后台自动完成的文字稿");
                return new JSONObject().put("job",new JSONObject(remote.toString()));
            }
            throw new AssertionError("unexpected_test_request");
        }
    }
    /** Reuse in task/editor unit tests that explicitly own their fake network pass. */
    @Implements(value=VideoTranscriptScheduler.class,isInAndroidSdk=false)
    public static class NoDispatch {
        @Implementation protected static void schedule(Context context){}
        @Implementation protected static void followUp(Context context,String scope){}
        @Implementation protected static void cancel(Context context,String scope){}
    }
}
