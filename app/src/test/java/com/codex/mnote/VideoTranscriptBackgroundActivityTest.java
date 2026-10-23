package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.Looper;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,shadows={VideoTranscriptSchedulerTest.NoDispatch.class,VideoTranscriptBackgroundActivityTest.Transport.class,QuickNoteActivityTest.ClipboardShadow.class,QuickNoteActivityTest.ServiceShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoTranscriptBackgroundActivityTest {
    private Context context;
    private static final String SCOPE="a".repeat(64),URL="https://v.douyin.com/example/";
    @Before public void reset()throws Exception{new QuickNoteActivityTest().reset();context=RuntimeEnvironment.getApplication();
        CaptureAccountSession.preferences(context).edit().clear().putString("scope",SCOPE).putString("account_id","a".repeat(32)).commit();
        VideoTranscriptTasksTest.deleteTree(new java.io.File(context.getFilesDir(),"video-transcript-tasks"));Transport.configs=Transport.posts=0;}
    @After public void clear(){CaptureAccountSession.preferences(context).edit().clear().commit();}
    private VideoTranscriptState state()throws Exception{VideoTranscriptState s=VideoTranscriptState.create(context,CaptureAccountSession.scope(context));s.backgroundFlow=true;s.importSource=true;s.persist();return s;}
    private void drain(VideoTranscriptActivity activity)throws Exception{VideoTranscriptActivity.Session session=ReflectionHelpers.getField(activity,"session");for(int i=0;i<3;i++){session.executor.submit(()->{}).get(5,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();}}
    @Test public void linkOnlyWorksOfflineWithoutAccountConsentOrNetwork()throws Exception{
        CaptureAccountSession.preferences(context).edit().clear().commit();VideoTranscriptState state=state();
        try(var controller=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup()){
            VideoTranscriptActivity activity=controller.get();assertFalse(activity.<CheckBox>findViewById(R.id.video_transcript_audio).isChecked());
            activity.<EditText>findViewById(R.id.video_transcript_link).setText("视频 "+URL);activity.findViewById(R.id.video_transcript_parse).performClick();
            assertTrue(activity.isFinishing());assertEquals(Activity.RESULT_OK,shadowOf(activity).getResultCode());
            VideoTranscriptState saved=VideoTranscriptState.read(context,state.file.getAbsolutePath());assertTrue(saved.linkOnly);assertEquals(URL,saved.sourceUrl);
            assertEquals(0,Transport.configs);assertEquals(0,Transport.posts);assertEquals(0,QuickNoteActivityTest.ClipboardShadow.reads);
        }
    }
    @Test public void audioQueuesLocallyWithoutWaitingForPostAndRequiresFreshConsent()throws Exception{
        VideoTranscriptState state=state();
        try(var controller=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup()){
            VideoTranscriptActivity activity=controller.get();activity.<CheckBox>findViewById(R.id.video_transcript_audio).setChecked(true);drain(activity);
            activity.<EditText>findViewById(R.id.video_transcript_link).setText(URL);
            activity.<Spinner>findViewById(R.id.video_transcript_basis).setSelection(1);activity.<EditText>findViewById(R.id.video_transcript_note).setText("原创测试录音");
            activity.findViewById(R.id.video_transcript_parse).performClick();assertFalse(activity.isFinishing());assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());
            activity.<CheckBox>findViewById(R.id.video_transcript_consent).setChecked(true);activity.findViewById(R.id.video_transcript_parse).performClick();
            assertTrue(activity.isFinishing());assertEquals(0,Transport.posts);
            VideoTranscriptState saved=VideoTranscriptState.read(context,state.file.getAbsolutePath());
            VideoTranscriptTasks.Task task=VideoTranscriptTasks.read(context,SCOPE,saved.backgroundTaskId);
            assertEquals(state.requestId,task.id);assertEquals("local_queued",task.status);assertEquals("",task.remoteId);
            assertEquals(1,shadowOf(activity).getResultIntent().getExtras().size());
        }
    }
    @Test public void leavingCheckedAudioWithoutSubmittingExplainsThatNoTaskExists()throws Exception{
        VideoTranscriptState state=state();
        try(var controller=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup()){
            VideoTranscriptActivity activity=controller.get();activity.<CheckBox>findViewById(R.id.video_transcript_audio).setChecked(true);drain(activity);
            activity.<EditText>findViewById(R.id.video_transcript_link).setText(URL);
            activity.onBackPressed();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());assertFalse(activity.isFinishing());assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());
            assertEquals("还没有加入后台解析",shadowOf(dialog).getTitle().toString());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();drain(activity);assertTrue(activity.isFinishing());
            assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());assertEquals(0,Transport.posts);
        }
    }
    @Test public void exitConfirmationCanQueueButNeverBypassesConsent()throws Exception{
        VideoTranscriptState state=state();
        try(var controller=Robolectric.buildActivity(VideoTranscriptActivity.class,VideoTranscriptActivity.open(context,state.file.getAbsolutePath())).setup()){
            VideoTranscriptActivity activity=controller.get();activity.<CheckBox>findViewById(R.id.video_transcript_audio).setChecked(true);drain(activity);
            activity.<EditText>findViewById(R.id.video_transcript_link).setText(URL);
            activity.<Spinner>findViewById(R.id.video_transcript_basis).setSelection(1);activity.<EditText>findViewById(R.id.video_transcript_note).setText("原创测试录音");
            activity.onBackPressed();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            drain(activity);
            assertFalse(activity.isFinishing());assertTrue(VideoTranscriptTasks.list(context,SCOPE).isEmpty());
            activity.<CheckBox>findViewById(R.id.video_transcript_consent).setChecked(true);
            activity.onBackPressed();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            drain(activity);
            assertTrue(activity.isFinishing());assertEquals(1,VideoTranscriptTasks.list(context,SCOPE).size());
            assertEquals(0,Transport.posts);
        }
    }
    @Test public void savingCaptureDoesNotWaitForTranscriptOrOverwriteModules()throws Exception{
        try(var controller=UnifiedCaptureActivityTest.open(null,CaptureSourceContext.EMPTY)){
            UnifiedCaptureActivity activity=controller.get();activity.<EditText>findViewById(R.id.unified_thought).setText("先保存我的想法");
            activity.<CheckBox>findViewById(R.id.unified_excerpt_toggle).setChecked(true);activity.<EditText>findViewById(R.id.unified_excerpt).setText("已有摘录");
            activity.findViewById(R.id.unified_video_transcript).performClick();UnifiedCaptureDraft draft=ReflectionHelpers.getField(activity,"draft");
            VideoTranscriptState state=VideoTranscriptState.read(activity,draft.transcriptState);
            JSONObject body=new JSONObject().put("client_request_id",state.requestId).put("share_text",URL).put("rights_basis","owned").put("rights_note","原创测试录音").put("consent",true).put("agreement_version","test-v1");
            VideoTranscriptTasks.Task task=VideoTranscriptTasks.enqueue(activity,SCOPE,body,URL,"");state.backgroundTaskId=task.id;state.sourceUrl=URL;state.persist();
            activity.onActivityResult(UnifiedCaptureActivity.REQUEST_VIDEO_TRANSCRIPT,Activity.RESULT_OK,new Intent().putExtra(VideoTranscriptActivity.STATE,state.file.getAbsolutePath()));
            assertEquals("已有摘录",activity.<EditText>findViewById(R.id.unified_excerpt).getText().toString());
            activity.findViewById(R.id.unified_save).performClick();UnifiedCaptureActivityTest.drain(activity);
            assertTrue(activity.isFinishing());CaptureStore.CaptureRecord record=CaptureStore.list(activity,10).get(0);
            assertEquals("先保存我的想法",record.comment);assertEquals("已有摘录",record.sourceText);assertEquals(URL,record.sourceUrl);
            assertEquals(record.id,VideoTranscriptTasks.read(activity,SCOPE,task.id).captureId);assertEquals(0,Transport.posts);
        }
    }
    @Test public void explicitImportPreservesThoughtOriginalTagsAndAsrFidelity()throws Exception{
        CaptureAccountSession.preferences(context).edit().remove("account_id").commit();
        CaptureStore.CaptureRecord record=CaptureStore.save(context,null,null,null,null,"thought","想法","quick_note","旧摘录","");
        record=CaptureRecordEdits.save(context,SCOPE,record.id,CaptureRecordEdits.fingerprint(record),record.comment,record.sourceText,"原文",new JSONArray().put("学习"));
        CaptureStore.CaptureRecord imported=CaptureRecordEdits.importTranscript(context,SCOPE,record.id,CaptureRecordEdits.fingerprint(record),"机器识别结果","11111111-1111-4111-8111-111111111111");
        assertEquals("想法",imported.comment);assertEquals("原文",CaptureRecordEdits.original(imported));assertEquals("学习",imported.tags.getString(0));
        assertEquals("video_transcript",imported.sourceType);assertEquals("machine_transcribed",imported.fidelityLevel);
        assertFalse(imported.captureContext.getJSONObject("transcription").getBoolean("verified"));
        String baseline=CaptureRecordEdits.fingerprint(imported);
        CaptureRecordEdits.save(context,SCOPE,record.id,baseline,"后来修改的想法",imported.sourceText,"原文",imported.tags);
        try{CaptureRecordEdits.importTranscript(context,SCOPE,record.id,baseline,"迟到的结果","11111111-1111-4111-8111-111111111111");fail();}
        catch(java.io.IOException expected){assertEquals("record_changed",expected.getMessage());}
        assertEquals("后来修改的想法",CaptureRecordEdits.latest(context,SCOPE,record.id).comment);
    }
    @Implements(VideoTranscriptClient.class) public static class Transport{
        static int configs,posts;
        @Implementation protected static JSONObject request(Context c,String scope,String method,String path,JSONObject body)throws Exception{
            if(path.equals("/config")){configs++;return new JSONObject().put("ready",true).put("agreement_version","test-v1");}
            posts++;throw new AssertionError("Saving/queuing must never wait on a server request");
        }
    }
}
