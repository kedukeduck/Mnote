package com.codex.mnote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",
        shadows={CaptureAccountTest.Scheduler.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureRecordEditActivityTest {
    Context context;CaptureStore.CaptureRecord record;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        record=CaptureStore.save(context,null,null,null,null,"thought","好的记录，也留下当时的自己。","share_text","值得思考的内容","",
                "","",false,CaptureContext.text("读到值得思考的内容时，写下自己的判断。","shared_text","值得思考的内容"));
    }
    private ActivityController<CaptureRecordEditActivity> open() throws Exception {
        Intent intent=new Intent(context,CaptureRecordEditActivity.class).putExtra(CaptureRecordEditActivity.ID,record.id)
                .putExtra(CaptureRecordEditActivity.SCOPE,"guest");
        ActivityController<CaptureRecordEditActivity> controller=Robolectric.buildActivity(CaptureRecordEditActivity.class,intent).setup();
        drain(controller.get());return controller;
    }
    private static void drain(CaptureRecordEditActivity activity) throws Exception {
        ReflectionHelpers.<ExecutorService>getField(activity,"executor").submit(()->{}).get(10,TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void editingAllThreeFieldsUpdatesRecordAndReturnsSavedIdentity() throws Exception {
        try(ActivityController<CaptureRecordEditActivity> controller=open()) {
            CaptureRecordEditActivity activity=controller.get();
            assertEquals(record.comment,activity.<EditText>findViewById(R.id.record_edit_comment).getText().toString());
            render(layout(activity,390,844),"record-edit.png");
            activity.<EditText>findViewById(R.id.record_edit_comment).setText("新的判断");
            activity.<EditText>findViewById(R.id.record_edit_quote).setText("新的摘录");
            activity.<EditText>findViewById(R.id.record_edit_original).setText("这里是新的摘录及其上下文");
            activity.<EditText>findViewById(R.id.capture_tags_input).setText("灵感，工作");
            activity.findViewById(R.id.record_edit_save).performClick();drain(activity);
            assertTrue(activity.isFinishing());assertEquals(Activity.RESULT_OK,shadowOf(activity).getResultCode());
            assertEquals(record.id,shadowOf(activity).getResultIntent().getStringExtra(CaptureRecordEditActivity.ID));
            CaptureStore.CaptureRecord updated=CaptureStore.find(context,record.id);
            assertEquals("新的判断",updated.comment);assertEquals("新的摘录",updated.sourceText);
            assertEquals("这里是新的摘录及其上下文",CaptureRecordEdits.original(updated));
            assertEquals("灵感，工作",CaptureTags.input(updated.tags));
            assertEquals("修改已保存",org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            assertEquals(1,CaptureStore.list(context,10).size());
        }
    }
    @Test public void cancelRequiresConfirmationAndNeverChangesOriginal() throws Exception {
        try(ActivityController<CaptureRecordEditActivity> controller=open()) {
            CaptureRecordEditActivity activity=controller.get();
            activity.<EditText>findViewById(R.id.record_edit_comment).setText("未保存");
            activity.findViewById(R.id.record_edit_cancel).performClick();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
            assertFalse(activity.isFinishing());assertEquals("未保存",activity.<EditText>findViewById(R.id.record_edit_comment).getText().toString());
            activity.onBackPressed();dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.isFinishing());assertEquals(record.comment,CaptureStore.find(context,record.id).comment);
        }
    }
    @Test public void rotationKeepsUnsavedInputAndBaseline() throws Exception {
        try(ActivityController<CaptureRecordEditActivity> controller=open()) {
            controller.get().<EditText>findViewById(R.id.record_edit_comment).setText("旋转前输入");
            controller.get().<EditText>findViewById(R.id.record_edit_original).setText("新的原文");
            controller.get().<EditText>findViewById(R.id.capture_tags_input).setText("旋转前标签");
            controller.recreate();drain(controller.get());
            CaptureRecordEditActivity activity=controller.get();
            assertEquals("旋转前输入",activity.<EditText>findViewById(R.id.record_edit_comment).getText().toString());
            assertEquals("新的原文",activity.<EditText>findViewById(R.id.record_edit_original).getText().toString());
            assertEquals("旋转前标签",activity.<EditText>findViewById(R.id.capture_tags_input).getText().toString());
            activity.findViewById(R.id.record_edit_save).performClick();drain(activity);
            assertEquals("旋转前输入",CaptureStore.find(context,record.id).comment);
        }
    }
    @Test public void failedSaveKeepsEditableInputAndShowsError() throws Exception {
        try(ActivityController<CaptureRecordEditActivity> controller=open()) {
            CaptureRecordEditActivity activity=controller.get();
            activity.<EditText>findViewById(R.id.record_edit_comment).setText("正在写的修改");
            CaptureRecordEdits.save(context,"guest",record.id,CaptureRecordEdits.fingerprint(record),"已有新修改",record.sourceText,CaptureRecordEdits.original(record));
            activity.findViewById(R.id.record_edit_save).performClick();drain(activity);
            assertFalse(activity.isFinishing());assertTrue(activity.findViewById(R.id.record_edit_save).isEnabled());
            assertTrue(activity.<TextView>findViewById(R.id.record_edit_status).getText().toString().contains("已有新修改"));
            assertEquals("正在写的修改",activity.<EditText>findViewById(R.id.record_edit_comment).getText().toString());
            assertEquals("已有新修改",CaptureStore.find(context,record.id).comment);
        }
    }
    @Test public void recreationAfterRecordDeletionStillKeepsUnsavedInputAvailableToCopy() throws Exception {
        try(ActivityController<CaptureRecordEditActivity> controller=open()) {
            controller.get().<EditText>findViewById(R.id.record_edit_comment).setText("不要丢失的输入");
            CaptureDeletionStore.delete(context,record.id);
            controller.recreate();drain(controller.get());
            assertEquals("不要丢失的输入",controller.get().<EditText>findViewById(R.id.record_edit_comment).getText().toString());
            assertTrue(controller.get().findViewById(R.id.record_edit_comment).isEnabled());
            assertTrue(controller.get().<TextView>findViewById(R.id.record_edit_status).getText().toString().contains("已删除"));
        }
    }
    @Test public void largeTextAndKeyboardViewportCanReachEveryFieldAndSave() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        try(ActivityController<CaptureRecordEditActivity> controller=open()) {
            View root=layout(controller.get(),360,400);
            for(int id:new int[]{R.id.record_edit_comment,R.id.record_edit_quote,R.id.record_edit_original}) {
                View input=root.findViewById(id);input.requestFocus();
                input.requestRectangleOnScreen(new Rect(0,0,input.getWidth(),input.getHeight()),true);
                Rect visible=new Rect();assertTrue(input.getGlobalVisibleRect(visible));
                assertTrue(visible.height()>=100);assertTrue(visible.bottom<=400);
                assertTrue(root.findViewById(R.id.record_edit_save).getGlobalVisibleRect(new Rect()));
            }
            render(root,"record-edit-keyboard.png");
        } finally {RuntimeEnvironment.setFontScale(1f);}
    }
    @Test public void detailEditEntryOpensNonExportedEditorWithScopedRecordId() throws Exception {
        try(ActivityController<CaptureInboxActivity> controller=Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            controller.get().<LinearLayout>findViewById(R.id.capture_records).getChildAt(0).performClick();
            AlertDialog detail=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            detail.findViewById(R.id.record_edit_open).performClick();
            Intent intent=shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(CaptureRecordEditActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals(record.id,intent.getStringExtra(CaptureRecordEditActivity.ID));
            assertEquals("guest",intent.getStringExtra(CaptureRecordEditActivity.SCOPE));
            assertFalse(detail.isShowing());
            assertFalse(context.getPackageManager().getActivityInfo(intent.getComponent(),0).exported);
        }
    }
    private static View layout(Activity activity,int width,int height) {
        View root=activity.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);return root;
    }
    private static void render(View root,String name) throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        File directory=new File("build/ui-previews");directory.mkdirs();
        try(FileOutputStream output=new FileOutputStream(new File(directory,name))) {bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}
        bitmap.recycle();
    }
}
