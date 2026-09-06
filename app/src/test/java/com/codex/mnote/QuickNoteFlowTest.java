package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35}, shadows = CaptureTileFlowTest.ScreenshotServiceShadow.class,
        instrumentedPackages = {"com.codex.mnote"})
@LooperMode(LooperMode.Mode.PAUSED)
public class QuickNoteFlowTest {
    @Before
    public void accessibilityDisabled() {
        // Android grants this merged-manifest signature permission at install
        // time. Robolectric API 30 needs the corresponding grant simulated.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        CaptureTileFlowTest.ScreenshotServiceShadow.ready = false;
        CaptureTileFlowTest.ScreenshotServiceShadow.configured = false;
        CaptureTileFlowTest.ScreenshotServiceShadow.requests = 0;
    }

    @Test
    public void noteTileLaunchesFreshEditorWithoutScreenshotPayload() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = QuickNoteTileService.noteIntent(context);
        assertEquals(new ComponentName(context, CaptureEditorActivity.class), intent.getComponent());
        assertNull(intent.getAction());
        assertNull(intent.getExtras());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0);
        assertEquals(0, intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Test
    public void noteTileIsSystemProtectedAndSeparateFromScreenshotTile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ServiceInfo info = context.getPackageManager().getServiceInfo(
                new ComponentName(context, QuickNoteTileService.class), 0);
        assertTrue(info.exported);
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission);
        assertEquals("随手记", info.loadLabel(context.getPackageManager()).toString());
        PendingIntent note = QuickNoteTileService.notePendingIntent(context);
        assertTrue(shadowOf(note).isImmutable());
        assertEquals(new ComponentName(context, CaptureEditorActivity.class),
                shadowOf(note).getSavedIntent().getComponent());
        assertNotEquals(note, CaptureQuickSettingsTileService.capturePendingIntent(context));
        if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions options = ReflectionHelpers.callStaticMethod(
                    ActivityOptions.class, "fromBundle", ReflectionHelpers.ClassParameter.from(
                            Bundle.class, shadowOf(note).getOptions()));
            assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    options.getPendingIntentCreatorBackgroundActivityStartMode());
        }
    }

    @Test
    public void noteOpensWithoutAccessibilityOrAnyImageAndFocusesInput() {
        try (ActivityController<CaptureEditorActivity> controller = note()) {
            CaptureEditorActivity activity = controller.get();
            assertEquals(0, CaptureTileFlowTest.ScreenshotServiceShadow.requests);
            assertEquals(View.GONE, activity.findViewById(R.id.capture_evidence_container).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.capture_tool_row).getVisibility());
            assertNull(ReflectionHelpers.getField(activity, "sourceBitmap"));
            assertNull(ReflectionHelpers.getField(activity, "draft"));
            assertTrue(activity.findViewById(R.id.capture_comment_input).hasFocus());
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                    activity.getWindow().getAttributes().softInputMode
                            & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
            assertNull(shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test
    public void saveNoteCreatesOnlyMetadataAndKeepsTodoClassification() throws Exception {
        try (ActivityController<CaptureEditorActivity> controller = note()) {
            CaptureEditorActivity activity = controller.get();
            EditText input = activity.findViewById(R.id.capture_comment_input);
            input.setText("  明天整理今天的三个想法  ");
            ((RadioGroup) activity.findViewById(R.id.capture_kind_group)).check(R.id.capture_kind_todo);
            activity.findViewById(R.id.capture_editor_save).performClick();
            // Drain the actual disk writer before running its UI completion callback.
            ExecutorService writer = ReflectionHelpers.getField(activity, "executor");
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            List<CaptureStore.CaptureRecord> records = CaptureStore.list(activity, 10);
            assertEquals(1, records.size());
            CaptureStore.CaptureRecord record = records.get(0);
            assertEquals("明天整理今天的三个想法", record.comment);
            assertEquals("todo", record.kind);
            assertEquals("quick_note", record.sourceType);
            assertEquals("", record.sourceText);
            assertEquals(CaptureStore.SYNC_LOCAL_ONLY, record.syncState);
            assertFalse(record.hasImage);
            assertNull(record.originalFile);
            assertNull(record.annotatedFile);
            File[] files = record.metadataFile.getParentFile().listFiles();
            assertNotNull(files);
            assertEquals(Arrays.toString(files), 1, files.length);
            assertEquals(record.metadataFile, files[0]);
            assertTrue(activity.isFinishing());
            assertEquals(0, CaptureTileFlowTest.ScreenshotServiceShadow.requests);
        }
    }

    @Test
    public void blankNoteCannotSaveAndCanCloseWithoutDiscardPrompt() {
        try (ActivityController<CaptureEditorActivity> controller = note()) {
            CaptureEditorActivity activity = controller.get();
            EditText input = activity.findViewById(R.id.capture_comment_input);
            input.setText("   ");
            activity.findViewById(R.id.capture_editor_save).performClick();
            assertNotNull(input.getError());
            assertTrue(CaptureStore.list(activity, 10).isEmpty());
            assertFalse(activity.isFinishing());
            activity.findViewById(R.id.capture_editor_cancel).performClick();
            assertTrue(activity.isFinishing());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test
    public void writtenNoteStillRequiresDiscardConfirmation() {
        try (ActivityController<CaptureEditorActivity> controller = note()) {
            CaptureEditorActivity activity = controller.get();
            ((EditText) activity.findViewById(R.id.capture_comment_input)).setText("不能静默丢弃");
            activity.findViewById(R.id.capture_editor_cancel).performClick();
            assertFalse(activity.isFinishing());
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
    }

    @Test
    public void inboxOffersIndependentEntryAndExpandableSetup() {
        try (ActivityController<CaptureInboxActivity> controller =
                     Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            View panel = activity.findViewById(R.id.capture_setup_panel);
            assertEquals(View.GONE, panel.getVisibility());
            activity.findViewById(R.id.capture_setup_toggle).performClick();
            assertEquals(View.VISIBLE, panel.getVisibility());
            assertTrue(activity.findViewById(R.id.capture_add_note_tile_button).isShown());
            assertTrue(activity.findViewById(R.id.capture_add_tile_button).isShown());
            activity.findViewById(R.id.capture_setup_toggle).performClick();
            assertEquals(View.GONE, panel.getVisibility());
            activity.findViewById(R.id.capture_quick_note_button).performClick();
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertEquals(new ComponentName(activity, CaptureEditorActivity.class), intent.getComponent());
            assertNull(intent.getExtras());
            assertEquals(0, CaptureTileFlowTest.ScreenshotServiceShadow.requests);
        }
    }

    private static ActivityController<CaptureEditorActivity> note() {
        return Robolectric.buildActivity(CaptureEditorActivity.class,
                QuickNoteTileService.noteIntent(RuntimeEnvironment.getApplication()))
                .create().start().resume();
    }
}
