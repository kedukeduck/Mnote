package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, instrumentedPackages = {"com.codex.mnote"},
    shadows = AppUpdateActivityTest.ClientShadow.class)
public class AppUpdateActivityTest {
    @Implements(value = AppUpdateClient.class, isInAndroidSdk = false)
    public static class ClientShadow {
        static AppRelease result;
        static boolean fail;
        @Implementation
        protected static AppRelease check(String current) throws Exception {
            if (fail)
                throw new IOException("rate_limited");
            return result;
        }
    }
    @Before
    public void reset() {
        ClientShadow.result = null;
        ClientShadow.fail = false;
    }
    private void drain(AppUpdateActivity activity) throws Exception {
        ExecutorService executor = ReflectionHelpers.getField(activity, "executor");
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    private Button button(AppUpdateActivity activity, String name) {
        return ReflectionHelpers.getField(activity, name);
    }
    private String status(AppUpdateActivity activity) {
        return ((TextView) ReflectionHelpers.getField(activity, "status")).getText().toString();
    }
    @Test
    public void latestAndNetworkFailureKeepRetryAvailable() throws Exception {
        try (var controller = Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            assertTrue(status(activity).contains("最新"));
            assertTrue(button(activity, "check").isEnabled());
            assertFalse(button(activity, "download").isEnabled());
            assertFalse(button(activity, "install").isEnabled());
            ClientShadow.fail = true;
            button(activity, "check").performClick();
            drain(activity);
            assertTrue(status(activity).contains("受限"));
            assertTrue(button(activity, "check").isEnabled());
        }
    }
    @Test
    public void newVersionNeedsExplicitDownloadAndConfirmation() throws Exception {
        ClientShadow.result = new AppRelease("1.8.0-test",
            "https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.8.0-test/"
            + "Mnote-Android-1.8.0-test.apk",
            "a".repeat(64), "新的版本说明", 100);
        try (var controller = Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            var activity = controller.get();
            drain(activity);
            assertTrue(status(activity).contains("1.8.0-test"));
            assertTrue(button(activity, "download").isEnabled());
            assertFalse(button(activity, "install").isEnabled());
            button(activity, "download").performClick();
            var dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            assertTrue(button(activity, "download").isEnabled());
            assertFalse(button(activity, "install").isEnabled());
        }
    }
    @Test
    public void destroyedScreenDoesNotReceiveLateCheckCallback() throws Exception {
        var controller = Robolectric.buildActivity(AppUpdateActivity.class).setup();
        var activity = controller.get();
        ExecutorService executor = ReflectionHelpers.getField(activity, "executor");
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        controller.pause().stop().destroy();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(executor.isShutdown());
    }
}
