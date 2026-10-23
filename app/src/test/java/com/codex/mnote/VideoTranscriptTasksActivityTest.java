package com.codex.mnote;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",shadows={VideoTranscriptSchedulerTest.NoDispatch.class,VideoTranscriptTasksActivityTest.Transport.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoTranscriptTasksActivityTest {
    Context context;
    static final String SECOND="98765432-4321-4321-8321-cba987654321";
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();CaptureAccountSession.preferences(context).edit().clear()
                .putString("scope",VideoTranscriptTasksTest.SCOPE).putString("account_id","a".repeat(32)).commit();
        shadowOf((android.app.Application)context).grantPermissions(context.getPackageName()+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        VideoTranscriptTasksTest.deleteTree(new File(context.getFilesDir(),"video-transcript-tasks"));
        context.getSharedPreferences("video-task-history-scan",Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void clear(){CaptureAccountSession.preferences(context).edit().clear().commit();}
    void seed(String id,String title,String status,String capture)throws Exception {
        VideoTranscriptTasks.Task task=new VideoTranscriptTasks.Task();task.id=task.requestId=id;task.remoteId=id;task.scope=VideoTranscriptTasksTest.SCOPE;
        task.status=status;task.stage=status;task.title=title;task.captureId=capture;task.sourceUrl=VideoTranscriptTasksTest.URL;task.share=task.sourceUrl;
        task.createdAt=task.updatedAt=System.currentTimeMillis();task.resultFetched="completed".equals(status);task.notified=true;
        task.text=task.resultFetched?"这是一份完整的测试文字稿，不会自动替换已有摘录。":"";
        task.submission=VideoTranscriptTasksTest.body(id);task.job=VideoTranscriptTasksTest.job(id,id,status,null);
        File dir=new File(context.getFilesDir(),"video-transcript-tasks/"+task.scope);assertTrue(dir.isDirectory()||dir.mkdirs());
        CaptureStore.writeJson(task.json(),new File(dir,id+".json"),VideoTranscriptTasks.MAX_BYTES);
    }
    ActivityController<VideoTranscriptTasksActivity> open(String capture,String task)throws Exception {
        Intent intent=VideoTranscriptTasksActivity.open(context,VideoTranscriptTasksTest.SCOPE,capture);
        if(task!=null)intent.putExtra("task_id",task);
        ActivityController<VideoTranscriptTasksActivity> controller=Robolectric.buildActivity(VideoTranscriptTasksActivity.class,intent).setup();
        drain(controller.get());return controller;
    }
    static void drain(VideoTranscriptTasksActivity activity)throws Exception {
        ExecutorService executor=ReflectionHelpers.getField(activity,"executor");
        for(int i=0;i<4;i++){
            java.util.concurrent.CyclicBarrier barrier=new java.util.concurrent.CyclicBarrier(2);
            Runnable fence=()->{try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(error);}};
            java.util.concurrent.Future<?> first=executor.submit(fence),second=executor.submit(fence);
            first.get(6,TimeUnit.SECONDS);second.get(6,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
        }
    }
    static TextView find(View view,String exact){
        if(view instanceof TextView&&exact.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=find(((ViewGroup)view).getChildAt(i),exact);if(found!=null)return found;}
        return null;
    }
    @Test public void metadataListHasDistinctStatusesAndFiltersWithoutShowingWholeTranscript()throws Exception {
        seed(VideoTranscriptTasksTest.TASK,"完成的自有视频","completed","");seed(SECOND,"未完成的自有视频","failed","");
        try(var controller=open("",null)){
            VideoTranscriptTasksActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            assertNotNull(find(root,"完成的自有视频"));assertNotNull(find(root,"未完成的自有视频"));
            assertNull(find(root,"这是一份完整的测试文字稿，不会自动替换已有摘录。"));
            find(root,"已完成").performClick();assertEquals(2,ReflectionHelpers.<Integer>getField(activity,"filter").intValue());drain(activity);
            assertNotNull(find(root,"完成的自有视频"));assertNull(find(root,"未完成的自有视频"));
            find(root,"未完成").performClick();drain(activity);
            assertNull(find(root,"完成的自有视频"));assertNotNull(find(root,"未完成的自有视频"));
        }
    }
    @Test public void queueLabelsDistinguishLocalPersistenceFromServerAcceptance()throws Exception {
        VideoTranscriptTasks.Task task=new VideoTranscriptTasks.Task();task.status="local_queued";task.stage="queued";
        assertEquals("待提交 · 已保存在本机",ReflectionHelpers.callStaticMethod(VideoTranscriptTasksActivity.class,"label",ReflectionHelpers.ClassParameter.from(VideoTranscriptTasks.Task.class,task)));
        task.status="submitting";
        assertEquals("正在提交到服务器",ReflectionHelpers.callStaticMethod(VideoTranscriptTasksActivity.class,"label",ReflectionHelpers.ClassParameter.from(VideoTranscriptTasks.Task.class,task)));
        task.status="queued";task.remoteId=VideoTranscriptTasksTest.REMOTE;
        assertEquals("已提交 · 等待服务器解析",ReflectionHelpers.callStaticMethod(VideoTranscriptTasksActivity.class,"label",ReflectionHelpers.ClassParameter.from(VideoTranscriptTasks.Task.class,task)));
    }
    @Test public void recordSpecificInboxDoesNotShowUnrelatedRecordTasks()throws Exception {
        seed(VideoTranscriptTasksTest.TASK,"本条记录的视频","completed","capture-first");seed(SECOND,"其他记录的视频","failed","capture-second");
        try(var controller=open("capture-first",null)){
            View root=controller.get().getWindow().getDecorView();assertNotNull(find(root,"本条记录的视频"));assertNull(find(root,"其他记录的视频"));
        }
    }
    @Test public void detailDisplaysFullResultAndCopyOnlyDoesNotMarkImported()throws Exception {
        seed(VideoTranscriptTasksTest.TASK,"详情视频","completed","");
        try(var controller=open("",VideoTranscriptTasksTest.TASK)){
            VideoTranscriptTasksActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            assertNotNull(find(root,"这是一份完整的测试文字稿，不会自动替换已有摘录。"));
            assertNull(activity.findViewById(R.id.transcript_tasks_import));
            find(root,"复制文字稿").performClick();assertFalse(VideoTranscriptTasks.read(context,VideoTranscriptTasksTest.SCOPE,VideoTranscriptTasksTest.TASK).imported);
            find(root,"返回任务列表").performClick();drain(activity);assertNotNull(find(root,"把等待，留给后台。"));
        }
    }
    @Test public void retryCreatesFreshRequestAndRequiresNewConsentInsteadOfReusingOldJob()throws Exception {
        seed(VideoTranscriptTasksTest.TASK,"重试视频","failed","");
        try(var controller=open("",VideoTranscriptTasksTest.TASK)){
            VideoTranscriptTasksActivity activity=controller.get();find(activity.getWindow().getDecorView(),"重新添加链接并选择解析").performClick();
            Intent started=shadowOf(activity).getNextStartedActivity();assertEquals(VideoTranscriptActivity.class.getName(),started.getComponent().getClassName());
            assertTrue(started.getBooleanExtra("task_only",false));VideoTranscriptState state=VideoTranscriptState.read(context,started.getStringExtra(VideoTranscriptActivity.STATE));
            assertTrue(state.backgroundFlow);assertTrue(state.audioRequested);assertFalse(state.consent);assertNotEquals(VideoTranscriptTasksTest.TASK,state.requestId);
            assertEquals(VideoTranscriptTasksTest.URL,state.share);assertEquals("",state.id());
        }
    }
    @Test public void foreignNotificationScopeAndAccountChangeCloseTheInbox()throws Exception {
        Intent foreign=VideoTranscriptTasksActivity.open(context,VideoTranscriptTasksTest.OTHER,"");
        try(var controller=Robolectric.buildActivity(VideoTranscriptTasksActivity.class,foreign).setup()){assertTrue(controller.get().isFinishing());}
        try(var controller=open("",null)){
            CaptureAccountSession.preferences(context).edit().putString("scope",VideoTranscriptTasksTest.OTHER).commit();
            controller.pause().resume();assertTrue(controller.get().isFinishing());
        }
    }
    @Test public void associatedRecordNavigationCarriesOnlyCurrentScopeAndCaptureId()throws Exception {
        seed(VideoTranscriptTasksTest.TASK,"关联视频","completed","capture-owned");
        try(var controller=open("",VideoTranscriptTasksTest.TASK)){
            VideoTranscriptTasksActivity activity=controller.get();assertNotNull(activity.findViewById(R.id.transcript_tasks_import));
            find(activity.getWindow().getDecorView(),"查看 / 编辑关联记录").performClick();Intent started=shadowOf(activity).getNextStartedActivity();
            assertEquals(CaptureRecordEditActivity.class.getName(),started.getComponent().getClassName());
            assertEquals("capture-owned",started.getStringExtra(CaptureRecordEditActivity.ID));assertEquals(VideoTranscriptTasksTest.SCOPE,started.getStringExtra(CaptureRecordEditActivity.SCOPE));
        }
    }
    @Implements(value=VideoTranscriptClient.class,isInAndroidSdk=false)
    public static class Transport {
        @Implementation protected static JSONObject request(Context context,String scope,String method,String path,JSONObject body)throws Exception {
            if("GET".equals(method)&&path.startsWith("/jobs?"))return new JSONObject().put("jobs",new JSONArray()).put("has_more",false);
            throw new java.io.IOException("network_failed");
        }
    }
}
