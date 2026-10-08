package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",
        shadows={QuickNoteActivityTest.ServiceShadow.class,QuickNoteActivityTest.ClipboardShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class UnifiedCaptureActivityTest {
    @Before public void setup(){new QuickNoteActivityTest().reset();CaptureAccountSession.preferences(RuntimeEnvironment.getApplication()).edit().clear().commit();}
    static File screenshot(Context context)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffe4ddd0);
        try{return CaptureStore.writeDraftBitmap(context,bitmap);}finally{bitmap.recycle();}
    }
    static ActivityController<UnifiedCaptureActivity> open(File screenshot,CaptureSourceContext source)throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        ActivityController<UnifiedCaptureActivity> c=Robolectric.buildActivity(UnifiedCaptureActivity.class,
                screenshot==null?UnifiedCaptureActivity.forText(app):UnifiedCaptureActivity.forScreenshot(app,screenshot,source)).setup();
        c.get().onWindowFocusChanged(true);drain(c.get());return c;
    }
    static void drain(UnifiedCaptureActivity a)throws Exception {
        for(int i=0;i<3;i++){
            ExecutorService executor=ReflectionHelpers.getField(a,"executor");
            if(!executor.isShutdown())executor.submit(()->{}).get(10,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void checked(UnifiedCaptureActivity a,int id,boolean value){a.<CompoundButton>findViewById(id).setChecked(value);}
    private static EditText input(UnifiedCaptureActivity a,int id,String value){EditText field=a.findViewById(id);field.setText(value);return field;}
    private static CaptureStore.CaptureRecord save(UnifiedCaptureActivity a)throws Exception {
        a.findViewById(R.id.unified_save).performClick();drain(a);
        assertTrue(a.isFinishing());assertNotNull(ShadowToast.getTextOfLatestToast());return CaptureStore.list(a,10).get(0);
    }
    private static JSONObject cropLayer(int right,int bottom)throws Exception {
        return new JSONObject().put("sourceWidth",600).put("sourceHeight",900).put("coordinateSpace","source_bitmap_pixels")
                .put("selection",new JSONObject().put("left",10).put("top",20).put("right",right).put("bottom",bottom))
                .put("strokes",new JSONArray());
    }
    private static void crop(UnifiedCaptureActivity a)throws Exception {
        a.findViewById(R.id.unified_edit_image).performClick();
        a.<CaptureMarkupView>findViewById(R.id.unified_markup).restoreAnnotationLayer(cropLayer(210,320));
        a.findViewById(R.id.unified_crop_confirm).performClick();drain(a);
    }
    @Test public void screenshotStartsInRecordPageAndSavesOnlyContextWithoutInventedCrop()throws Exception {
        File file=screenshot(RuntimeEnvironment.getApplication());byte[] original=Files.readAllBytes(file.toPath());
        try(var c=open(file,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();
            assertEquals(View.GONE,a.findViewById(R.id.unified_canvas).getVisibility());
            assertTrue(a.<CompoundButton>findViewById(R.id.unified_context_toggle).isChecked());
            assertFalse(a.<CompoundButton>findViewById(R.id.unified_crop_toggle).isChecked());
            assertEquals(0,QuickNoteActivityTest.ClipboardShadow.reads);assertEquals(0,QuickNoteActivityTest.ServiceShadow.reads);
            CaptureStore.CaptureRecord record=save(a);assertFalse(record.hasImage);assertTrue(record.contextFile.isFile());
            assertArrayEquals(original,Files.readAllBytes(record.contextFile.toPath()));
            assertEquals("page_context",record.captureContext.getJSONObject("image").getString("purpose"));
            assertTrue(record.captureContext.getJSONObject("image").isNull("selected_asset_role"));
            assertEquals(0,QuickNoteActivityTest.ServiceShadow.captures);
        }
    }
    @Test public void uncheckedModulesKeepTheirDraftButNeverLeakIntoSavedRecord()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();input(a,R.id.unified_thought,"只保存这段想法");
            checked(a,R.id.unified_excerpt_toggle,true);input(a,R.id.unified_excerpt,"不应保存的摘录");checked(a,R.id.unified_excerpt_toggle,false);
            checked(a,R.id.unified_original_toggle,true);input(a,R.id.unified_original,"不应保存的原文");checked(a,R.id.unified_original_toggle,false);
            checked(a,R.id.unified_source_toggle,true);input(a,R.id.unified_url,"https://example.test/private");checked(a,R.id.unified_source_toggle,false);
            checked(a,R.id.unified_tags_toggle,true);input(a,R.id.capture_tags_input,"私人标签");checked(a,R.id.unified_tags_toggle,false);
            assertEquals("不应保存的摘录",a.<EditText>findViewById(R.id.unified_excerpt).getText().toString());
            CaptureStore.CaptureRecord record=save(a);assertEquals("只保存这段想法",record.comment);
            assertEquals("",record.sourceText);assertEquals("",record.sourceUrl);assertEquals("",record.sourcePackage);
            assertFalse(record.captureContext.has("text"));assertEquals(0,record.tags.length());
        }
    }
    @Test public void typingAndCheckingExcerptDoNotReadClipboardUntilExplicitButton()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();checked(a,R.id.unified_excerpt_toggle,true);
            assertEquals(0,QuickNoteActivityTest.ClipboardShadow.reads);
            a.findViewById(R.id.unified_clipboard).performClick();
            assertEquals(1,QuickNoteActivityTest.ClipboardShadow.reads);
            checked(a,R.id.unified_excerpt_toggle,false);checked(a,R.id.unified_excerpt_toggle,true);
            assertEquals(1,QuickNoteActivityTest.ClipboardShadow.reads);
            assertEquals(QuickNoteActivityTest.ClipboardShadow.value,a.<EditText>findViewById(R.id.unified_excerpt).getText().toString());
            CaptureStore.CaptureRecord record=save(a);assertEquals("clipboard",record.sourceType);assertEquals(QuickNoteActivityTest.ClipboardShadow.value,record.sourceText);
        }
    }
    @Test public void failedClipboardReadKeepsIndependentOriginalAndNeverCaptures()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();checked(a,R.id.unified_original_toggle,true);input(a,R.id.unified_original,"手写原文");
            checked(a,R.id.unified_excerpt_toggle,true);QuickNoteActivityTest.ClipboardShadow.fail=true;a.findViewById(R.id.unified_clipboard).performClick();
            assertEquals("手写原文",a.<EditText>findViewById(R.id.unified_original).getText().toString());
            assertEquals(0,QuickNoteActivityTest.ServiceShadow.captures);assertEquals("手写原文",CaptureRecordEdits.original(save(a)));
        }
    }
    @Test public void originalReadIsExplicitOffMainIndependentAndDoesNotLeakUncheckedSource()throws Exception {
        File file=screenshot(RuntimeEnvironment.getApplication());
        try(var c=open(file,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();checked(a,R.id.unified_original_toggle,true);QuickNoteActivityTest.ServiceShadow.ready=true;
            assertEquals(0,QuickNoteActivityTest.ServiceShadow.reads);a.findViewById(R.id.unified_read_page).performClick();
            assertEquals(View.GONE,a.findViewById(R.id.unified_root).getVisibility());assertEquals(1,a.getWindow().getAttributes().width);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            ReflectionHelpers.<ExecutorService>getField(a,"pageExecutor").submit(()->{}).get(5,TimeUnit.SECONDS);drain(a);
            assertEquals(View.VISIBLE,a.findViewById(R.id.unified_root).getVisibility());assertNotEquals(1,a.getWindow().getAttributes().width);
            assertFalse(QuickNoteActivityTest.ServiceShadow.readOnMain);assertEquals(1,QuickNoteActivityTest.ServiceShadow.reads);
            assertTrue(file.isFile());CaptureStore.CaptureRecord record=save(a);
            assertTrue(record.contextFile.isFile());assertEquals(QuickNoteActivityTest.ServiceShadow.page.text,CaptureRecordEdits.original(record));
            assertEquals("",record.sourceUrl);assertEquals("",record.sourcePackage);
            assertEquals("",record.captureContext.getJSONObject("text").getString("source_url"));
            assertEquals("",record.captureContext.getJSONObject("text").getString("source_package"));
        }
    }
    @Test public void cancellingPageReadRestoresWindowAndIgnoresLateResult()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();checked(a,R.id.unified_original_toggle,true);QuickNoteActivityTest.ServiceShadow.ready=true;
            a.findViewById(R.id.unified_read_page).performClick();a.onWindowFocusChanged(false);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12));
            assertEquals(0,QuickNoteActivityTest.ServiceShadow.reads);assertEquals(View.VISIBLE,a.findViewById(R.id.unified_root).getVisibility());
            assertNotEquals(1,a.getWindow().getAttributes().width);assertEquals("",a.<EditText>findViewById(R.id.unified_original).getText().toString());
        }
    }
    @Test public void blockedPageReadTimesOutWithoutBlockingSavingThought()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();input(a,R.id.unified_thought,"页面读取失败也要留下想法");checked(a,R.id.unified_original_toggle,true);
            QuickNoteActivityTest.ServiceShadow.ready=true;QuickNoteActivityTest.ServiceShadow.entered=new CountDownLatch(1);QuickNoteActivityTest.ServiceShadow.release=new CountDownLatch(1);
            a.findViewById(R.id.unified_read_page).performClick();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertTrue(QuickNoteActivityTest.ServiceShadow.entered.await(3,TimeUnit.SECONDS));
            try {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
                assertEquals(View.VISIBLE,a.findViewById(R.id.unified_root).getVisibility());
                CaptureStore.CaptureRecord record=save(a);assertEquals("页面读取失败也要留下想法",record.comment);assertFalse(record.captureContext.has("text"));
            } finally{QuickNoteActivityTest.ServiceShadow.release.countDown();}
            ReflectionHelpers.<ExecutorService>getField(a,"pageExecutor").submit(()->{}).get(5,TimeUnit.SECONDS);drain(a);
            assertEquals("",a.<EditText>findViewById(R.id.unified_original).getText().toString());
        }
    }
    @Test public void confirmedCropAndFullPageRemainSeparateAndCancelledReeditKeepsPreviousCrop()throws Exception {
        File file=screenshot(RuntimeEnvironment.getApplication());byte[] bytes=Files.readAllBytes(file.toPath());
        try(var c=open(file,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();crop(a);UnifiedCaptureDraft draft=ReflectionHelpers.getField(a,"draft");
            File previous=draft.cropAnnotated;String layer=draft.layer.toString();
            a.findViewById(R.id.unified_edit_image).performClick();
            CaptureMarkupView markup=a.findViewById(R.id.unified_markup);assertEquals(layer,markup.annotationLayer().toString());
            markup.selectWholeImage();a.findViewById(R.id.unified_crop_cancel).performClick();
            assertEquals(previous,draft.cropAnnotated);assertEquals(layer,draft.layer.toString());assertTrue(previous.isFile());
            CaptureStore.CaptureRecord record=save(a);assertTrue(record.hasImage);
            Bitmap savedCrop=CaptureStore.decodeEditorBitmap(record.originalFile);
            assertEquals(200,savedCrop.getWidth());assertEquals(300,savedCrop.getHeight());savedCrop.recycle();
            assertTrue(record.contextFile.isFile());assertArrayEquals(bytes,Files.readAllBytes(record.contextFile.toPath()));
            assertFalse("page_context".equals(record.captureContext.getJSONObject("image").optString("purpose")));
        }
    }
    @Test public void cropCanBeSavedWithoutFullPageAndFullPageCanBeSavedWithoutCrop()throws Exception {
        try(var c=open(screenshot(RuntimeEnvironment.getApplication()),CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();crop(a);checked(a,R.id.unified_context_toggle,false);
            CaptureStore.CaptureRecord record=save(a);assertTrue(record.hasImage);assertNull(record.contextFile);
            assertFalse(record.captureContext.getJSONObject("image").getBoolean("retained"));
        }
    }
    @Test public void allSelectedMaterialsCoexistAndSourceOptOutRedactsNestedProvenance()throws Exception {
        CaptureSourceContext source=new CaptureSourceContext("reader.app","https://example.test/page","browser_address_bar");
        try(var c=open(screenshot(RuntimeEnvironment.getApplication()),source)) {
            UnifiedCaptureActivity a=c.get();input(a,R.id.unified_thought,"我自己的理解");crop(a);
            checked(a,R.id.unified_excerpt_toggle,true);a.findViewById(R.id.unified_clipboard).performClick();
            checked(a,R.id.unified_original_toggle,true);input(a,R.id.unified_original,"手写原文，与图片独立");
            checked(a,R.id.unified_tags_toggle,true);input(a,R.id.capture_tags_input,"阅读");
            checked(a,R.id.unified_source_toggle,false);
            CaptureStore.CaptureRecord record=save(a);
            assertEquals("我自己的理解",record.comment);assertEquals("clipboard",record.sourceType);
            assertEquals(QuickNoteActivityTest.ClipboardShadow.value,record.sourceText);
            assertEquals("手写原文，与图片独立",CaptureRecordEdits.original(record));
            assertTrue(record.hasImage);assertTrue(record.contextFile.isFile());assertEquals(1,record.tags.length());
            assertEquals("",record.sourceUrl);assertEquals("",record.sourcePackage);
            assertEquals("",record.captureContext.getJSONObject("text").getString("source_url"));
            assertEquals("",record.captureContext.getJSONObject("text").getString("source_package"));
            assertEquals("user_entered",record.captureContext.getJSONObject("text").getString("origin"));
        }
    }
    @Test public void cancelledCropModuleRetainsConfirmedDraftButSavesNoCrop()throws Exception {
        try(var c=open(screenshot(RuntimeEnvironment.getApplication()),CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();crop(a);UnifiedCaptureDraft draft=ReflectionHelpers.getField(a,"draft");
            checked(a,R.id.unified_crop_toggle,false);assertTrue(draft.cropAnnotated.isFile());
            CaptureStore.CaptureRecord record=save(a);assertFalse(record.hasImage);assertTrue(record.contextFile.isFile());
            assertFalse(record.captureContext.getJSONObject("image").has("selection"));
        }
    }
    @Test public void longDraftAllModuleChoicesAndUnconfirmedCanvasSurviveRotation()throws Exception {
        try(var c=open(screenshot(RuntimeEnvironment.getApplication()),CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();String quote="摘录内容\n".repeat(12000),page="原文\n".repeat(9000);
            checked(a,R.id.unified_excerpt_toggle,true);input(a,R.id.unified_excerpt,quote);
            checked(a,R.id.unified_original_toggle,true);input(a,R.id.unified_original,page);
            checked(a,R.id.unified_tags_toggle,true);input(a,R.id.capture_tags_input,"阅读，行动");
            a.<RadioGroup>findViewById(R.id.unified_kind).check(R.id.unified_kind_todo);
            checked(a,R.id.unified_excerpt_toggle,false);a.findViewById(R.id.unified_edit_image).performClick();
            a.<CaptureMarkupView>findViewById(R.id.unified_markup).restoreAnnotationLayer(cropLayer(210,320));
            c.recreate();a=c.get();a.onWindowFocusChanged(true);drain(a);
            assertEquals(quote,a.<EditText>findViewById(R.id.unified_excerpt).getText().toString());
            assertEquals(page,a.<EditText>findViewById(R.id.unified_original).getText().toString());
            assertFalse(a.<CompoundButton>findViewById(R.id.unified_excerpt_toggle).isChecked());
            assertEquals(View.VISIBLE,a.findViewById(R.id.unified_canvas).getVisibility());
            assertEquals(210,a.<CaptureMarkupView>findViewById(R.id.unified_markup).annotationLayer().getJSONObject("selection").getInt("right"));
            a.findViewById(R.id.unified_crop_cancel).performClick();CaptureStore.CaptureRecord record=save(a);
            assertEquals("todo",record.kind);assertEquals("",record.sourceText);assertEquals(page,CaptureRecordEdits.original(record));assertEquals(2,record.tags.length());
            assertEquals(0,QuickNoteActivityTest.ClipboardShadow.reads);assertEquals(0,QuickNoteActivityTest.ServiceShadow.reads);
        }
    }
    @Test public void doubleTapAndRotationDuringSaveCommitOnlyOneRecord()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity first=c.get();input(first,R.id.unified_thought,"只保存一次");
            ExecutorService worker=ReflectionHelpers.getField(first,"executor");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            worker.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try {
                first.findViewById(R.id.unified_save).performClick();first.findViewById(R.id.unified_save).performClick();
                c.recreate();c.get().findViewById(R.id.unified_save).performClick();
            } finally{release.countDown();}
            assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));drain(c.get());
            assertEquals(1,CaptureStore.list(c.get(),10).size());assertEquals("只保存一次",CaptureStore.list(c.get(),10).get(0).comment);assertTrue(c.get().isFinishing());
        }
    }
    @Test public void changedAccountCannotReadOrSavePriorDraft()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();input(a,R.id.unified_thought,"原账号草稿");
            CaptureAccountSession.preferences(a).edit().putString("scope","a".repeat(64)).commit();
            a.findViewById(R.id.unified_save).performClick();assertEquals(0,CaptureStore.list(a,10).size());
            assertFalse(UnifiedCaptureActivity.resumeExisting(a));assertFalse(a.isFinishing());
        }
    }
    @Test public void resumeExistingPreservesDraftAndNeverStartsAnotherScreenshot()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            input(c.get(),R.id.unified_thought,"正在编辑，不要覆盖");assertTrue(UnifiedCaptureActivity.resumeExisting(c.get()));
            assertEquals("正在编辑，不要覆盖",c.get().<EditText>findViewById(R.id.unified_thought).getText().toString());
            assertEquals(0,QuickNoteActivityTest.ServiceShadow.captures);
        }
    }
    @Test public void saveAndChatOpensConversationOnlyAfterDurableSave()throws Exception {
        try(var c=open(null,CaptureSourceContext.EMPTY)) {
            UnifiedCaptureActivity a=c.get();input(a,R.id.unified_thought,"保存后再聊");
            a.findViewById(R.id.unified_save_chat).performClick();drain(a);
            assertEquals(1,CaptureStore.list(a,10).size());android.content.Intent intent=shadowOf(a).getNextStartedActivity();
            assertEquals(AiChatActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals(CaptureStore.list(a,10).get(0).id,intent.getStringExtra(AiUi.RECORD));
        }
    }
}
