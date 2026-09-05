package com.codex.mnote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;

/** Regression scenarios for one tile click before/after SystemUI gives up focus. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35}, shadows = CaptureTileFlowTest.ScreenshotServiceShadow.class,
        instrumentedPackages = {"com.codex.mnote"})
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureTileFlowTest {
    @Before
    public void resetService() {
        ScreenshotServiceShadow.ready = true;
        ScreenshotServiceShadow.configured = true;
        ScreenshotServiceShadow.requests = 0;
        ScreenshotServiceShadow.callback = null;
    }

    @Test
    public void tileLaunchCannotReuseInboxOrAnOlderEditorTask() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = CaptureQuickSettingsTileService.captureIntent(context);
        assertEquals(new ComponentName(context, CaptureTriggerActivity.class), intent.getComponent());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
        assertEquals(0, intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ActivityInfo info = context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
        assertTrue(info.taskAffinity == null || info.taskAffinity.isEmpty());
        assertFalse(info.exported);
        assertTrue((info.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
    }

    @Test
    public void tilePendingIntentIsExplicitImmutableAndHasAndroid15CreatorOptIn() {
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent pendingIntent = CaptureQuickSettingsTileService.capturePendingIntent(context);
        assertTrue(shadowOf(pendingIntent).isImmutable());
        assertEquals(new ComponentName(context, CaptureTriggerActivity.class),
                shadowOf(pendingIntent).getSavedIntent().getComponent());
        if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions options = ReflectionHelpers.callStaticMethod(
                    ActivityOptions.class, "fromBundle",
                    ReflectionHelpers.ClassParameter.from(
                            Bundle.class, shadowOf(pendingIntent).getOptions()));
            assertNotNull(options);
            assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    options.getPendingIntentCreatorBackgroundActivityStartMode());
        }
    }

    @Test
    public void upgradingDoesNotReuseSystemUiPendingIntentWithOldTaskFlags() {
        Context context = RuntimeEnvironment.getApplication();
        Intent legacyIntent = new Intent(context, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent legacy = PendingIntent.getActivity(context, 4301, legacyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent updated = CaptureQuickSettingsTileService.capturePendingIntent(context);
        assertFalse(legacy.equals(updated));
        assertEquals(0, shadowOf(updated).getSavedIntent().getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
        assertTrue((shadowOf(updated).getSavedIntent().getFlags()
                & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
    }

    @Test
    public void firstClickWaitsForPanelFocusThenCapturesExactlyOnce() {
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            idle(1_000);
            assertEquals(0, ScreenshotServiceShadow.requests);
            controller.get().onWindowFocusChanged(true);
            idle(349);
            assertEquals(0, ScreenshotServiceShadow.requests);
            idle(1);
            assertEquals(1, ScreenshotServiceShadow.requests);
            controller.get().onWindowFocusChanged(false);
            controller.get().onWindowFocusChanged(true);
            idle(1_000);
            assertEquals(1, ScreenshotServiceShadow.requests);
        }
    }

    @Test
    public void reopeningShadeCancelsOldTimerAndWaitsForNewFocus() {
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            controller.get().onWindowFocusChanged(true);
            idle(200);
            controller.get().onWindowFocusChanged(false);
            idle(1_000);
            assertEquals(0, ScreenshotServiceShadow.requests);
            controller.get().onWindowFocusChanged(true);
            idle(350);
            assertEquals(1, ScreenshotServiceShadow.requests);
        }
    }

    @Test
    public void enabledServiceConnectingOnFirstClickDoesNotRequireSecondClick() {
        ScreenshotServiceShadow.ready = false;
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            controller.get().onWindowFocusChanged(true);
            idle(350);
            assertEquals(0, ScreenshotServiceShadow.requests);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            ScreenshotServiceShadow.ready = true;
            idle(250);
            assertEquals(1, ScreenshotServiceShadow.requests);
        }
    }

    @Test
    public void disconnectedServiceEventuallyShowsAnActionableDialog() {
        ScreenshotServiceShadow.ready = false;
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            controller.get().onWindowFocusChanged(true);
            idle(3_500);
            assertEquals(0, ScreenshotServiceShadow.requests);
            assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
    }

    @Test
    public void leavingBeforeTimerExpiresDoesNotCaptureInBackground() {
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            controller.get().onWindowFocusChanged(true);
            idle(200);
            controller.pause();
            idle(1_000);
            assertEquals(0, ScreenshotServiceShadow.requests);
        }
    }

    @Test
    public void editorStartsOnlyAfterScreenshotArrives() throws Exception {
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            CaptureTriggerActivity activity = controller.get();
            activity.onWindowFocusChanged(true);
            idle(350);
            assertNull(shadowOf(activity).getNextStartedActivity());
            File draft = File.createTempFile("tile-test", ".png", activity.getCacheDir());
            ScreenshotServiceShadow.callback.onCaptured(draft);
            Intent editor = shadowOf(activity).getNextStartedActivity();
            assertEquals(new ComponentName(activity, CaptureEditorActivity.class), editor.getComponent());
            assertEquals(0, editor.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
            assertTrue(activity.isFinishing());
            Files.deleteIfExists(draft.toPath());
        }
    }

    @Test
    public void screenshotArrivingAfterUserLeavesNeverOpensEditor() throws Exception {
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            CaptureTriggerActivity activity = controller.get();
            activity.onWindowFocusChanged(true);
            idle(350);
            controller.pause();
            // Use the real draft directory: discardDraft deliberately rejects other paths.
            File draftDir = new File(activity.getCacheDir(), "capture_drafts");
            assertTrue(draftDir.isDirectory() || draftDir.mkdirs());
            File draft = File.createTempFile("capture-", ".png", draftDir);
            ScreenshotServiceShadow.callback.onCaptured(draft);
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertTrue(activity.isFinishing());
            assertFalse(draft.exists());
        }
    }

    private static ActivityController<CaptureTriggerActivity> resumedTrigger() {
        return Robolectric.buildActivity(CaptureTriggerActivity.class).create().start().resume();
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Implements(value = CaptureAccessibilityService.class, isInAndroidSdk = false)
    public static class ScreenshotServiceShadow {
        static boolean ready;
        static boolean configured;
        static int requests;
        static CaptureAccessibilityService.CaptureCallback callback;

        @Implementation
        protected static boolean isReady() {
            return ready;
        }

        @Implementation
        protected static boolean isConfigured(Context context) {
            return configured;
        }

        @Implementation
        protected static void captureOnce(CaptureAccessibilityService.CaptureCallback result) {
            requests++;
            callback = result;
        }
    }
}
