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
        ScreenshotServiceShadow.overlayPresent = false;
        ScreenshotServiceShadow.overlayAccepted = true;
        ScreenshotServiceShadow.overlayRequests = 0;
        ScreenshotServiceShadow.restores = 0;
        ScreenshotServiceShadow.sourceReads = 0;
        ScreenshotServiceShadow.source = new CaptureSourceContext("com.android.chrome", "https://example.com/post", "browser_address_bar");
        ScreenshotServiceShadow.overlaidSource = null;
        ScreenshotServiceShadow.selection=CaptureSelectedText.EMPTY;
        ScreenshotServiceShadow.readerWindow=null;
        ScreenshotServiceShadow.bridgeWindowId=-1;
        CaptureSelectionTicket.clear();
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

    @Test public void bridgeTitleSurvivesTheRealPostCreateTitleInitialization() {
        try(ActivityController<CaptureTriggerActivity> bridge=Robolectric.buildActivity(CaptureTriggerActivity.class)
                .create().start().postCreate(null).resume().visible()) {
            assertEquals(CaptureTriggerActivity.SOURCE_BRIDGE_TITLE,bridge.get().getTitle().toString());
            assertEquals(CaptureTriggerActivity.SOURCE_BRIDGE_TITLE,
                    ReflectionHelpers.getField(bridge.get().getWindow(),"mTitle").toString());
        }
    }
    @Test
    @Config(shadows={ScreenshotServiceShadow.class,OwnNodeWindowShadow.class})
    public void bridgePassesItsAttachedWindowIdentityToTheOneShotReader() {
        try(ActivityController<CaptureTriggerActivity> bridge=Robolectric.buildActivity(CaptureTriggerActivity.class).setup()) {
            ScreenshotServiceShadow.selection=new CaptureSelectedText("reader.app",27,"quote","quote",0);
            bridge.get().onWindowFocusChanged(true); idle(350);
            assertEquals(2301,ScreenshotServiceShadow.bridgeWindowId);
            assertEquals(0,ScreenshotServiceShadow.requests);
            assertNotNull(shadowOf(bridge.get()).getNextStartedActivity());
        }
    }
    @Implements(android.view.accessibility.AccessibilityNodeInfo.class)
    public static class OwnNodeWindowShadow extends org.robolectric.shadows.ShadowAccessibilityNodeInfo {
        @Implementation protected int getWindowId() { return 2301; }
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
    public void overlayStartsOnlyAfterScreenshotArrivesWithoutOpeningAnEditorActivity() throws Exception {
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            CaptureTriggerActivity activity = controller.get();
            activity.onWindowFocusChanged(true);
            idle(350);
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertEquals(1, ScreenshotServiceShadow.sourceReads);
            File draft = File.createTempFile("tile-test", ".png", activity.getCacheDir());
            ScreenshotServiceShadow.callback.onCaptured(draft);
            assertEquals(1, ScreenshotServiceShadow.overlayRequests);
            assertEquals("https://example.com/post", ScreenshotServiceShadow.overlaidSource.url);
            assertEquals(activity.getString(R.string.capture_screenshot_ready),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertTrue(activity.isFinishing());
            Files.deleteIfExists(draft.toPath());
        }
    }

    @Test
    public void minimizedOverlayResumesWithoutTakingAnotherScreenshot() {
        ScreenshotServiceShadow.overlayPresent = true;
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            idle(1_000);
            assertEquals(1, ScreenshotServiceShadow.restores);
            assertEquals(0, ScreenshotServiceShadow.requests);
            assertEquals(0, ScreenshotServiceShadow.sourceReads);
            assertTrue(controller.get().isFinishing());
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    @Test
    public void rejectedOverlayFallsBackToEditorWithTheSameDraft() throws Exception {
        ScreenshotServiceShadow.overlayAccepted = false;
        try (ActivityController<CaptureTriggerActivity> controller = resumedTrigger()) {
            controller.get().onWindowFocusChanged(true);
            idle(350);
            File draft = File.createTempFile("overlay-fallback", ".png", controller.get().getCacheDir());
            ScreenshotServiceShadow.callback.onCaptured(draft);
            Intent fallback = shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(new ComponentName(controller.get(), CaptureEditorActivity.class), fallback.getComponent());
            assertEquals(draft.getAbsolutePath(), fallback.getStringExtra("com.codex.mnote.extra.CAPTURE_DRAFT_PATH"));
            assertEquals("com.android.chrome", fallback.getStringExtra("capture_source_package"));
            assertEquals("https://example.com/post", fallback.getStringExtra("capture_source_url"));
            assertEquals("browser_address_bar", fallback.getStringExtra("capture_source_origin"));
            assertTrue(draft.exists());
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

    @Test public void clickSourceSurvivesBridgeWindowButCurrentSourceWinsIfAvailable() throws Exception {
        Intent intent = CaptureQuickSettingsTileService.captureIntent(RuntimeEnvironment.getApplication());
        new CaptureSourceContext("com.sina.weibo", "", "").attachTo(intent);
        ScreenshotServiceShadow.source = CaptureSourceContext.EMPTY;
        try (ActivityController<CaptureTriggerActivity> controller =
                     Robolectric.buildActivity(CaptureTriggerActivity.class, intent).create().start().resume()) {
            controller.get().onWindowFocusChanged(true);
            idle(350);
            File draft = File.createTempFile("source-fallback", ".png", controller.get().getCacheDir());
            ScreenshotServiceShadow.callback.onCaptured(draft);
            assertEquals("com.sina.weibo", ScreenshotServiceShadow.overlaidSource.appPackage);
            Files.deleteIfExists(draft.toPath());
        }
        ScreenshotServiceShadow.source = new CaptureSourceContext("com.android.chrome", "https://example.com/new", "browser_address_bar");
        try (ActivityController<CaptureTriggerActivity> controller =
                     Robolectric.buildActivity(CaptureTriggerActivity.class, intent).create().start().resume()) {
            controller.get().onWindowFocusChanged(true);
            idle(350);
            File draft = File.createTempFile("source-current", ".png", controller.get().getCacheDir());
            ScreenshotServiceShadow.callback.onCaptured(draft);
            assertEquals("com.android.chrome", ScreenshotServiceShadow.overlaidSource.appPackage);
            assertEquals("https://example.com/new", ScreenshotServiceShadow.overlaidSource.url);
            Files.deleteIfExists(draft.toPath());
        }
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
    @Test public void selectedTextOpensEditorWithoutTakingScreenshotAndCanRetainOriginal() throws Exception {
        ScreenshotServiceShadow.selection=new CaptureSelectedText("com.example.reader",17,"选中文字","前文选中文字后文",2);
        try(ActivityController<CaptureTriggerActivity> bridge=Robolectric.buildActivity(CaptureTriggerActivity.class).create().start().resume()) {
            bridge.get().onWindowFocusChanged(true); idle(350);
            assertEquals(0,ScreenshotServiceShadow.requests);
            Intent editorIntent=shadowOf(bridge.get()).getNextStartedActivity(); assertNotNull(editorIntent);
            assertNull(editorIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
            try(ActivityController<CaptureEditorActivity> editor=Robolectric.buildActivity(CaptureEditorActivity.class,editorIntent).setup()) {
                CaptureEditorActivity activity=editor.get();
                assertEquals("选中文字",activity.<android.widget.TextView>findViewById(R.id.capture_source_text).getText().toString());
                activity.<android.widget.CompoundButton>findViewById(R.id.capture_retain_text_context).setChecked(true);
                activity.<android.widget.EditText>findViewById(R.id.capture_comment_input).setText("我的想法");
                activity.findViewById(R.id.capture_editor_save).performClick();
                ReflectionHelpers.<java.util.concurrent.ExecutorService>getField(activity,"executor").submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
                idle(0);
                CaptureStore.CaptureRecord record=CaptureStore.list(activity,10).get(0);
                assertEquals("accessibility_selection",record.sourceType); assertEquals("我的想法",record.comment);
                assertEquals("前文选中文字后文",record.captureContext.getJSONObject("text").getString("full_text"));
                assertEquals("accessibility_node",record.captureContext.getJSONObject("text").getString("origin"));
                assertFalse(record.hasImage);
            }
        }
    }
    @Test public void tileGoesDirectlyToTextEditorWhenSelectionIsPresentAndOtherwiseKeepsScreenshotRoute() {
        Context context=RuntimeEnvironment.getApplication();
        ScreenshotServiceShadow.selection=new CaptureSelectedText("com.example.reader",17,"quote","quote",0);
        Intent intent=CaptureQuickSettingsTileService.prepareCaptureIntent(context);
        assertEquals(new ComponentName(context,CaptureEditorActivity.class),intent.getComponent());
        assertNull(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        try(ActivityController<CaptureEditorActivity> editor=Robolectric.buildActivity(CaptureEditorActivity.class,intent).setup()) {
            assertEquals("quote",editor.get().<android.widget.TextView>findViewById(R.id.capture_source_text).getText().toString());
        }
        ScreenshotServiceShadow.selection=CaptureSelectedText.EMPTY;
        assertEquals(new ComponentName(context,CaptureTriggerActivity.class),CaptureQuickSettingsTileService.prepareCaptureIntent(context).getComponent());
        ScreenshotServiceShadow.selection=new CaptureSelectedText("com.example.reader",17,"quote","quote",0);
        ScreenshotServiceShadow.overlayPresent=true;
        assertEquals(new ComponentName(context,CaptureTriggerActivity.class),CaptureQuickSettingsTileService.prepareCaptureIntent(context).getComponent());
    }

    @Test public void selectedTextSurvivesRecreationWithoutReusingTicket() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        Intent intent=CaptureEditorActivity.forSelectedText(context,
                new CaptureSelectedText("com.example.reader",17,"quote","before quote after",7));
        try(ActivityController<CaptureEditorActivity> editor=Robolectric.buildActivity(CaptureEditorActivity.class,intent).setup()) {
            assertFalse(editor.get().<android.widget.CompoundButton>findViewById(R.id.capture_retain_text_context).isChecked());
            editor.get().<android.widget.CompoundButton>findViewById(R.id.capture_retain_text_context).setChecked(true);
            editor.get().<android.widget.EditText>findViewById(R.id.capture_comment_input).setText("my thought");
            editor.recreate();
            assertEquals("quote",editor.get().<android.widget.TextView>findViewById(R.id.capture_source_text).getText().toString());
            assertEquals("my thought",editor.get().<android.widget.EditText>findViewById(R.id.capture_comment_input).getText().toString());
            assertEquals("com.example.reader",ReflectionHelpers.getField(editor.get(),"sourcePackage"));
            org.json.JSONObject saved=ReflectionHelpers.<CaptureTextExcerpt>getField(editor.get(),"textExcerpt").context("quote");
            assertEquals("before quote after",saved.getString("full_text"));
            assertEquals(7,saved.getInt("start"));
            assertEquals("source_text_node",saved.getString("extent"));
            assertNull(intent.getStringExtra(CaptureSelectionTicket.EXTRA));
        }
    }

    @Test public void bridgeExposesUnderlyingWindowAfterShadeClosesInsteadOfRemainingFullScreenModal() {
        Context context=RuntimeEnvironment.getApplication();
        // Model the system boundary: while Quick Settings is open there is no source selection.
        Intent intent=CaptureQuickSettingsTileService.prepareCaptureIntent(context);
        assertEquals(new ComponentName(context,CaptureTriggerActivity.class),intent.getComponent());
        try(ActivityController<CaptureTriggerActivity> bridge=Robolectric.buildActivity(CaptureTriggerActivity.class,intent).create().start().resume()) {
            android.view.WindowManager.LayoutParams params=bridge.get().getWindow().getAttributes();
            assertEquals(1,params.width); assertEquals(1,params.height);
            assertTrue((params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0);
            assertTrue((params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
            assertEquals(0,params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            ScreenshotServiceShadow.readerWindow=bridge.get().getWindow();
            ScreenshotServiceShadow.selection=new CaptureSelectedText("reader.app",17,"quote","quote",0);
            bridge.get().onWindowFocusChanged(true); idle(350);
            assertEquals(0,ScreenshotServiceShadow.requests);
            assertEquals(new ComponentName(context,CaptureEditorActivity.class),shadowOf(bridge.get()).getNextStartedActivity().getComponent());
        }
    }

    @Test public void smallPassThroughBridgeDoesNotMakeSetupDialogUntouchable() {
        ScreenshotServiceShadow.ready=false; ScreenshotServiceShadow.configured=false;
        try(ActivityController<CaptureTriggerActivity> bridge=resumedTrigger()) {
            bridge.get().onWindowFocusChanged(true); idle(350);
            android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals(0,dialog.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertTrue(dialog.getWindow().getAttributes().width!=1);
        }
    }

    @Implements(value = CaptureAccessibilityService.class, isInAndroidSdk = false)
    public static class ScreenshotServiceShadow {
        static boolean ready;
        static boolean configured;
        static int requests;
        static CaptureAccessibilityService.CaptureCallback callback;
        static boolean overlayPresent;
        static boolean overlayAccepted;
        static int overlayRequests;
        static int restores;
        static int sourceReads;
        static CaptureSourceContext source;
        static CaptureSourceContext overlaidSource;
        static CaptureSelectedText selection;
        static android.view.Window readerWindow;
        static int bridgeWindowId;
        @Implementation protected static CaptureSelectedText readSelectionOnce(int ownBridgeWindowId) {
            bridgeWindowId=ownBridgeWindowId; return readSelectionOnce();
        }
        @Implementation protected static CaptureSelectedText readSelectionOnce() {
            if(readerWindow!=null) {
                android.view.WindowManager.LayoutParams params=readerWindow.getAttributes();
                if(params.width!=1 || params.height!=1
                        || (params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)==0)
                    return CaptureSelectedText.EMPTY;
            }
            return selection==null?CaptureSelectedText.EMPTY:selection;
        }

        @Implementation protected static CaptureSourceContext readSourceOnce() { sourceReads++; return source; }

        @Implementation protected static boolean hasOverlay() { return overlayPresent; }
        @Implementation protected static boolean restoreOverlay() { restores++; return true; }
        @Implementation protected static boolean showOverlay(File draft, CaptureSourceContext source) {
            overlayRequests++;
            overlaidSource = source;
            return overlayAccepted;
        }

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
