package com.codex.mnote;

import android.content.Context;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35}, shadows={CaptureInboxRefreshTest.Preferences.class,CaptureInboxRefreshTest.Reader.class,
        CaptureInboxRefreshTest.Session.class,CaptureInboxRefreshTest.Sync.class},
        instrumentedPackages="com.codex.mnote")
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureInboxRefreshTest {
    static boolean configured;
    static CaptureSyncReader.Transport transport;
    @Before public void setup() {
        configured = true;
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    }
    @Test public void refreshPullsIntoInboxDisablesDuplicateTapAndReportsSuccess() throws Exception {
        byte[] feed = CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert","remote-note",CaptureRemoteCacheTest.record("remote-note",1)));
        CountDownLatch gate = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        transport = (path,limit) -> { calls.incrementAndGet();
            try { assertTrue(gate.await(5,TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new IOException(error); }
            return feed;
        };
        try (ActivityController<CaptureInboxActivity> controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            Button refresh = activity.findViewById(R.id.capture_refresh_button);
            refresh.performClick(); assertFalse(refresh.isEnabled());
            refresh.performClick(); gate.countDown(); await(activity);
            assertEquals(1,calls.get()); assertTrue(refresh.isEnabled());
            assertTrue(((TextView)activity.findViewById(R.id.capture_refresh_status)).getText().toString().contains("1"));
            assertEquals(1,((android.widget.LinearLayout)activity.findViewById(R.id.capture_records)).getChildCount());
        } finally { gate.countDown(); }
    }
    @Test public void authErrorIsActionableAndKeepsLocalRecords() throws Exception {
        transport = (path,limit) -> { throw new IOException("http_401"); };
        CaptureStore.save(RuntimeEnvironment.getApplication(),null,null,null,null,"thought","本机笔记","quick_note","","");
        try (ActivityController<CaptureInboxActivity> controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            activity.findViewById(R.id.capture_refresh_button).performClick(); await(activity);
            assertTrue(((TextView)activity.findViewById(R.id.capture_refresh_status)).getText().toString().contains("登录"));
            assertTrue(activity.findViewById(R.id.capture_refresh_button).isEnabled());
            assertEquals(1,((android.widget.LinearLayout)activity.findViewById(R.id.capture_records)).getChildCount());
        }
    }
    @Test public void missingConfigurationShowsSetupWithoutNetwork() {
        configured = false;
        transport = (path,limit) -> { throw new AssertionError("No network without configuration"); };
        try (ActivityController<CaptureInboxActivity> controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            activity.findViewById(R.id.capture_refresh_button).performClick();
            assertTrue(((TextView)activity.findViewById(R.id.capture_refresh_status)).getText().toString().contains("登录"));
            assertTrue(activity.findViewById(R.id.capture_refresh_button).isEnabled());
        }
    }
    @Test public void localSaveUpdatesAlreadyResumedInboxWithoutNetworkOrRestart() throws Exception {
        configured = false;
        transport = (path,limit) -> { throw new AssertionError("Local save must not pull"); };
        try (ActivityController<CaptureInboxActivity> controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            android.widget.LinearLayout list = activity.findViewById(R.id.capture_records);
            assertEquals(0,list.getChildCount());
            CaptureStore.save(activity,null,null,null,null,"thought","悬浮层保存的想法","quick_note","","");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,list.getChildCount());
            assertEquals("悬浮层保存的想法",((TextView)list.getChildAt(0).findViewById(R.id.capture_item_comment)).getText());
        }
    }

    @Test public void refreshReloadsLocalDataEvenWhenBroadcastWasMissedAndNoTokenExists() throws Exception {
        configured = false;
        try (ActivityController<CaptureInboxActivity> controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            Context noBroadcast = new android.content.ContextWrapper(activity) {
                @Override public void sendBroadcast(android.content.Intent intent) { }
            };
            CaptureStore.save(noBroadcast,null,null,null,null,"thought","离线也能刷新","quick_note","","");
            android.widget.LinearLayout list = activity.findViewById(R.id.capture_records);
            assertEquals(0,list.getChildCount());
            activity.findViewById(R.id.capture_refresh_button).performClick();
            assertEquals(1,list.getChildCount());
        }
    }

    @Test public void onlyCommittedLocalWritesNotifyAndBroadcastFailureCannotUndoSave() throws Exception {
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        Context failingBroadcast = new android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public void sendBroadcast(android.content.Intent intent) {
                assertEquals(CaptureStore.ACTION_RECORDS_CHANGED,intent.getAction());
                assertEquals(getPackageName(),intent.getPackage());
                assertFalse(CaptureStore.list(this,10).isEmpty());
                notifications.incrementAndGet();
                throw new IllegalStateException("delivery unavailable");
            }
        };
        assertThrows(IOException.class,() -> CaptureStore.save(failingBroadcast,null,null,null,null,"thought","","quick_note","",""));
        assertEquals(0,notifications.get());
        CaptureStore.save(failingBroadcast,null,null,null,null,"thought","持久化先于通知","quick_note","","");
        assertEquals(1,notifications.get());
        assertEquals(1,CaptureStore.list(failingBroadcast,10).size());
    }

    @Test public void sourceSettingsEntryIsVisibleWithoutExpandingSetup() {
        try (ActivityController<CaptureInboxActivity> controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            assertEquals(android.view.View.GONE,activity.findViewById(R.id.capture_setup_panel).getVisibility());
            assertTrue(activity.findViewById(R.id.capture_source_settings_button).isShown());
            activity.findViewById(R.id.capture_source_settings_button).performClick();
            assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    private void await(CaptureInboxActivity activity) throws Exception {
        ExecutorService executor = ReflectionHelpers.getField(activity,"refreshExecutor");
        executor.submit(() -> {}).get(10,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle();
    }
    @Implements(CaptureSyncPreferences.class) public static class Preferences {
        @Implementation protected static CaptureSyncPreferences.Config load(Context context) {
            if (!configured) throw new IllegalArgumentException("not configured");
            return new CaptureSyncPreferences.Config("https://test.example","test-only-credential","deny",Session.scope(context));
        }
    }
    @Implements(CaptureSyncReader.class) public static class Reader {
        @Implementation protected static CaptureSyncReader.Transport forConfig(CaptureSyncPreferences.Config config) { return transport; }
    }
    @Implements(CaptureAccountSession.class) public static class Session {
        @Implementation protected static boolean hasAccount(Context context) { return configured; }
        @Implementation protected static String scope(Context context) { return configured ? "a".repeat(64) : "guest"; }
        @Implementation protected static String username(Context context) { return "test-user"; }
    }
    @Implements(CaptureAccountSync.class) public static class Sync {
        @Implementation protected static void enqueue(Context context) { }
        @Implementation protected static int run(Context context) throws Exception {
            return CaptureRemoteCache.pull(context,Session.scope(context),transport,()->true);
        }
    }
}
