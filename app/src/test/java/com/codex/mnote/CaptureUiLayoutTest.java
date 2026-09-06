package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Renders real Android Views with Skia; these are NOT device screenshots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "zh-rCN-w390dp-h844dp-mdpi",
        shadows = CaptureTileFlowTest.ScreenshotServiceShadow.class,
        instrumentedPackages = {"com.codex.mnote"})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureUiLayoutTest {
    @Before
    public void sampleClock() {
        SystemClock.setCurrentTimeMillis(java.time.Instant.parse("2026-09-06T00:20:00Z").toEpochMilli());
    }

    @Test
    public void inboxRendersRecordsBeforeExpandedTechnicalSettings() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureStore.save(context, null, null, null, null, "thought",
                "好的记录，不只是留下信息。\n也留下当时的自己。", "quick_note", "", "");
        CaptureStore.save(context, null, null, null, null, "todo",
                "周末留半小时，回顾这一周的小想法。", "quick_note", "", "");
        try (ActivityController<CaptureInboxActivity> controller =
                     Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            View root = layout(controller.get(), 390, 844);
            assertEquals(View.GONE, root.findViewById(R.id.capture_setup_panel).getVisibility());
            assertTrue(root.findViewById(R.id.capture_records).getHeight() > 0);
            render(root, "inbox.png");
            root.findViewById(R.id.capture_setup_toggle).performClick();
            root = layout(controller.get(), 390, 844);
            render(root, "inbox-shortcuts.png");
        }
    }

    @Test
    public void quickNoteHasLargeWritingSurfaceAndReadableSaveAction() throws Exception {
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class).setup()) {
            CaptureEditorActivity activity = controller.get();
            EditText input = activity.findViewById(R.id.capture_comment_input);
            input.setText("突然想到，\n\n给灵感留一点空间，\n不必每一次都从截图开始。");
            View root = layout(activity, 390, 844);
            assertTrue(input.getHeight() > 500);
            assertInside(root, input);
            assertInside(root, root.findViewById(R.id.capture_editor_save));
            render(root, "quick-note.png");
        }
    }

    @Test
    public void quickNoteRemainsUsableWithLargeTextAndKeyboardSizedViewport() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class).setup()) {
            View root = layout(controller.get(), 360, 400);
            EditText input = root.findViewById(R.id.capture_comment_input);
            input.setText("写下今天最重要的一件事。");
            assertTrue("Writing surface remains usable with keyboard space reserved", input.getHeight() >= 100);
            assertInside(root, input);
            assertInside(root, root.findViewById(R.id.capture_editor_save));
            assertInside(root, root.findViewById(R.id.capture_kind_group));
            render(root, "quick-note-large-text-keyboard-space.png");
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void screenshotEditorRetainsCanvasAndCommentComposer() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap sample = Bitmap.createBitmap(390, 620, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sample);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(29, 29, 31));
        paint.setTextSize(24);
        canvas.drawText("阅读摘录 · 示例", 28, 72, paint);
        paint.setTextSize(18);
        canvas.drawText("为值得思考的内容，", 28, 140, paint);
        canvas.drawText("留下一点自己的声音。", 28, 178, paint);
        File draft = CaptureStore.writeDraftBitmap(context, sample);
        sample.recycle();
        Intent intent = new Intent(context, CaptureEditorActivity.class)
                .setAction("com.codex.mnote.action.EDIT_SCREENSHOT")
                .putExtra("com.codex.mnote.extra.CAPTURE_DRAFT_PATH", draft.getAbsolutePath());
        try (ActivityController<CaptureEditorActivity> controller =
                     Robolectric.buildActivity(CaptureEditorActivity.class, intent).setup()) {
            CaptureEditorActivity activity = controller.get();
            ExecutorService writer = ReflectionHelpers.getField(activity, "executor");
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            View root = layout(activity, 390, 844);
            assertEquals(View.VISIBLE, root.findViewById(R.id.capture_markup_container).getVisibility());
            assertEquals(View.VISIBLE, root.findViewById(R.id.capture_tool_row).getVisibility());
            assertTrue(root.findViewById(R.id.capture_markup_view).getHeight() > 300);
            assertInside(root, root.findViewById(R.id.capture_comment_input));
            render(root, "screenshot-editor.png");
            root = layout(activity, 360, 480);
            assertTrue(root.findViewById(R.id.capture_markup_view).getHeight() > 0);
            assertInside(root, root.findViewById(R.id.capture_editor_save));
            assertInside(root, root.findViewById(R.id.capture_comment_input));
            ((EditText) root.findViewById(R.id.capture_comment_input)).setText("这段话提醒我定期回顾。");
            root.findViewById(R.id.capture_editor_save).performClick();
            writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            CaptureStore.CaptureRecord record = CaptureStore.list(activity, 10).get(0);
            assertTrue(record.hasImage);
            assertTrue(record.originalFile.isFile());
            assertTrue(record.annotatedFile.isFile());
            assertEquals("screen", record.sourceType);
            assertEquals("这段话提醒我定期回顾。", record.comment);
            assertFalse("Temporary full-screen draft is removed after saving", draft.exists());
        } finally {
            CaptureStore.discardDraft(context, draft);
        }
    }

    @Test
    public void settingsRemainSecureAndRenderWithoutRealCredentials() throws Exception {
        try (ActivityController<CaptureSyncSettingsActivity> controller =
                     Robolectric.buildActivity(CaptureSyncSettingsActivity.class).setup()) {
            assertTrue((controller.get().getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            render(layout(controller.get(), 390, 844), "sync-settings.png");
        }
    }

    private static View layout(Activity activity, int width, int height) {
        View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        return root;
    }

    private static void assertInside(View root, View child) {
        int[] rootLocation = new int[2];
        int[] childLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        child.getLocationInWindow(childLocation);
        assertTrue(child.getWidth() > 0 && child.getHeight() > 0);
        assertTrue(childLocation[0] >= rootLocation[0]);
        assertTrue(childLocation[1] >= rootLocation[1]);
        assertTrue(childLocation[0] + child.getWidth() <= rootLocation[0] + root.getWidth());
        assertTrue(childLocation[1] + child.getHeight() <= rootLocation[1] + root.getHeight());
    }

    private static void render(View root, String name) throws Exception {
        File directory = new File("build/ui-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }
}
