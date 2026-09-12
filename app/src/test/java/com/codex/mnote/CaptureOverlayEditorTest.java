package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35}, qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureOverlayEditorTest {
    private ServiceController<CaptureAccessibilityService> serviceController;
    private CaptureAccessibilityService service;
    private CaptureOverlayEditor editor;
    private File draft;
    private int closeCount;

    @Before public void setup() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        serviceController = Robolectric.buildService(CaptureAccessibilityService.class).create();
        service = serviceController.get();
        Bitmap bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(29, 29, 31));
        paint.setTextSize(23);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawText("一条动态 · 虚构示例", 28, 72, paint);
        paint.setTextSize(18);
        canvas.drawText("灵感出现的那一刻，", 28, 130, paint);
        canvas.drawText("也值得留下它的来处。", 28, 168, paint);
        draft = CaptureStore.writeDraftBitmap(service, bitmap);
        bitmap.recycle();
        editor = new CaptureOverlayEditor(service, draft, () -> closeCount++);
    }

    @After public void cleanup() {
        if (editor != null) editor.close();
        serviceController.destroy();
        CaptureStore.discardDraft(service, draft);
    }

    @Test public void opensServiceOwnedFocusableAccessibilityWindowWithNoActivity() throws Exception {
        open();
        WindowManager.LayoutParams params = ReflectionHelpers.getField(editor, "params");
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type);
        assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                params.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        assertTrue((Boolean) ReflectionHelpers.getField(editor, "attached"));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertEquals(View.VISIBLE, root().findViewById(R.id.capture_markup_container).getVisibility());
        assertTrue(root().findViewById(R.id.capture_editor_save).isEnabled());
        assertFalse(root().findViewById(R.id.capture_composer).isShown());
        assertEquals("下一步", ((android.widget.Button) root().findViewById(R.id.capture_editor_save)).getText().toString());
        assertEquals(root().getHeight(), root().findViewById(R.id.capture_markup_view).getHeight());
        render("overlay-editor.png");
    }

    @Test public void minimizeRestoreKeepsTextLinkAndMarkupInSameSession() throws Exception {
        open();
        ((EditText) root().findViewById(R.id.capture_comment_input)).setText("原帖让我想到的一件事");
        ((EditText) root().findViewById(R.id.capture_source_url)).setText("https://m.weibo.cn/detail/123456789");
        root().findViewById(R.id.capture_tool_pen).performClick();
        CaptureMarkupView markup = root().findViewById(R.id.capture_markup_view);
        assertTrue(root().findViewById(R.id.capture_tool_pen).isSelected());
        stroke(markup);
        assertTrue(markup.canUndo());
        next();
        editor.minimize();
        assertFalse((Boolean) ReflectionHelpers.getField(editor, "attached"));
        assertTrue(draft.exists());
        assertEquals(0, closeCount);
        assertTrue(editor.restore());
        assertEquals("原帖让我想到的一件事",
                ((EditText) root().findViewById(R.id.capture_comment_input)).getText().toString());
        assertEquals("https://m.weibo.cn/detail/123456789",
                ((EditText) root().findViewById(R.id.capture_source_url)).getText().toString());
        assertTrue(markup.canUndo());
        assertTrue(CaptureStore.list(service, 10).isEmpty());
    }

    @Test public void selectionThenCompactComposerCanGoBackWithoutLosingCropOrNote() throws Exception {
        open();
        CaptureMarkupView markup = root().findViewById(R.id.capture_markup_view);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40, 100, 0);
        MotionEvent up = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 240, 350, 0);
        markup.onTouchEvent(down);
        markup.onTouchEvent(up);
        down.recycle(); up.recycle();
        String selection = markup.annotationLayer().getJSONObject("selection").toString();
        next();
        assertTrue(root().findViewById(R.id.capture_composer).isShown());
        assertFalse(root().findViewById(R.id.capture_tool_row).isShown());
        assertTrue(CaptureStore.list(service, 10).isEmpty());
        assertFalse(markup.isEnabled());
        View dock = ReflectionHelpers.getField(editor, "column");
        assertTrue(dock.getTop() < 50);
        android.widget.ImageView preview = root().findViewById(R.id.capture_selection_preview);
        assertTrue(preview.isShown());
        assertNotNull(preview.getDrawable());
        assertTrue(preview.getHeight() >= 280);
        assertTrue(root().findViewById(R.id.capture_preview_modes).isShown());
        assertFalse(root().findViewById(R.id.capture_editor_status).isShown());
        ((EditText) root().findViewById(R.id.capture_comment_input)).setText("先选区域，再写想法");
        ((EditText) root().findViewById(R.id.capture_tags_input)).setText("截图，灵感");
        render("overlay-compose.png");
        android.widget.CompoundButton retain=root().findViewById(R.id.capture_retain_image_context);
        assertFalse(retain.isChecked());
        root().findViewById(R.id.capture_preview_full).performClick();
        assertSame(ReflectionHelpers.getField(editor,"sourceBitmap"),
                ((android.graphics.drawable.BitmapDrawable)preview.getDrawable()).getBitmap());
        assertFalse(retain.isChecked());
        assertEquals(selection,markup.annotationLayer().getJSONObject("selection").toString());
        assertFalse(ReflectionHelpers.<android.graphics.RectF>getField(preview,"selection").isEmpty());
        render("overlay-compose-context.png");
        root().findViewById(R.id.capture_preview_crop).performClick();
        assertTrue(ReflectionHelpers.<android.graphics.RectF>getField(preview,"selection").isEmpty());
        root().findViewById(R.id.capture_editor_cancel).performClick();
        layout(390, 844);
        assertFalse(root().findViewById(R.id.capture_composer).isShown());
        assertTrue(markup.isEnabled());
        assertEquals(selection, markup.annotationLayer().getJSONObject("selection").toString());
        next();
        assertEquals("先选区域，再写想法", ((EditText) root().findViewById(R.id.capture_comment_input)).getText().toString());
        root().findViewById(R.id.capture_editor_save).performClick();
        drain();
        CaptureStore.CaptureRecord record = CaptureStore.list(service, 10).get(0);
        assertEquals("截图，灵感",CaptureTags.input(record.tags));
        Bitmap crop = android.graphics.BitmapFactory.decodeFile(record.originalFile.getAbsolutePath());
        assertEquals(200, crop.getWidth());
        assertEquals(250, crop.getHeight());
        crop.recycle();
    }

    @Test public void toolbarCanMoveAwayFromBottomSelectionAndReturnsForComposer() throws Exception {
        open();
        View dock = ReflectionHelpers.getField(editor, "column");
        assertTrue(dock.getTop() > 500);
        root().findViewById(R.id.capture_tool_move).performClick();
        layout(390, 844);
        assertTrue(dock.getTop() < 50);
        next();
        assertTrue(dock.getTop() < 50);
        editor.minimize();
        assertTrue(editor.restore());
        assertTrue(root().findViewById(R.id.capture_composer).isShown());
    }

    @Test public void detectedSourceIsEditableAndCapturedOnlyOnceForEntireSession() throws Exception {
        editor.close();
        editor = new CaptureOverlayEditor(service, draft,
                new CaptureSourceContext("com.android.chrome", "https://example.com/article/123", "browser_address_bar"), () -> closeCount++);
        open();
        next();
        EditText input = root().findViewById(R.id.capture_source_url);
        assertEquals("https://example.com/article/123", input.getText().toString());
        editor.minimize();
        assertTrue(editor.restore());
        root().findViewById(R.id.capture_editor_save).performClick();
        drain();
        CaptureStore.CaptureRecord record = CaptureStore.list(service, 10).get(0);
        assertEquals("com.android.chrome", record.sourcePackage);
        assertEquals("browser_address_bar", record.sourceUrlOrigin);
        assertEquals("https://example.com/article/123", record.sourceUrl);
    }

    @Test public void savesScreenshotAnnotationCommentAndLinkTogetherThenRemovesWindow() throws Exception {
        open();
        ((EditText) root().findViewById(R.id.capture_comment_input)).setText("回头继续读这条动态");
        ((EditText) root().findViewById(R.id.capture_source_url)).setText("https://m.weibo.cn/detail/123456789?from=share");
        ((RadioGroup) root().findViewById(R.id.capture_kind_group)).check(R.id.capture_kind_todo);
        root().findViewById(R.id.capture_tool_highlighter).performClick();
        stroke(root().findViewById(R.id.capture_markup_view));
        next();
        root().findViewById(R.id.capture_editor_save).performClick();
        drain();
        List<CaptureStore.CaptureRecord> records = CaptureStore.list(service, 10);
        assertEquals(1, records.size());
        CaptureStore.CaptureRecord record = records.get(0);
        assertEquals("todo", record.kind);
        assertEquals("回头继续读这条动态", record.comment);
        assertEquals("https://m.weibo.cn/detail/123456789?from=share", record.sourceUrl);
        assertEquals("user_entered", record.sourceUrlOrigin);
        assertTrue(record.originalFile.isFile());
        assertTrue(record.annotatedFile.isFile());
        assertTrue(CaptureStore.annotationItems(record).length() > 0);
        assertFalse(draft.exists());
        assertFalse((Boolean) ReflectionHelpers.getField(editor, "attached"));
        assertEquals(1, closeCount);
        assertEquals(service.getString(R.string.capture_saved), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void cancellationUsesOverlayConfirmationAndPreservesUntilConfirmed() throws Exception {
        open();
        root().findViewById(R.id.capture_editor_cancel).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                dialog.getWindow().getAttributes().type);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(draft.exists());
        assertEquals(0, closeCount);
        root().findViewById(R.id.capture_editor_cancel).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, closeCount);
        assertFalse(draft.exists());
        assertFalse((Boolean) ReflectionHelpers.getField(editor, "attached"));
    }

    @Test public void invalidSourceLinkCannotBeSavedOrExecuted() throws Exception {
        open();
        next();
        EditText link = root().findViewById(R.id.capture_source_url);
        link.setText("intent://detail#Intent;scheme=unsafe;end");
        root().findViewById(R.id.capture_editor_save).performClick();
        assertNotNull(link.getError());
        assertTrue(CaptureStore.list(service, 10).isEmpty());
        assertTrue(draft.exists());
        assertTrue((Boolean) ReflectionHelpers.getField(editor, "attached"));
    }

    @Test public void attachmentFailureLeavesDraftAvailableForActivityFallback() {
        editor.close();
        Context refused = new ContextWrapper(service) {
            @Override public Object getSystemService(String name) {
                if (!Context.WINDOW_SERVICE.equals(name)) return super.getSystemService(name);
                return Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                        new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
                            if ("addView".equals(method.getName())) throw new WindowManager.BadTokenException();
                            return null;
                        });
            }
        };
        editor = new CaptureOverlayEditor(refused, draft, () -> {});
        assertFalse(editor.open());
        editor.close();
        assertTrue("Draft ownership must not transfer when attachment failed", draft.exists());
    }

    @Test public void serviceDestructionClosesWindowAndClearsSessionReference() throws Exception {
        editor.close();
        service.onServiceConnected();
        assertTrue(CaptureAccessibilityService.showOverlay(draft));
        editor = ReflectionHelpers.getField(service, "overlay");
        drain();
        assertTrue(CaptureAccessibilityService.hasOverlay());
        service.onDestroy();
        assertFalse(CaptureAccessibilityService.hasOverlay());
        assertFalse((Boolean) ReflectionHelpers.getField(editor, "attached"));
        assertFalse(draft.exists());
    }

    private void open() throws Exception {
        assertTrue(editor.open());
        drain();
        layout(390, 844);
    }

    @Test public void screenOffHidesWindowAndExplicitRestoreRetainsDraft() throws Exception {
        editor.close();
        service.onServiceConnected();
        assertTrue(CaptureAccessibilityService.showOverlay(draft));
        editor = ReflectionHelpers.getField(service, "overlay");
        drain();
        ((EditText) root().findViewById(R.id.capture_comment_input)).setText("锁屏前写的想法");
        service.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse((Boolean) ReflectionHelpers.getField(editor, "attached"));
        assertTrue(draft.exists());
        assertTrue(CaptureAccessibilityService.restoreOverlay());
        assertEquals("锁屏前写的想法",
                ((EditText) root().findViewById(R.id.capture_comment_input)).getText().toString());
    }

    @Test public void closingDuringSaveDoesNotDeleteDraftBeforeAtomicWriteCompletes() throws Exception {
        open();
        next();
        ((EditText) root().findViewById(R.id.capture_comment_input)).setText("正在保存时关闭服务");
        root().findViewById(R.id.capture_editor_save).performClick();
        ExecutorService writer = ReflectionHelpers.getField(editor, "writer");
        java.util.concurrent.Future<?> finished = writer.submit(() -> {});
        editor.close();
        finished.get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, CaptureStore.list(service, 10).size());
        assertFalse(draft.exists());
        assertEquals(1, closeCount);
    }

    @Test public void expandedLinkAndKeyboardSizedViewportKeepInputsReachable() throws Exception {
        open();
        next();
        root().findViewById(R.id.capture_url_toggle).performClick();
        ((EditText) root().findViewById(R.id.capture_source_url)).setText("https://m.weibo.cn/detail/123456789");
        layout(360, 480);
        for (int id : new int[]{R.id.capture_editor_save, R.id.capture_source_url, R.id.capture_comment_input}) {
            View view = root().findViewById(id);
            if(view instanceof EditText) {
                view.requestFocus();
                view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);
            }
            int[] location = new int[2];
            int[] rootLocation = new int[2];
            view.getLocationInWindow(location);
            root().getLocationInWindow(rootLocation);
            assertTrue(view.getHeight() > 0);
            assertTrue(location[1] >= rootLocation[1]);
            assertTrue(location[1] + view.getHeight() <= rootLocation[1] + root().getHeight());
        }
    }

    @Test public void largeTextComposerCanScrollWithinSmallKeyboardViewport() throws Exception {
        editor.close();
        RuntimeEnvironment.setFontScale(1.5f);
        try {
            editor = new CaptureOverlayEditor(service, draft, () -> {});
            open();
            next();
            root().findViewById(R.id.capture_url_toggle).performClick();
            layout(360, 400);
            View composer = root().findViewById(R.id.capture_composer);
            assertTrue(composer.getParent() instanceof ScrollView);
            ScrollView scroll = (ScrollView) composer.getParent();
            assertTrue(scroll.getHeight() > 48);
            scroll.setSmoothScrollingEnabled(false);
            View commentView = root().findViewById(R.id.capture_comment_input);
            commentView.requestFocus();
            commentView.requestRectangleOnScreen(new Rect(0,0,commentView.getWidth(),commentView.getHeight()),true);
            render("overlay-large-font-keyboard.png");
            Rect visible = new Rect();
            assertTrue("composer=" + composer.getHeight() + ", scroll=" + scroll.getHeight()
                    + ", offset=" + scroll.getScrollY() + ", commentTop=" + commentView.getTop(),
                    commentView.getGlobalVisibleRect(visible));
            assertTrue(visible.height() >= 40);
            assertTrue(root().findViewById(R.id.capture_editor_save).getGlobalVisibleRect(new Rect()));
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private View root() { return ReflectionHelpers.getField(editor, "root"); }

    @Test public void selectionControlsUseSmallTranslucentSurfacesWithoutPanel() throws Exception {
        open();
        View dock = ReflectionHelpers.getField(editor, "column");
        assertEquals(0, ((android.graphics.drawable.ColorDrawable) dock.getBackground()).getColor());
        assertEquals(0f, dock.getElevation(), 0f);
        assertTrue(dock.getHeight() <= 132);
        for (int id : new int[]{R.id.capture_editor_save, R.id.capture_overlay_minimize,
                R.id.capture_editor_cancel, R.id.capture_tool_select, R.id.capture_tool_pen, R.id.capture_tool_move}) {
            View control = root().findViewById(id);
            assertTrue(control.getBackground() instanceof android.graphics.drawable.RippleDrawable);
            android.graphics.drawable.RippleDrawable ripple = (android.graphics.drawable.RippleDrawable) control.getBackground();
            android.graphics.drawable.GradientDrawable fill = (android.graphics.drawable.GradientDrawable) ripple.getDrawable(0);
            assertTrue(Color.alpha(fill.getColor().getDefaultColor()) < 255);
            assertTrue(control.getWidth() >= 48);
            assertTrue(control.getHeight() >= 48);
        }
        android.widget.TextView pen = root().findViewById(R.id.capture_tool_pen);
        assertNotNull(pen.getCompoundDrawables()[1]);
        pen.performClick();
        assertTrue(pen.isSelected());
        assertFalse(root().findViewById(R.id.capture_tool_select).isSelected());
    }

    @Test public void imeInsetsLiftInputEvenWhenOverlayIsNotResizedAndDoNotDoubleLiftWhenResized() throws Exception {
        open(); next();
        root().findViewById(R.id.capture_comment_input).requestFocus();
        android.view.WindowInsets shown = new android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 320))
                .setVisible(android.view.WindowInsets.Type.ime(), true).build();
        root().dispatchApplyWindowInsets(shown);
        layout(390, 844);
        assertInputAbove(524);
        render("overlay-ime-visible.png");
        layout(390, 524);
        View dock = ReflectionHelpers.getField(editor, "column");
        assertTrue("IME must not be subtracted twice", dock.getHeight() > 400);
        assertInputAbove(524);
        root().dispatchApplyWindowInsets(new android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.NONE)
                .setVisible(android.view.WindowInsets.Type.ime(), false).build());
        layout(390, 844);
        assertTrue(dock.getHeight() > 500);
    }

    @Test public void compactToolbarKeepsActionsAccessibleOnSmallScreen() throws Exception {
        open(); layout(320,640);
        for (int id : new int[]{R.id.capture_editor_cancel,R.id.capture_overlay_minimize,R.id.capture_editor_save}) {
            Rect rect = new Rect(); View action = root().findViewById(id);
            assertTrue(action.getGlobalVisibleRect(rect));
            assertTrue(rect.width() >= 48); assertTrue(rect.height() >= 48);
            assertTrue(rect.right <= 320);
        }
        android.widget.HorizontalScrollView tools = root().findViewById(R.id.capture_tool_row);
        tools.scrollTo(1000,0);
        Rect moved = new Rect();
        assertTrue(root().findViewById(R.id.capture_tool_move).getGlobalVisibleRect(moved));
        assertTrue(moved.width() >= 48);
        render("overlay-small-screen.png");
    }

    @Test public void visibleFrameFallbackAlsoProtectsInputWithoutImeInsets() throws Exception {
        open(); next();
        root().findViewById(R.id.capture_comment_input).requestFocus();
        ReflectionHelpers.setField(editor, "visibleBottom", 524);
        // The global-layout listener requests layout when this cached bound changes.
        root().requestLayout();
        layout(390, 844);
        assertInputAbove(524);
    }

    private void assertInputAbove(int bottom) {
        View input = root().findViewById(R.id.capture_comment_input);
        Rect rect = new Rect();
        assertTrue(input.getGlobalVisibleRect(rect));
        assertTrue("input bottom=" + rect.bottom, rect.bottom <= bottom);
        assertTrue("visible="+rect+" inputHeight="+input.getHeight()+" scroll="
                +((android.widget.ScrollView)root().findViewById(R.id.capture_composer).getParent()).getScrollY()
                +" focus="+root().findFocus(),rect.height() >= 48);
        assertTrue(root().findViewById(R.id.capture_selection_preview).getGlobalVisibleRect(new Rect()));
        assertTrue(root().findViewById(R.id.capture_editor_save).getGlobalVisibleRect(new Rect()));
    }

    @Test public void failedLocalSaveToastsFailureAndKeepsEditableSession() throws Exception {
        open(); next();
        ((EditText) root().findViewById(R.id.capture_comment_input)).setText("不要丢掉这句话");
        // Simulate the system removing the temporary screenshot before the write.
        CaptureStore.discardDraft(service, draft);
        root().findViewById(R.id.capture_editor_save).performClick();
        drain();
        assertEquals(service.getString(R.string.capture_error_save_failed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertEquals(0, closeCount);
        assertTrue(root().findViewById(R.id.capture_editor_save).isEnabled());
        assertEquals("不要丢掉这句话", ((EditText) root().findViewById(R.id.capture_comment_input)).getText().toString());
        assertTrue(CaptureStore.list(service, 10).isEmpty());
    }

    private void next() {
        root().findViewById(R.id.capture_editor_save).performClick();
        layout(390, 844);
    }


    private void layout(int width, int height) {
        root().measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root().layout(0, 0, width, height);
    }

    private void drain() throws Exception {
        ExecutorService writer = ReflectionHelpers.getField(editor, "writer");
        writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void stroke(CaptureMarkupView markup) {
        for (int i = 0; i < 3; i++) {
            MotionEvent event = MotionEvent.obtain(0, i * 20L,
                    i == 0 ? MotionEvent.ACTION_DOWN : i == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP,
                    markup.getWidth() / 2f + i * 10, markup.getHeight() / 2f + i * 10, 0);
            markup.onTouchEvent(event);
            event.recycle();
        }
    }

    private void render(String name) throws Exception {
        File directory = new File("build/ui-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root().getWidth(), root().getHeight(), Bitmap.Config.ARGB_8888);
        root().draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
