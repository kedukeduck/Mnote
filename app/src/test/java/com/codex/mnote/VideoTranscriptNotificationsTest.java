package com.codex.mnote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Result notices contain no video names, links or transcript and cannot cross an account boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,instrumentedPackages="com.codex.mnote")
public class VideoTranscriptNotificationsTest {
    Context context;NotificationManager manager;VideoTranscriptTasks.Task task;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();manager=context.getSystemService(NotificationManager.class);manager.cancelAll();
        CaptureAccountSession.preferences(context).edit().clear().putString("scope",VideoTranscriptTasksTest.SCOPE).commit();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        task=new VideoTranscriptTasks.Task();task.id=VideoTranscriptTasksTest.TASK;task.scope=VideoTranscriptTasksTest.SCOPE;
        task.status="completed";task.resultFetched=true;task.title="PRIVATE_VIDEO_TITLE";task.sourceUrl="https://private.example/secret";task.text="PRIVATE_TRANSCRIPT";
    }
    @After public void clear(){CaptureAccountSession.preferences(context).edit().clear().commit();manager.cancelAll();}
    @Test public void successNoticeIsPrivateGenericAndTapsOnlyAnOpaqueScopedTaskId(){
        assertTrue(VideoTranscriptNotifications.show(context,task));assertEquals(1,manager.getActiveNotifications().length);
        Notification notification=manager.getActiveNotifications()[0].getNotification();assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility);
        String description=notification.extras.getCharSequence(Notification.EXTRA_TITLE)+" "+notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertFalse(description.contains(task.title));assertFalse(description.contains(task.sourceUrl));assertFalse(description.contains(task.text));
        android.content.Intent opened=shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(task.id,opened.getStringExtra("task_id"));assertEquals(task.scope,opened.getStringExtra("account_scope"));
        assertEquals("com.codex.mnote.VideoTranscriptTasksActivity",opened.getComponent().getClassName());
        assertFalse(opened.getExtras().containsKey("text"));
    }
    @Test public void deniedPermissionDoesNotCrashOrPretendNotificationWasDelivered(){
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertFalse(VideoTranscriptNotifications.show(context,task));assertEquals(0,manager.getActiveNotifications().length);
    }
    @Test public void accountChangedDoesNotNotifyAndLogoutOnlyClearsThatScope(){
        assertTrue(VideoTranscriptNotifications.show(context,task));
        manager.notify("unrelated-sync",7,new Notification.Builder(context,"other").setSmallIcon(android.R.drawable.stat_notify_sync).build());
        CaptureAccountSession.preferences(context).edit().putString("scope",VideoTranscriptTasksTest.OTHER).commit();
        assertFalse(VideoTranscriptNotifications.show(context,task));
        VideoTranscriptNotifications.cancelScope(context,task.scope);assertEquals(1,manager.getActiveNotifications().length);
        assertEquals("unrelated-sync",manager.getActiveNotifications()[0].getTag());
    }
    @Test public void failureAndEmptySpeechUseFailureNoticeWithoutLeakingServerError(){
        task.status="failed";task.problemCode="PRIVATE_BACKEND_ERROR";
        assertTrue(VideoTranscriptNotifications.show(context,task));
        Notification notice=manager.getActiveNotifications()[0].getNotification();
        assertTrue(notice.extras.getString(Notification.EXTRA_TITLE).contains("未完成"));
        assertFalse(notice.extras.getString(Notification.EXTRA_TEXT).contains("PRIVATE_BACKEND_ERROR"));
        task.status="completed";task.problemCode="empty_transcript";assertTrue(VideoTranscriptNotifications.show(context,task));
        assertTrue(manager.getActiveNotifications()[0].getNotification().extras.getString(Notification.EXTRA_TITLE).contains("未完成"));
    }
    @Test public void sameTaskReplacesItsNoticeAndDifferentTaskDoesNotCollide(){
        assertTrue(VideoTranscriptNotifications.show(context,task));assertTrue(VideoTranscriptNotifications.show(context,task));
        assertEquals(1,manager.getActiveNotifications().length);
        String first=task.id;task.id="98765432-4321-4321-8321-cba987654321";assertTrue(VideoTranscriptNotifications.show(context,task));
        assertEquals(2,manager.getActiveNotifications().length);VideoTranscriptNotifications.cancelTask(context,task.scope,first);
        assertEquals(1,manager.getActiveNotifications().length);
    }
}
