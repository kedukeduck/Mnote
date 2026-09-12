package com.codex.mnote;

import android.app.Dialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},qualifiers="zh-rCN-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureLongTextTest {
    @Test public void expandedEditorCanReachEndAndKeepsEveryCharacterAcrossRotation() {
        try(var c=Robolectric.buildActivity(QuickNoteActivity.class).setup()) {
            QuickNoteActivity a=c.get();
            EditText source=a.findViewById(R.id.quick_note_original);
            String article="每一段都应该可以查看和编辑。\n".repeat(800)+"最后一段-END";
            source.setText(article);
            CaptureLongText.show(a,source,"页面原文");
            Dialog dialog=ShadowDialog.getLatestDialog();
            View root=dialog.getWindow().getDecorView();layout(root,390,400);
            EditText expanded=dialog.findViewById(R.id.capture_expanded_text);
            assertEquals(article,expanded.getText().toString());assertTrue(expanded.canScrollVertically(1));
            assertTrue(expanded.getHeight()>100);assertTrue(expanded.getHeight()<400);
            expanded.requestFocus();expanded.setSelection(expanded.length());
            expanded.bringPointIntoView(expanded.length());shadowOf(Looper.getMainLooper()).idle();
            assertTrue(expanded.getScrollY()>0);
            expanded.append("\n补充内容");assertEquals(article+"\n补充内容",source.getText().toString());
            c.recreate();
            assertEquals(article+"\n补充内容",c.get().<EditText>findViewById(R.id.quick_note_original).getText().toString());
            assertFalse(dialog.isShowing());
        }
    }
    @Test public void nestedTextScrollKeepsGestureUntilItsBoundaryThenReleasesParent() {
        var activity=Robolectric.buildActivity(QuickNoteActivity.class).setup();
        try {
            final boolean[] disallowed={false};
            LinearLayout parent=new LinearLayout(activity.get()) {
                @Override public void requestDisallowInterceptTouchEvent(boolean value) {disallowed[0]=value;super.requestDisallowInterceptTouchEvent(value);}
            };
            EditText input=new EditText(activity.get());input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setText("原文段落\n".repeat(200));CaptureLongText.enableScrolling(input);
            parent.addView(input,new LinearLayout.LayoutParams(300,150));layout(parent,320,200);
            MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,100,100,0);
            input.dispatchTouchEvent(down);assertTrue(disallowed[0]);down.recycle();
            MotionEvent cancel=MotionEvent.obtain(0,10,MotionEvent.ACTION_CANCEL,100,100,0);
            input.dispatchTouchEvent(cancel);assertFalse(disallowed[0]);cancel.recycle();
            assertEquals("原文段落\n".repeat(200),input.getText().toString());
        } finally {activity.close();}
    }
    private static void layout(View root,int w,int h) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
        root.layout(0,0,w,h);
    }
}
