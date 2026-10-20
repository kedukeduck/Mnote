package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",
        shadows={VideoTranscriptActivityTest.Transport.class,QuickNoteActivityTest.ClipboardShadow.class,QuickNoteActivityTest.ServiceShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoTranscriptActivityTest {
    Context context;
    static final String ID="12345678-1234-4234-8234-123456789abc";
    @Before public void setup()throws Exception {
        new QuickNoteActivityTest().reset();context=RuntimeEnvironment.getApplication();
        CaptureAccountSession.preferences(context).edit().clear().putString("scope","a".repeat(64)).putString("account_id","a".repeat(32)).commit();
        Transport.posts=Transport.gets=Transport.deletes=0;Transport.holdQueued=false;Transport.tooLong=false;Transport.failCreate=false;Transport.body=null;
        Transport.entered=Transport.release=null;
        Transport.configEntered=Transport.configRelease=null;Transport.failConfig=false;Transport.configReady=true;Transport.ignorePostInterrupt=false;
    }
    @After public void clear(){CaptureAccountSession.preferences(context).edit().clear().commit();}
    private ActivityController<VideoTranscriptActivity> open(VideoTranscriptState state)throws Exception {
        ActivityController<VideoTranscriptActivity> c=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup();
        drain(c.get());return c;
    }
    private static void drain(VideoTranscriptActivity activity)throws Exception {
        VideoTranscriptActivity.Session session=ReflectionHelpers.getField(activity,"session");
        for(int i=0;i<3;i++){session.executor.submit(()->{}).get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}
    }
    private static void form(VideoTranscriptActivity a){
        a.<EditText>findViewById(R.id.video_transcript_link).setText("看看我的视频 https://v.douyin.com/test/");
        a.<Spinner>findViewById(R.id.video_transcript_basis).setSelection(1);
        a.<EditText>findViewById(R.id.video_transcript_note).setText("我的原创视频");
    }
    private static void start(VideoTranscriptActivity a)throws Exception {form(a);a.<CheckBox>findViewById(R.id.video_transcript_consent).setChecked(true);a.findViewById(R.id.video_transcript_parse).performClick();drain(a);}
    @Test public void optInIsRequiredAndOpeningDoesNotReadClipboardOrPage()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            VideoTranscriptActivity a=c.get();assertFalse(a.<CheckBox>findViewById(R.id.video_transcript_consent).isChecked());
            assertEquals(0,a.<Spinner>findViewById(R.id.video_transcript_basis).getSelectedItemPosition());form(a);
            a.findViewById(R.id.video_transcript_parse).performClick();drain(a);assertEquals(0,Transport.posts);
            assertEquals(0,QuickNoteActivityTest.ClipboardShadow.reads);assertEquals(0,QuickNoteActivityTest.ServiceShadow.reads);
        }
    }
    @Test public void configurationTimeoutAllowsRetryAndRejectsLateSuccess()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        Transport.configEntered=new CountDownLatch(1);CountDownLatch release=Transport.configRelease=new CountDownLatch(1);
        ActivityController<VideoTranscriptActivity> c=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup();
        try{
            assertTrue(Transport.configEntered.await(3,TimeUnit.SECONDS));
            VideoTranscriptActivity a=c.get();VideoTranscriptActivity.Session session=ReflectionHelpers.getField(a,"session");
            assertTrue(session.busy);assertFalse(a.findViewById(R.id.video_transcript_refresh).isEnabled());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoTranscriptActivity.Session.REQUEST_TIMEOUT_MILLIS));
            assertFalse(session.busy);assertFalse(session.ready);
            assertTrue(a.<TextView>findViewById(R.id.video_transcript_status).getText().toString().contains("连接解析服务超时"));
            assertTrue(a.findViewById(R.id.video_transcript_refresh).isEnabled());
            // The stalled request deliberately ignores interruption and returns after the UI timed out.
            Transport.configRelease.countDown();drain(a);
            assertFalse(session.ready);assertTrue(a.<TextView>findViewById(R.id.video_transcript_status).getText().toString().contains("超时"));
            Transport.configEntered=Transport.configRelease=null;
            a.findViewById(R.id.video_transcript_refresh).performClick();drain(a);
            assertTrue(session.ready);assertTrue(a.<TextView>findViewById(R.id.video_transcript_status).getText().toString().startsWith("已连接"));
            assertEquals("",VideoTranscriptState.read(context,state.file.getAbsolutePath()).problem);
        }finally{release.countDown();c.close();}
    }
    @Test public void oldConfigurationCannotOverwriteNewRetryAcrossRotation()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        Transport.configEntered=new CountDownLatch(1);CountDownLatch release=Transport.configRelease=new CountDownLatch(1);
        CountDownLatch retryEntered=new CountDownLatch(1),retryRelease=new CountDownLatch(1);
        ActivityController<VideoTranscriptActivity> c=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup();
        try{
            assertTrue(Transport.configEntered.await(3,TimeUnit.SECONDS));
            VideoTranscriptActivity.Session session=ReflectionHelpers.getField(c.get(),"session");
            // The old request already captured ready=true. Configure and independently hold its successor before rotating.
            Transport.configEntered=retryEntered;Transport.configRelease=retryRelease;Transport.configReady=false;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoTranscriptActivity.Session.REQUEST_TIMEOUT_MILLIS));
            c.recreate();assertSame(session,ReflectionHelpers.getField(c.get(),"session"));
            release.countDown();assertTrue(retryEntered.await(3,TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse("Late old success must not mark the reconnect ready",session.ready);
            assertTrue("Late old completion must not clear the successor's busy state",session.busy);
            retryRelease.countDown();drain(c.get());
            assertFalse(session.ready);assertFalse(session.busy);assertEquals(0,Transport.posts);
            assertTrue(c.get().<TextView>findViewById(R.id.video_transcript_status).getText().toString().contains("尚未启用"));
            Transport.configEntered=Transport.configRelease=null;
            Transport.configReady=true;c.get().findViewById(R.id.video_transcript_refresh).performClick();drain(c.get());
            assertTrue(session.ready);assertTrue(c.get().<TextView>findViewById(R.id.video_transcript_status).getText().toString().startsWith("已连接"));
        }finally{release.countDown();retryRelease.countDown();c.close();}
    }
    @Test public void reconnectAfterLoginFailureClearsPersistedProblem()throws Exception {
        Transport.failConfig=true;VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            VideoTranscriptActivity a=c.get();
            assertTrue(a.<TextView>findViewById(R.id.video_transcript_status).getText().toString().contains("重新登录"));
            Transport.failConfig=false;a.findViewById(R.id.video_transcript_refresh).performClick();drain(a);
            assertTrue(a.<TextView>findViewById(R.id.video_transcript_status).getText().toString().startsWith("已连接"));
            assertEquals("",VideoTranscriptState.read(context,state.file.getAbsolutePath()).problem);
        }
    }
    @Test public void postTimeoutRecoveryKeepsIdempotencyKeyAndDoesNotDeleteLateJob()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            VideoTranscriptActivity a=c.get();Transport.entered=new CountDownLatch(1);Transport.release=new CountDownLatch(1);
            Transport.ignorePostInterrupt=true;
            try{
                form(a);a.<CheckBox>findViewById(R.id.video_transcript_consent).setChecked(true);
                a.findViewById(R.id.video_transcript_parse).performClick();assertTrue(Transport.entered.await(3,TimeUnit.SECONDS));
                String key=Transport.body.getString("client_request_id");
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoTranscriptActivity.Session.REQUEST_TIMEOUT_MILLIS));
                VideoTranscriptActivity.Session session=ReflectionHelpers.getField(a,"session");
                assertFalse(session.busy);assertTrue(session.state.creating);assertEquals(1,Transport.posts);
                assertEquals("",session.state.id());assertEquals(key,session.state.requestId);
                a.findViewById(R.id.video_transcript_refresh).performClick();
                Transport.release.countDown();drain(a);
                assertEquals(2,Transport.posts);assertEquals(key,Transport.body.getString("client_request_id"));
                assertEquals(ID,session.state.id());assertEquals(0,Transport.deletes);
            }finally{Transport.release.countDown();Transport.ignorePostInterrupt=false;}
        }
    }
    @Test public void resultRequiresExplicitImportAndKeepsTranscriptOutsideIntent()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            VideoTranscriptActivity a=c.get();start(a);assertEquals(1,Transport.posts);
            assertFalse(a.findViewById(R.id.video_transcript_import).isEnabled());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));drain(a);
            assertEquals("识别出的文字",a.<TextView>findViewById(R.id.video_transcript_preview).getText().toString());
            assertFalse(a.isFinishing());assertEquals("2026-test-v1",Transport.body.getString("agreement_version"));
            assertEquals(state.requestId,Transport.body.getString("client_request_id"));
            a.findViewById(R.id.video_transcript_import).performClick();assertTrue(a.isFinishing());
            assertEquals(Activity.RESULT_OK,shadowOf(a).getResultCode());
            assertEquals(1,shadowOf(a).getResultIntent().getExtras().size());
            VideoTranscriptState result=VideoTranscriptState.read(context,state.file.getAbsolutePath());assertTrue(result.imported);assertEquals("识别出的文字",result.text);
            drain(a);assertEquals(1,Transport.deletes);
        }
    }
    @Test public void rotationResumesSameJobWithoutSecondPost()throws Exception {
        Transport.holdQueued=true;VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            start(c.get());assertEquals(1,Transport.posts);c.recreate();drain(c.get());assertEquals(1,Transport.posts);
            assertEquals(ID,VideoTranscriptState.read(context,state.file.getAbsolutePath()).id());
        }
    }
    @Test public void uncertainPostUsesSameRequestIdForManualRecovery()throws Exception {
        Transport.failCreate=true;VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            start(c.get());assertEquals(1,Transport.posts);String key=Transport.body.getString("client_request_id");
            c.recreate();drain(c.get());assertEquals(1,Transport.posts);Transport.failCreate=false;
            c.get().findViewById(R.id.video_transcript_refresh).performClick();drain(c.get());
            assertEquals(2,Transport.posts);assertEquals(key,Transport.body.getString("client_request_id"));
        }
    }
    @Test public void oversizedTranscriptIsNotTruncatedOrImported()throws Exception {
        Transport.tooLong=true;VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            start(c.get());shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));drain(c.get());
            assertFalse(c.get().findViewById(R.id.video_transcript_import).isEnabled());
            assertTrue(c.get().<TextView>findViewById(R.id.video_transcript_status).getText().toString().contains("10 万字"));
            assertEquals("",VideoTranscriptState.read(context,state.file.getAbsolutePath()).text);
        }
    }
    @Test public void cancelledJobIsDeletedAndPauseDoesNotRecreateState()throws Exception {
        Transport.holdQueued=true;VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        try(var c=open(state)){
            start(c.get());c.get().findViewById(R.id.video_transcript_cancel).performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain(c.get());c.pause();
            assertFalse(state.file.exists());assertEquals(1,Transport.deletes);
        }
    }
    @Test public void abandonedDraftDoesNotResurrectWhenCreateCompletesLate()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        Transport.entered=new CountDownLatch(1);Transport.release=new CountDownLatch(1);
        try(var c=open(state)){
            form(c.get());c.get().<CheckBox>findViewById(R.id.video_transcript_consent).setChecked(true);
            c.get().findViewById(R.id.video_transcript_parse).performClick();assertTrue(Transport.entered.await(3,TimeUnit.SECONDS));
            state.discard();Transport.release.countDown();drain(c.get());assertFalse(state.file.exists());assertEquals(1,Transport.deletes);
        }finally{Transport.release.countDown();}
    }
    @Test public void privateStateRejectsAccountSwitchAndPersistsLargeResult()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));state.text="文".repeat(100000);state.persist();
        assertEquals(100000,VideoTranscriptState.read(context,state.file.getAbsolutePath()).text.length());
        CaptureAccountSession.preferences(context).edit().putString("scope","b".repeat(64)).commit();
        assertThrows(IOException.class,()->VideoTranscriptState.read(context,state.file.getAbsolutePath()));
    }
    @Test public void failedTaskCanResetWithoutKeepingConsentOrRequestId()throws Exception {
        VideoTranscriptState state=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));
        state.job=Transport.job(false).put("status","failed").put("stage","failed").put("error",new JSONObject().put("code","image_post_unsupported"));
        state.consent=true;state.share="https://v.douyin.com/old/";state.persist();String key=state.requestId;
        try(var c=open(state)){
            assertTrue(c.get().<TextView>findViewById(R.id.video_transcript_status).getText().toString().contains("图文"));
            c.get().findViewById(R.id.video_transcript_reset).performClick();drain(c.get());
            VideoTranscriptState reset=VideoTranscriptState.read(context,state.file.getAbsolutePath());
            assertFalse(reset.consent);assertNotEquals(key,reset.requestId);assertEquals("",reset.share);assertEquals("",reset.id());assertEquals(1,Transport.deletes);
        }
    }
    @Test public void independentlyEditedExcerptIsNotSilentlyOverwritten()throws Exception {
        try(var c=UnifiedCaptureActivityTest.open(null,CaptureSourceContext.EMPTY)){
            UnifiedCaptureActivity a=c.get();a.<CheckBox>findViewById(R.id.unified_excerpt_toggle).setChecked(true);
            a.<EditText>findViewById(R.id.unified_excerpt).setText("原摘录");a.findViewById(R.id.unified_video_transcript).performClick();
            UnifiedCaptureDraft draft=ReflectionHelpers.getField(a,"draft");VideoTranscriptState state=VideoTranscriptState.read(a,draft.transcriptState);
            state.text="新视频文字稿";state.imported=true;state.persist();a.<EditText>findViewById(R.id.unified_excerpt).setText("用户后来写下的摘录");
            a.onActivityResult(UnifiedCaptureActivity.REQUEST_VIDEO_TRANSCRIPT,Activity.RESULT_OK,new Intent().putExtra(VideoTranscriptActivity.STATE,state.file.getAbsolutePath()));
            assertEquals("用户后来写下的摘录",a.<EditText>findViewById(R.id.unified_excerpt).getText().toString());
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("用户后来写下的摘录",a.<EditText>findViewById(R.id.unified_excerpt).getText().toString());assertTrue(state.file.exists());
            assertFalse(VideoTranscriptState.read(a,state.file.getAbsolutePath()).imported);
        }
    }
    @Test public void unifiedImportPreservesIndependentModulesAndMarksMachineText()throws Exception {
        try(var c=UnifiedCaptureActivityTest.open(null,CaptureSourceContext.EMPTY)){
            UnifiedCaptureActivity a=c.get();a.<EditText>findViewById(R.id.unified_thought).setText("我的想法");
            a.<CheckBox>findViewById(R.id.unified_excerpt_toggle).setChecked(true);
            a.<EditText>findViewById(R.id.unified_excerpt).setText("之前摘录");
            a.<EditText>findViewById(R.id.unified_url).setText("https://example.org/old");
            a.findViewById(R.id.unified_video_transcript).performClick();
            UnifiedCaptureDraft draft=ReflectionHelpers.getField(a,"draft");VideoTranscriptState state=VideoTranscriptState.read(a,draft.transcriptState);
            state.text="视频文字稿";state.sourceUrl="https://www.douyin.com/video/123";state.imported=true;state.importSource=true;state.persist();
            a.onActivityResult(UnifiedCaptureActivity.REQUEST_VIDEO_TRANSCRIPT,Activity.RESULT_OK,new Intent().putExtra(VideoTranscriptActivity.STATE,state.file.getAbsolutePath()));
            assertEquals("视频文字稿",a.<EditText>findViewById(R.id.unified_excerpt).getText().toString());
            assertEquals("我的想法",a.<EditText>findViewById(R.id.unified_thought).getText().toString());
            assertFalse(a.<CheckBox>findViewById(R.id.unified_source_toggle).isChecked());
            assertEquals("https://www.douyin.com/video/123",a.<EditText>findViewById(R.id.unified_url).getText().toString());
            // Avoid enqueueing real account work: preserve the private scope, remove only the account-presence flag.
            CaptureAccountSession.preferences(context).edit().remove("account_id").commit();
            a.findViewById(R.id.unified_save).performClick();UnifiedCaptureActivityTest.drain(a);
            CaptureStore.CaptureRecord record=CaptureStore.list(a,10).get(0);assertEquals("video_transcript",record.sourceType);
            assertEquals("machine_transcribed",record.fidelityLevel);assertEquals("",record.sourceUrl);
            JSONObject metadata=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"metadata",ReflectionHelpers.ClassParameter.from(Context.class,a),ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,record));
            assertEquals("video_transcript",metadata.getJSONObject("source").getString("text_origin"));assertTrue(metadata.getJSONObject("evidence").isNull("exact_text"));
            assertEquals("automatic_speech_recognition",metadata.getJSONObject("source").getJSONObject("transcription").getString("method"));
        }
    }
    @Test public void correctingTranscriptAndSyncRoundTripNeverTurnsAsrIntoExactEvidence()throws Exception {
        CaptureAccountSession.preferences(context).edit().remove("account_id").commit();String scope=CaptureAccountSession.scope(context);
        CaptureStore.CaptureRecord record=CaptureStore.save(context,null,null,null,null,"thought","想法","video_transcript","机器识别词","");
        CaptureStore.CaptureRecord edited=CaptureRecordEdits.save(context,scope,record.id,CaptureRecordEdits.fingerprint(record),"想法","校对之后的文字","");
        JSONObject metadata=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"metadata",ReflectionHelpers.ClassParameter.from(Context.class,context),ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,edited));
        assertTrue(metadata.getJSONObject("evidence").isNull("exact_text"));assertEquals("user_edited",metadata.getJSONObject("source").getString("text_origin"));
        assertTrue(metadata.getJSONObject("source").getJSONObject("transcription").getBoolean("user_edited"));
        assertFalse(metadata.getJSONObject("source").getJSONObject("transcription").getBoolean("verified"));
        assertTrue(AiChatStore.snapshot(context,edited.id,Collections.singleton("excerpt")).getString("context_note").contains("自动语音转写"));
        metadata.put("revision",1);String vault="b".repeat(64);
        byte[] page=CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert",edited.id,metadata));
        CaptureRemoteCache.pull(context,vault,(path,limit)->page,()->true);
        CaptureStore.CaptureRecord downloaded=CaptureRemoteCache.merged(context,vault,Collections.emptyList()).get(0);
        assertEquals("校对之后的文字",downloaded.sourceText);assertEquals("video_transcript",downloaded.sourceType);
        assertEquals("machine_transcribed",downloaded.fidelityLevel);
        JSONObject canonical=CaptureStore.readRecordObject(new java.io.File(downloaded.metadataFile.getParentFile(),"remote.json"));
        assertEquals("automatic_speech_recognition",canonical.getJSONObject("source").getJSONObject("transcription").getString("method"));
        assertTrue(canonical.getJSONObject("evidence").isNull("exact_text"));
    }
    @Implements(VideoTranscriptClient.class) public static class Transport {
        static int posts,gets,deletes;static boolean holdQueued,tooLong,failCreate;static JSONObject body;
        static CountDownLatch entered,release;
        static volatile CountDownLatch configEntered,configRelease;
        static volatile boolean failConfig,configReady=true,ignorePostInterrupt;
        @Implementation protected static JSONObject request(Context context,String scope,String method,String path,JSONObject request)throws Exception {
            CaptureAccountSession.requireScope(context,scope);
            if(path.equals("/config")){
                boolean ready=configReady,failed=failConfig;CountDownLatch started=configEntered,finish=configRelease;
                if(started!=null)started.countDown();if(finish!=null)awaitIgnoringInterrupt(finish);
                if(failed)throw new IOException("login_required");
                return new JSONObject().put("ready",ready).put("agreement_version","2026-test-v1");
            }
            if(method.equals("DELETE")){deletes++;return new JSONObject().put("deleted",true).put("id",ID);}
            if(method.equals("POST")){posts++;body=new JSONObject(request.toString());if(entered!=null){entered.countDown();if(ignorePostInterrupt)awaitIgnoringInterrupt(release);else if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");}if(failCreate)throw new IOException("network_failed");
                return new JSONObject().put("job",job(false));}
            gets++;return new JSONObject().put("job",job(!holdQueued));
        }
        static JSONObject job(boolean done)throws Exception{return new JSONObject().put("id",ID).put("status",done?"completed":"queued")
                .put("stage",done?"completed":"queued").put("progress",done?100:0).put("text",done?(tooLong?"文".repeat(100001):"识别出的文字"):"");}
        static void awaitIgnoringInterrupt(CountDownLatch latch)throws Exception {
            // Do not use System.nanoTime here: Robolectric advances it with idleFor, while this latch waits in real time.
            for(int interruptions=0;interruptions<3;interruptions++)try{
                if(latch.await(5,TimeUnit.SECONDS))return;throw new IOException("timeout");
            }catch(InterruptedException ignored){/* Exercise a transport that returns after cancellation. */}
            throw new IOException("timeout");
        }
    }
}
