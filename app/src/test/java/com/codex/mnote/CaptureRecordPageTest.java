package com.codex.mnote;

import android.app.AlertDialog;
import android.graphics.*;
import android.view.View;
import android.view.WindowManager;
import android.widget.RadioGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureRecordPageTest {
    @org.junit.Before public void installSignaturePermission() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    }
    @Test public void readingPageSwitchesSavedContextWithoutRecyclingImagesStillUsedByRenderThread() throws Exception {
        CaptureContextTest fixture=new CaptureContextTest();fixture.setup();
        CaptureStore.CaptureRecord record=fixture.screenshot(true);
        String metadata=record.captureContext.toString();
        Bitmap crop=CaptureStore.decodeReviewBitmap(record.annotatedFile);
        Bitmap full=CaptureStore.decodeReviewBitmap(record.contextFile);
        try(ActivityController<CaptureInboxActivity> controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            AlertDialog dialog=CaptureRecordPage.show(controller.get(),record,crop,full,
                    ()->fail("Close must not delete"),url->fail("No URL action expected"));
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT,dialog.getWindow().getAttributes().height);
            layout(dialog,390,844);
            CaptureContextPreview preview=dialog.findViewById(R.id.capture_selection_preview);
            assertEquals(320,preview.getHeight());
            assertTrue(ReflectionHelpers.<RectF>getField(preview,"selection").isEmpty());
            dialog.findViewById(R.id.capture_preview_full).performClick();
            assertSame(full,((android.graphics.drawable.BitmapDrawable)preview.getDrawable()).getBitmap());
            assertFalse(ReflectionHelpers.<RectF>getField(preview,"selection").isEmpty());
            preview.performClick(); layout(dialog,390,844);
            assertTrue(preview.getHeight()>320);
            dialog.findViewById(R.id.capture_preview_crop).performClick();
            assertTrue(ReflectionHelpers.<RectF>getField(preview,"selection").isEmpty());
            assertEquals(metadata,record.captureContext.toString());
            dialog.findViewById(R.id.capture_review_close).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(crop.isRecycled());assertFalse(full.isRecycled());
            // A retained display list must still be safe to draw after dismiss.
            Bitmap frame=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);
            preview.draw(new Canvas(frame));frame.recycle();
            assertNotNull(CaptureStore.readRecord(record.metadataFile.getParentFile()));
        }
    }

    @Test public void missingFullScreenshotIsDisabledAndDeleteIsExplicit() throws Exception {
        CaptureContextTest fixture=new CaptureContextTest();fixture.setup();
        CaptureStore.CaptureRecord record=fixture.screenshot(false);
        Bitmap crop=CaptureStore.decodeReviewBitmap(record.annotatedFile);
        int[] requests={0};
        try(ActivityController<CaptureInboxActivity> controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            AlertDialog dialog=CaptureRecordPage.show(controller.get(),record,crop,null,
                    ()->requests[0]++,url->fail("No source URL"));
            assertFalse(dialog.findViewById(R.id.capture_preview_full).isEnabled());
            assertEquals(R.id.capture_preview_crop,dialog.<RadioGroup>findViewById(R.id.capture_preview_modes).getCheckedRadioButtonId());
            dialog.findViewById(R.id.capture_review_delete).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1,requests[0]); assertFalse(dialog.isShowing()); assertFalse(crop.isRecycled());
            // The page requests confirmation; it does not mutate the store itself.
            assertNotNull(CaptureStore.readRecord(record.metadataFile.getParentFile()));
        }
    }

    private static void layout(AlertDialog dialog,int width,int height) {
        View root=dialog.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
    }

    @Test public void retainedOriginalIsStillReadableAfterExcerptIsCleared() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        CaptureStore.CaptureRecord record=CaptureStore.save(context,null,null,null,null,"thought","我的想法","share_text","","",
                "","",false,CaptureContext.text("仍然保留的原文","user_edited",""));
        try(ActivityController<CaptureInboxActivity> controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            AlertDialog dialog=CaptureRecordPage.show(controller.get(),record,null,null,()->{},url->{});
            RadioGroup modes=dialog.findViewById(R.id.capture_text_preview_modes);
            assertNotNull(modes);assertEquals(R.id.capture_text_preview_original,modes.getCheckedRadioButtonId());
            dialog.dismiss();
        }
    }
}
