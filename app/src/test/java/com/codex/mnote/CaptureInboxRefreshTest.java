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
@Config(sdk={30,35}, shadows={CaptureInboxRefreshTest.Preferences.class,CaptureInboxRefreshTest.Reader.class},
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
            assertTrue(((TextView)activity.findViewById(R.id.capture_refresh_status)).getText().toString().contains("Token"));
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
            assertEquals(activity.getString(R.string.capture_refresh_setup),((TextView)activity.findViewById(R.id.capture_refresh_status)).getText());
            assertTrue(activity.findViewById(R.id.capture_refresh_button).isEnabled());
        }
    }
    private void await(CaptureInboxActivity activity) throws Exception {
        ExecutorService executor = ReflectionHelpers.getField(activity,"refreshExecutor");
        executor.submit(() -> {}).get(10,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle();
    }
    @Implements(CaptureSyncPreferences.class) public static class Preferences {
        @Implementation protected static CaptureSyncPreferences.Config load(Context context) {
            if (!configured) throw new IllegalArgumentException("not configured");
            return new CaptureSyncPreferences.Config("https://test.example","test-only-credential","deny");
        }
    }
    @Implements(CaptureSyncReader.class) public static class Reader {
        @Implementation protected static CaptureSyncReader.Transport forConfig(CaptureSyncPreferences.Config config) { return transport; }
    }
}
