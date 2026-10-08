package com.codex.mnote;

import android.graphics.*;
import android.view.View;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

/** Actual native UI render, only synthetic page/text; no accessibility or clipboard reads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",
        shadows={QuickNoteActivityTest.ServiceShadow.class,QuickNoteActivityTest.ClipboardShadow.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class UnifiedCaptureVisualTest {
    @Test public void expandingTagsDoesNotJumpBackToPreviouslyFocusedThought()throws Exception {
        new QuickNoteActivityTest().reset();
        try(var c=UnifiedCaptureActivityTest.open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();
            EditText thought=a.findViewById(R.id.unified_thought);
            thought.requestFocus();
            layout(a,844);
            ScrollView scroll=a.findViewById(R.id.unified_scroll);
            scroll.setSmoothScrollingEnabled(false);
            scroll.fullScroll(View.FOCUS_DOWN);
            // Touching a checkbox does not transfer focus away from an EditText.
            thought.requestFocus();layout(a,844);
            scroll.scrollTo(0,scroll.getChildAt(0).getHeight()-scroll.getHeight());
            int before=scroll.getScrollY();assertTrue(before>0);
            a.<CompoundButton>findViewById(R.id.unified_tags_toggle).performClick();
            layout(a,844);
            assertEquals("Expanding tags must not reveal an off-screen thought",before,scroll.getScrollY());
            assertTrue(a.<CompoundButton>findViewById(R.id.unified_tags_toggle).isChecked());
        }
    }

    @Test public void openingAndSelectingExistingTagsKeepsThePickerInPlace()throws Exception {
        new QuickNoteActivityTest().reset();
        CaptureStore.save(RuntimeEnvironment.getApplication(),null,null,null,null,"thought","fixture","quick_note","","","","",false,null,
                CaptureTags.parse("阅读，工作，灵感"));
        try(var c=UnifiedCaptureActivityTest.open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();
            a.<CompoundButton>findViewById(R.id.unified_tags_toggle).setChecked(true);
            EditText thought=a.findViewById(R.id.unified_thought);thought.requestFocus();
            layout(a,844);
            ScrollView scroll=a.findViewById(R.id.unified_scroll);scroll.setSmoothScrollingEnabled(false);
            scroll.scrollTo(0,scroll.getChildAt(0).getHeight()-scroll.getHeight());
            int before=scroll.getScrollY();
            a.findViewById(R.id.capture_tags_choose).performClick();
            layout(a,844);
            assertEquals("Opening the picker must preserve the viewport",before,scroll.getScrollY());
            CaptureTags.TAG_WORKER.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
            layout(a,844);
            assertEquals("Loading existing tags must not reveal stale input focus",before,scroll.getScrollY());
            LinearLayout options=a.findViewById(R.id.capture_tags_options);assertEquals(3,options.getChildCount());
            Rect target=new Rect();options.getChildAt(0).getDrawingRect(target);
            scroll.offsetDescendantRectToMyCoords(options.getChildAt(0),target);
            scroll.scrollTo(0,target.top-80);before=scroll.getScrollY();
            options.getChildAt(0).performClick();layout(a,844);
            assertEquals("Selecting an existing tag must leave the picker visible",before,scroll.getScrollY());
            assertEquals(1,CaptureTags.parse(a.<EditText>findViewById(R.id.capture_tags_input).getText().toString()).length());
        }
    }

    private static void layout(UnifiedCaptureActivity a,int height) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        View view=a.getWindow().getDecorView();
        view.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,390,height);
    }

    @Test public void recordCanvasAndKeyboardSizedLongTextScreens()throws Exception {
        new QuickNoteActivityTest().reset();
        Bitmap page=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(page);canvas.drawColor(0xfff4f0e7);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff24494e);paint.setTextSize(34);canvas.drawText("A moment worth keeping",40,80,paint);
        paint.setColor(0xffc7d0c0);canvas.drawRect(40,130,560,450,paint);paint.setColor(0xff748d80);canvas.drawCircle(430,280,110,paint);
        paint.setColor(0xff333b39);paint.setTextSize(22);canvas.drawText("Read. Notice. Think again.",40,520,paint);
        File screenshot=CaptureStore.writeDraftBitmap(RuntimeEnvironment.getApplication(),page);page.recycle();
        try(var c=UnifiedCaptureActivityTest.open(screenshot,new CaptureSourceContext("reader.app","https://example.test/reading","browser_address_bar"))) {
            UnifiedCaptureActivity a=c.get();a.<EditText>findViewById(R.id.unified_thought).setText("不只记下看到了什么，也留下这一刻为什么被触动。");
            frame(a.getWindow().getDecorView(),390,844,"unified-capture-record");
            a.findViewById(R.id.unified_edit_image).performClick();frame(a.getWindow().getDecorView(),390,844,"unified-capture-canvas");
            a.findViewById(R.id.unified_crop_cancel).performClick();
            a.<CompoundButton>findViewById(R.id.unified_original_toggle).setChecked(true);
            EditText original=a.findViewById(R.id.unified_original);original.setText("页面原文保留完整内容，可以独立滚动与展开编辑。\n".repeat(600));
            original.requestFocus();original.setSelection(original.length());
            frame(a.getWindow().getDecorView(),390,500,"unified-capture-keyboard");
            assertTrue(original.getMaxLines()<=5);assertTrue(original.length()>10000);
            View save=a.findViewById(R.id.unified_save);int[] location=new int[2];save.getLocationOnScreen(location);
            assertTrue("Save remains above keyboard",location[1]+save.getHeight()<=500);
            Rect visible=new Rect();assertTrue("Focused original must be visible",original.getGlobalVisibleRect(visible));
            int[] inputLocation=new int[2];original.getLocationOnScreen(inputLocation);
            int line=original.getLayout().getLineForOffset(original.getSelectionEnd());
            int caretTop=inputLocation[1]+original.getTotalPaddingTop()+original.getLayout().getLineTop(line)-original.getScrollY();
            int caretBottom=inputLocation[1]+original.getTotalPaddingTop()+original.getLayout().getLineBottom(line)-original.getScrollY();
            assertTrue("Caret must remain inside the visible input",caretTop>=visible.top&&caretBottom<=visible.bottom);
            assertTrue("Visible article has room to edit",visible.height()>=40);
            assertEquals(0,QuickNoteActivityTest.ServiceShadow.reads);assertEquals(0,QuickNoteActivityTest.ClipboardShadow.reads);
        }
        RuntimeEnvironment.setFontScale(1.5f);
        try(var c=UnifiedCaptureActivityTest.open(null,CaptureSourceContext.EMPTY)) {
            frame(c.get().getWindow().getDecorView(),320,500,"unified-capture-large-text");
            Button save=c.get().findViewById(R.id.unified_save);
            assertTrue(save.getLayout().getHeight()<=save.getHeight()-save.getCompoundPaddingTop()-save.getCompoundPaddingBottom());
        } finally{RuntimeEnvironment.setFontScale(1f);}
    }
    private static void frame(View view,int width,int height,String name)throws Exception {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        File directory=new File("build/ui-previews");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
    }
}
