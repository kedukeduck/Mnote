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
    public void clipboardQuickNoteUsesStyleAAndScrollableKeyboardLayout() throws Exception {
        try (ActivityController<QuickNoteActivity> controller = Robolectric.buildActivity(QuickNoteActivity.class).setup()) {
            QuickNoteActivity activity=controller.get();activity.onWindowFocusChanged(true);
            activity.<EditText>findViewById(R.id.capture_comment_input).setText("给灵感留一点空间，\n先记下此刻的想法。");
            View root=layout(activity,390,844);
            assertInside(root,root.findViewById(R.id.capture_editor_save));
            assertInside(root,root.findViewById(R.id.quick_note_clipboard));
            render(root,"quick-note-clipboard-default.png");
            activity.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                    android.content.ClipData.newPlainText("","记录，不只是保存信息。\n也留下那些被触动的时刻。"));
            activity.<android.widget.CompoundButton>findViewById(R.id.quick_note_clipboard).setChecked(true);
            root=layout(activity,390,844);
            root.findViewById(R.id.quick_note_capture_page).requestRectangleOnScreen(new android.graphics.Rect(0,0,120,48),true);
            render(root,"quick-note-clipboard-material.png");
            RuntimeEnvironment.setFontScale(1.5f);
            root=layout(activity,360,400);
            EditText input=root.findViewById(R.id.capture_comment_input);input.requestFocus();
            input.requestRectangleOnScreen(new android.graphics.Rect(0,0,input.getWidth(),80),true);
            assertInside(root,root.findViewById(R.id.capture_editor_save));
            assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    activity.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            render(root,"quick-note-clipboard-keyboard.png");
        } finally {RuntimeEnvironment.setFontScale(1f);}
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
            View input = root.findViewById(R.id.capture_comment_input);
            input.requestFocus();
            input.requestRectangleOnScreen(new android.graphics.Rect(0,0,input.getWidth(),input.getHeight()),true);
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

    @Test public void styleALibraryFiltersAndDockRemainUsable() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureStore.save(context,null,null,null,null,"comment","每周留十分钟，回看真正触动我的内容。",
                "text_share","记录不是终点，思考才是。","");
        CaptureStore.save(context,null,null,null,null,"thought","散步时不戴耳机，也许会有新的想法。","quick_note","","");
        CaptureStore.save(context,null,null,null,null,"todo","周日整理本周摘录","quick_note","","");
        try (ActivityController<CaptureInboxActivity> controller =
                     Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            View root = layout(activity,390,844);
            android.widget.LinearLayout records = root.findViewById(R.id.capture_records);
            assertEquals(3,records.getChildCount());
            assertInside(root,root.findViewById(R.id.capture_action_dock));
            render(root,"style-a-library.png");
            android.widget.RadioGroup filters = root.findViewById(R.id.capture_filter_group);
            filters.check(R.id.capture_filter_excerpt); assertEquals(1,records.getChildCount());
            filters.check(R.id.capture_filter_todo); assertEquals(1,records.getChildCount());
            filters.check(R.id.capture_filter_thought); assertEquals(1,records.getChildCount());
            EditText query = root.findViewById(R.id.capture_search);
            query.setText("周日"); assertEquals(0,records.getChildCount());
            assertEquals(View.VISIBLE,root.findViewById(R.id.capture_empty).getVisibility());
            filters.check(R.id.capture_filter_all); assertEquals(1,records.getChildCount());
            query.setText(""); assertEquals(3,records.getChildCount());
        }
    }

    @Test public void styleADockFitsSmallScreenWithLargeFontAndKeyboard() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        try (ActivityController<CaptureInboxActivity> controller =
                     Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            View root = layout(controller.get(),320,400);
            assertInside(root,root.findViewById(R.id.capture_action_dock));
            assertInside(root,root.findViewById(R.id.capture_quick_note_button));
            assertInside(root,root.findViewById(R.id.capture_start_button));
            render(root,"style-a-library-small.png");
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }

    @Test public void styleADetailAndDestructiveDialogsShareTheme() throws Exception {
        CaptureStore.save(RuntimeEnvironment.getApplication(),null,null,null,null,"thought",
                "给灵感留一点空间。","quick_note","","");
        try (ActivityController<CaptureInboxActivity> controller =
                     Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            CaptureInboxActivity activity = controller.get();
            android.util.TypedValue theme = new android.util.TypedValue();
            assertTrue(activity.getTheme().resolveAttribute(android.R.attr.alertDialogTheme,theme,true));
            assertEquals(R.style.Theme_Mnote_Dialog,theme.resourceId);
            android.widget.LinearLayout list=activity.findViewById(R.id.capture_records);
            list.getChildAt(0).performClick();
            android.app.AlertDialog detail=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(detail.isShowing());
            assertEquals(activity.getColor(R.color.danger),
                    ((android.widget.TextView)detail.findViewById(R.id.capture_review_delete)).getCurrentTextColor());
            renderDialog(detail,"style-a-detail.png");
            detail.findViewById(R.id.capture_review_delete).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog deletion=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(deletion.isShowing());
            assertEquals(activity.getColor(R.color.danger),deletion.getButton(-1).getCurrentTextColor());
            renderDialog(deletion,"style-a-delete-dialog.png");
            deletion.getButton(-2).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,CaptureStore.list(activity,10).size());
        }
    }

    @Test public void styleATransitionDialogUsesSameThemeWithoutOpaqueActivity() {
        android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),R.style.Theme_CaptureTrigger);
        android.util.TypedValue value=new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.alertDialogTheme,value,true);
        assertEquals(R.style.Theme_Mnote_Dialog,value.resourceId);
        context.getTheme().resolveAttribute(android.R.attr.windowIsTranslucent,value,true);
        assertNotEquals(0,value.data);
    }

    @Test public void savedScreenshotUsesLargeReadingPreviewAndIndependentThought() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        Bitmap full=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);
        full.eraseColor(Color.WHITE);
        Canvas canvas=new Canvas(full); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(32,36,48)); paint.setTextSize(24);
        canvas.drawText("给思考留一点空间",24,64,paint);
        paint.setTextSize(13);paint.setColor(Color.rgb(104,113,132));
        canvas.drawText("阅读摘录 · 虚构示例",24,96,paint);
        paint.setColor(Color.rgb(229,235,245));canvas.drawRoundRect(24,124,366,324,16,16,paint);
        paint.setColor(Color.rgb(144,162,190));
        android.graphics.Path mountain=new android.graphics.Path();mountain.moveTo(24,300);
        mountain.lineTo(126,190);mountain.lineTo(204,270);mountain.lineTo(284,172);
        mountain.lineTo(366,282);mountain.lineTo(366,324);mountain.lineTo(24,324);mountain.close();canvas.drawPath(mountain,paint);
        paint.setColor(Color.rgb(32,36,48));paint.setTextSize(18);
        canvas.drawText("记录，不只是保存信息。",24,370,paint);
        canvas.drawText("也保留那些被触动的时刻。",24,408,paint);
        paint.setTextSize(15);paint.setColor(Color.rgb(104,113,132));
        canvas.drawText("回顾时，记得问自己：",24,484,paint);
        canvas.drawText("这段内容为什么打动了我？",24,516,paint);
        File draft=CaptureStore.writeDraftBitmap(context,full);
        CaptureMarkupView markup=new CaptureMarkupView(context,null);markup.setSourceBitmap(full);
        ReflectionHelpers.<android.graphics.RectF>getField(markup,"cropRect").set(16,116,374,430);
        Bitmap crop=markup.renderOriginalSelection(),annotated=markup.renderAnnotatedSelection();
        CaptureStore.CaptureRecord record=CaptureStore.save(context,draft,crop,annotated,markup.annotationLayer(),
                "thought","想把每周回顾变成习惯。\n不只看收藏了什么，也看看自己为什么会被触动。",
                "screen","","","https://example.com/reading","user",true,null);
        crop.recycle();annotated.recycle();full.recycle();
        try(ActivityController<CaptureInboxActivity> controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            android.app.AlertDialog page=CaptureRecordPage.show(controller.get(),record,
                    CaptureStore.decodeReviewBitmap(record.annotatedFile),CaptureStore.decodeReviewBitmap(record.contextFile),
                    ()->fail("No delete expected"),url->assertEquals(record.sourceUrl,url));
            renderDialog(page,"style-a-image-detail.png");
            assertTrue(page.findViewById(R.id.capture_selection_preview).getHeight()>=320);
            page.findViewById(R.id.capture_preview_full).performClick();
            renderDialog(page,"style-a-image-context.png");
            page.dismiss();
        }
    }

    private static void renderDialog(android.app.AlertDialog dialog,String name) throws Exception {
        View root=dialog.getWindow().getDecorView();
        boolean reading=dialog.findViewById(R.id.capture_review_body)!=null;
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(reading ? 844 : 720,reading ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST));
        root.layout(0,0,360,root.getMeasuredHeight());
        render(root,name);
    }

    @Test public void accountLoginProtectsCredentialsAndRenders() throws Exception {
        try (ActivityController<CaptureAccountActivity> controller = Robolectric.buildActivity(CaptureAccountActivity.class).setup()) {
            assertTrue((controller.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            render(layout(controller.get(),390,844),"account-login.png");
        }
    }

    @Test public void textExcerptShowsQuoteContextChoiceAndThoughtField() throws Exception {
        Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,"让记录成为思考的开始。\n\n保留上下文，也保留自己的判断。\n\n下次回顾时，分清作者说了什么，以及我想到了什么。");
        try(ActivityController<CaptureEditorActivity> controller=Robolectric.buildActivity(CaptureEditorActivity.class,intent).setup()) {
            CaptureEditorActivity activity=controller.get();
            activity.<EditText>findViewById(R.id.capture_comment_input).setText("这让我想到：每周回顾时，把引用和自己的判断分开看。");
            View root=layout(activity,390,844);
            assertInside(root,activity.findViewById(R.id.capture_retain_text_context));
            assertInside(root,activity.findViewById(R.id.capture_comment_input));
            render(root,"text-excerpt.png");
        }
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
