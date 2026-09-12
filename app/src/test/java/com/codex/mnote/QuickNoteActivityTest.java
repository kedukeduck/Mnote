package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},shadows={QuickNoteActivityTest.ServiceShadow.class,QuickNoteActivityTest.ClipboardShadow.class},instrumentedPackages="com.codex.mnote")
@LooperMode(LooperMode.Mode.PAUSED)
public class QuickNoteActivityTest {
    private static QuickNoteActivity current;
    @Before public void reset() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        ServiceShadow.ready=false;ServiceShadow.reads=0;ServiceShadow.captures=0;ServiceShadow.callback=null;
        ServiceShadow.page=new QuickNotePageContext(new CaptureSourceContext("reader.app","https://example.com/page","browser_address_bar"),42,"前文 剪贴板摘录 后文","");
        ClipboardShadow.reads=0;ClipboardShadow.value="  剪贴板摘录  ";ClipboardShadow.fail=false;
        current=null;
        ServiceShadow.entered=null;ServiceShadow.release=null;ServiceShadow.failRead=false;ServiceShadow.readOnMain=false;
    }
    private ActivityController<QuickNoteActivity> note() {
        ActivityController<QuickNoteActivity> result=Robolectric.buildActivity(QuickNoteActivity.class,
                QuickNoteTileService.noteIntent(RuntimeEnvironment.getApplication())).setup();
        current=result.get(); result.get().onWindowFocusChanged(true);return result;
    }
    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
        // Complete background page queries, then deliver their main-thread results.
        for(int i=0;i<3;i++) {
            if(current!=null) {
                ExecutorService reader=ReflectionHelpers.getField(current,"pageExecutor");
                if(!reader.isShutdown()) try {reader.submit(()->{}).get(5,TimeUnit.SECONDS);}
                catch(Exception error) {throw new AssertionError(error);}
            }
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void optIn(QuickNoteActivity a) {a.<CompoundButton>findViewById(R.id.quick_note_clipboard).setChecked(true);}
    private static void thought(QuickNoteActivity a) {a.<EditText>findViewById(R.id.capture_comment_input).setText("我的独立想法");}
    private static CaptureStore.CaptureRecord save(QuickNoteActivity a) throws Exception {
        a.findViewById(R.id.capture_editor_save).performClick();
        ReflectionHelpers.<ExecutorService>getField(a,"executor").submit(()->{}).get(5,TimeUnit.SECONDS);idle(0);
        return CaptureStore.list(a,10).get(0);
    }
    private static File screenshot(QuickNoteActivity a) throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(300,500,Bitmap.Config.ARGB_8888);
        try{return CaptureStore.writeDraftBitmap(a,bitmap);}finally{bitmap.recycle();}
    }
    @Test public void pureNoteDoesNotReadClipboardOrPageAndSavesTodoWithoutImages() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);
            a.<EditText>findViewById(R.id.capture_tags_input).setText("灵感，待办");
            a.<RadioGroup>findViewById(R.id.capture_kind_group).check(R.id.capture_kind_todo);
            assertFalse(a.<CompoundButton>findViewById(R.id.quick_note_clipboard).isChecked());
            assertEquals(View.GONE,a.findViewById(R.id.quick_note_material).getVisibility());
            CaptureStore.CaptureRecord record=save(a);
            assertEquals("todo",record.kind);assertEquals("quick_note",record.sourceType);assertEquals("我的独立想法",record.comment);
            assertEquals("灵感，待办",CaptureTags.input(record.tags));
            assertEquals("",record.sourceText);assertEquals("",record.sourcePackage);assertFalse(record.hasImage);
            assertEquals(0,ClipboardShadow.reads);assertEquals(0,ServiceShadow.reads);assertEquals(0,ServiceShadow.captures);
            assertTrue(a.isFinishing());assertNotNull(ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void explicitClipboardReadHappensOnceAndPersistsSeparateExcerpt() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);
            a.onWindowFocusChanged(false);a.onWindowFocusChanged(true);idle(500);
            assertEquals(1,ClipboardShadow.reads);assertEquals(0,ServiceShadow.reads);
            CaptureStore.CaptureRecord record=save(a);
            assertEquals("clipboard",record.sourceType);assertEquals(ClipboardShadow.value,record.sourceText);
            assertEquals("L3",record.fidelityLevel);assertEquals("我的独立想法",record.comment);assertFalse(record.hasImage);
            assertFalse(record.captureContext.has("text"));assertFalse(record.captureContext.has("image"));
        }
    }
    @Test public void clipboardFailureLeavesThoughtAndDoesNotFallbackToScreenshot() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);ClipboardShadow.fail=true;optIn(a);
            assertFalse(a.<CompoundButton>findViewById(R.id.quick_note_clipboard).isChecked());
            assertEquals("我的独立想法",a.<EditText>findViewById(R.id.capture_comment_input).getText().toString());
            assertEquals(0,ServiceShadow.captures);assertNotNull(ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void contextRequiresAccessibilityButNotClipboardOptIn() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();a.findViewById(R.id.quick_note_read_page).performClick();assertEquals(0,ServiceShadow.reads);
            optIn(a);a.findViewById(R.id.quick_note_read_page).performClick();assertEquals(0,ServiceShadow.reads);
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
            assertEquals(1,ClipboardShadow.reads);assertEquals(0,ServiceShadow.captures);
        }
    }
    @Test public void textContextReadsOnlyAfterExplicitTapAndExposesOnlyOnePixelBridge() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_read_page).performClick();
            WindowManager.LayoutParams p=a.getWindow().getAttributes();
            assertEquals(1,p.width);assertEquals(1,p.height);assertTrue((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
            assertEquals(View.GONE,a.findViewById(R.id.quick_note_root).getVisibility());assertEquals(0,ServiceShadow.reads);
            idle(500);assertEquals(1,ServiceShadow.reads);assertEquals(0,ServiceShadow.captures);
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());assertNotEquals(1,a.getWindow().getAttributes().width);
            a.<EditText>findViewById(R.id.quick_note_original).setText("编辑后的原文");
            CaptureStore.CaptureRecord record=save(a);
            assertEquals("编辑后的原文",record.captureContext.getJSONObject("text").getString("full_text"));
            assertEquals("unverified",record.captureContext.getJSONObject("text").getString("relation_to_quote"));
            assertEquals("not_found",record.captureContext.getJSONObject("text").getString("match"));
            assertEquals(ClipboardShadow.value,record.sourceText);assertFalse(record.hasImage);
            CaptureStore.CaptureRecord edited=CaptureRecordEdits.save(a,CaptureAccountSession.scope(a),record.id,
                    CaptureRecordEdits.fingerprint(record),record.comment,"新摘录","用户更新的原文");
            assertEquals("unverified",edited.captureContext.getJSONObject("text").getString("relation_to_quote"));
            assertEquals("reader.app",edited.captureContext.getJSONObject("text").getString("source_package"));
        }
    }
    @Test public void pageFailureRetainsThoughtAndExcerptAndNeverAutomaticallyCaptures() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);ServiceShadow.ready=true;
            ServiceShadow.page=QuickNotePageContext.failure("此页面未提供文字");
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            assertEquals(0,ServiceShadow.captures);assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
            assertEquals(ClipboardShadow.value,a.<EditText>findViewById(R.id.quick_note_quote).getText().toString());
            assertEquals("我的独立想法",a.<EditText>findViewById(R.id.capture_comment_input).getText().toString());
        }
    }
    @Test public void screenshotIsFullContextNotAnInferredQuoteLocation() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            assertEquals(1,ServiceShadow.captures);assertEquals(View.GONE,a.findViewById(R.id.quick_note_root).getVisibility());
            File draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_image).getVisibility());
            CaptureStore.CaptureRecord record=save(a);
            assertTrue(record.hasImage);assertFalse(draft.exists());assertTrue(record.contextFile.isFile());
            assertEquals("page_context",record.captureContext.getJSONObject("image").getString("purpose"));
            assertEquals("full_viewport_not_quote_location",record.captureContext.getJSONObject("image").getString("selection_meaning"));
            assertEquals(ClipboardShadow.value,record.sourceText);assertEquals(500,record.captureContext.getJSONObject("image").getInt("height"));
            File payload=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"createPayload",
                    ReflectionHelpers.ClassParameter.from(Context.class,a),ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,record));
            org.json.JSONObject canonical=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(payload.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("clipboard",canonical.getJSONObject("source").getString("type"));
            assertEquals(ClipboardShadow.value,canonical.getJSONObject("evidence").getJSONObject("exact_text").getString("text"));
            assertEquals("page_context",canonical.getJSONObject("evidence").getJSONObject("context").getJSONObject("image").getString("purpose"));
            assertEquals(3,canonical.getJSONObject("assets").length());assertTrue(payload.delete());
        }
    }
    @Test public void changedSourceWindowDiscardsLateScreenshot() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            File draft=screenshot(a);ServiceShadow.page=QuickNotePageContext.failure("Mnote窗口");
            ServiceShadow.callback.onCaptured(draft);idle(0);
            assertFalse(draft.exists());assertEquals(View.GONE,a.findViewById(R.id.quick_note_image).getVisibility());
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
        }
    }
    @Test public void losingFocusCancelsAndDiscardsPendingScreenshot() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            a.onWindowFocusChanged(false);File draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);
            assertFalse(draft.exists());assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
            assertFalse(ReflectionHelpers.<Boolean>getField(a,"acquiring"));
        }
    }
    @Test public void timeoutRestoresWindowAndLateCallbackCannotAttachImage() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);idle(9500);
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
            File draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);assertFalse(draft.exists());
        }
    }
    @Test public void cancellingAndImmediatelyRetryingDoesNotLetOldTimerAbortNewRequest() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_read_page).performClick();idle(100);a.onBackPressed();
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            assertEquals(1,ServiceShadow.reads);assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_original).getVisibility());
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
        }
    }
    @Test public void optingOutOfClipboardKeepsIndependentPageContextAndThought() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            File draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);
            a.<CompoundButton>findViewById(R.id.quick_note_clipboard).setChecked(false);
            assertTrue(draft.exists());assertEquals("",a.<EditText>findViewById(R.id.quick_note_quote).getText().toString());
            CaptureStore.CaptureRecord record=save(a);assertEquals("quick_note",record.sourceType);assertTrue(record.hasImage);
            assertEquals("我的独立想法",record.comment);assertEquals("reader.app",record.sourcePackage);
        }
    }
    @Test public void recreationKeepsEditedExcerptAndOriginalWithoutReadingAgain() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            a.<EditText>findViewById(R.id.quick_note_original).setText("补充原文");
            a.<EditText>findViewById(R.id.quick_note_quote).setText("调整摘录");
            c.recreate();a=c.get();
            assertEquals("调整摘录",a.<EditText>findViewById(R.id.quick_note_quote).getText().toString());
            assertEquals("补充原文",a.<EditText>findViewById(R.id.quick_note_original).getText().toString());
            assertEquals("我的独立想法",a.<EditText>findViewById(R.id.capture_comment_input).getText().toString());
            assertEquals(1,ServiceShadow.reads);assertEquals(1,ClipboardShadow.reads);
        }
    }
    @Test public void linkOnlyNoteStillWorksAndEmptyNoteCannotSave() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();a.findViewById(R.id.capture_editor_save).performClick();
            assertNotNull(a.<EditText>findViewById(R.id.capture_comment_input).getError());
            a.<EditText>findViewById(R.id.capture_source_url).setText("https://example.com/a");
            assertEquals("https://example.com/a",save(a).sourceUrl);assertEquals(0,ClipboardShadow.reads);
        }
    }
    @Test public void accountMismatchCannotSaveIntoAnotherScope() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);ReflectionHelpers.setField(a,"ownerScope","other-account");
            a.findViewById(R.id.capture_editor_save).performClick();
            ReflectionHelpers.<ExecutorService>getField(a,"executor").submit(()->{}).get(5,TimeUnit.SECONDS);idle(0);
            assertTrue(CaptureStore.list(a,10).isEmpty());assertFalse(a.isFinishing());assertTrue(a.findViewById(R.id.capture_editor_save).isEnabled());
            assertTrue(ShadowToast.getTextOfLatestToast().contains("保存失败"));
        }
    }
    @Test public void switchingToTextRemovesScreenshotAndDoesNotKeepBothContexts() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            File draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            assertFalse(draft.exists());CaptureStore.CaptureRecord record=save(a);
            assertTrue(record.captureContext.has("text"));assertFalse(record.hasImage);assertNull(record.contextFile);
        }
    }
    @Test public void rotationDuringSaveAttachesCompletionToNewEditorWithoutDuplicateRecord() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);
            ExecutorService writer=ReflectionHelpers.getField(a,"executor");
            java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);
            writer.execute(()->{try{gate.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
            a.findViewById(R.id.capture_editor_save).performClick();
            try {c.recreate();assertFalse(c.get().findViewById(R.id.capture_editor_save).isEnabled());}
            finally {gate.countDown();}
            assertTrue(writer.awaitTermination(10,TimeUnit.SECONDS));idle(0);
            assertEquals(1,CaptureStore.list(c.get(),10).size());assertTrue(c.get().isFinishing());
            assertEquals(1,ClipboardShadow.reads);
        }
    }
    @Test public void accountChangeDuringScreenshotRestoresEditorAndDiscardsResult() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            ReflectionHelpers.setField(a,"ownerScope","other-account");File draft=screenshot(a);
            ServiceShadow.callback.onCaptured(draft);idle(0);assertFalse(draft.exists());
            assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
            assertFalse(ReflectionHelpers.<Boolean>getField(a,"acquiring"));
        }
    }
    @Test public void cancelRequiresConfirmationAndCleansUnsavedScreenshot() throws Exception {
        File draft;
        try(var c=note()) {
            QuickNoteActivity a=c.get();thought(a);optIn(a);ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);
            a.findViewById(R.id.capture_editor_cancel).performClick();assertFalse(a.isFinishing());
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            idle(0);
            assertTrue(a.isFinishing());assertTrue(CaptureStore.list(a,10).isEmpty());
        }
        assertFalse(draft.exists());
    }
    @Test public void contextButtonsAreVisibleWithoutClipboardAndOriginalAloneCanSave() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;
            assertTrue(a.findViewById(R.id.quick_note_context).isShown());
            assertEquals(View.GONE,a.findViewById(R.id.quick_note_material).getVisibility());
            ServiceShadow.page=new QuickNotePageContext(new CaptureSourceContext("reader.app","",""),42,"独立页面原文","");
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            assertFalse(ServiceShadow.readOnMain);assertEquals(0,ClipboardShadow.reads);
            idle(20000);
            assertFalse(a.isFinishing());assertNull(ReflectionHelpers.getField(a,"timeoutCallback"));
            CaptureStore.CaptureRecord record=save(a);
            assertEquals("",record.comment);assertEquals("",record.sourceText);assertEquals("",record.sourceUrl);
            assertEquals("独立页面原文",CaptureRecordEdits.original(record));assertFalse(record.hasImage);
            File payload=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"createPayload",
                    ReflectionHelpers.ClassParameter.from(Context.class,a),ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,record));
            org.json.JSONObject canonical=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(payload.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(canonical.getJSONObject("evidence").isNull("exact_text"));
            canonical.put("revision",1);
            String vault="d".repeat(64);
            byte[] feed=CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert",record.id,canonical));
            CaptureRemoteCache.pull(a,vault,(path,limit)->feed,()->true);
            CaptureStore.CaptureRecord remote=CaptureRemoteCache.merged(a,vault,java.util.Collections.emptyList()).get(0);
            assertEquals("",remote.sourceText);assertEquals("独立页面原文",CaptureRecordEdits.original(remote));
            assertTrue(payload.delete());
        }
    }
    @Test public void screenshotAloneCanSaveWithoutClipboardThoughtOrUrl() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;
            ServiceShadow.page=new QuickNotePageContext(new CaptureSourceContext("reader.app","",""),42,"","");
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            File draft=screenshot(a);ServiceShadow.callback.onCaptured(draft);idle(0);idle(20000);
            assertFalse(a.isFinishing());assertFalse(ServiceShadow.readOnMain);assertEquals(0,ClipboardShadow.reads);
            assertNull(ReflectionHelpers.getField(a,"timeoutCallback"));
            CaptureStore.CaptureRecord record=save(a);
            assertTrue(record.hasImage);assertEquals("",record.sourceText);assertEquals("",record.comment);assertEquals("",record.sourceUrl);
        }
    }
    @Test public void clipboardFailureAndOptOutNeverRemoveIndependentOriginal() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            ClipboardShadow.fail=true;optIn(a);
            assertEquals(ServiceShadow.page.text,a.<EditText>findViewById(R.id.quick_note_original).getText().toString());
            ClipboardShadow.fail=false;optIn(a);a.<CompoundButton>findViewById(R.id.quick_note_clipboard).setChecked(false);
            assertEquals(ServiceShadow.page.text,CaptureRecordEdits.original(save(a)));
        }
    }
    @Test public void removingContextDoesNotRemoveClipboardExcerpt() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;optIn(a);
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            a.findViewById(R.id.quick_note_clear_context).performClick();
            CaptureStore.CaptureRecord record=save(a);
            assertEquals(ClipboardShadow.value,record.sourceText);assertEquals("",CaptureRecordEdits.original(record));
        }
    }
    @Test public void cancelledPreviewIsNotRecycledWhileRenderThreadMayStillUseIt() throws Exception {
        Bitmap bound;
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_capture_page).performClick();idle(500);
            ServiceShadow.callback.onCaptured(screenshot(a));idle(0);
            bound=ReflectionHelpers.getField(a,"preview");assertNotNull(bound);
            a.findViewById(R.id.quick_note_clear_context).performClick();
            assertFalse(bound.isRecycled());idle(20000);assertFalse(a.isFinishing());
        }
        assertFalse(bound.isRecycled());
    }
    @Test public void backgroundReadExceptionReturnsToEditableNoteAndDoesNotCrashLater() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;ServiceShadow.failRead=true;thought(a);
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);idle(20000);
            assertFalse(a.isFinishing());assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
            assertTrue(a.findViewById(R.id.capture_editor_save).isEnabled());assertEquals(0,ServiceShadow.captures);
            assertEquals("我的独立想法",a.<EditText>findViewById(R.id.capture_comment_input).getText().toString());
        }
    }
    @Test public void blockedBinderReadCannotFreezeTimeoutOrPreventSavingThought() throws Exception {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;thought(a);
            ServiceShadow.entered=new java.util.concurrent.CountDownLatch(1);ServiceShadow.release=new java.util.concurrent.CountDownLatch(1);
            a.findViewById(R.id.quick_note_read_page).performClick();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertTrue(ServiceShadow.entered.await(2,TimeUnit.SECONDS));
            try {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10000));
                assertEquals(View.VISIBLE,a.findViewById(R.id.quick_note_root).getVisibility());
                assertFalse(ReflectionHelpers.<Boolean>getField(a,"acquiring"));
                a.findViewById(R.id.capture_editor_save).performClick();
                ReflectionHelpers.<ExecutorService>getField(a,"executor").submit(()->{}).get(5,TimeUnit.SECONDS);
                shadowOf(Looper.getMainLooper()).idle();assertEquals(1,CaptureStore.list(a,10).size());
            } finally {ServiceShadow.release.countDown();}
            idle(0);assertEquals("",CaptureRecordEdits.original(CaptureStore.list(a,10).get(0)));
        }
    }
    @Test public void independentContextSurvivesRotationWithoutEnablingOrReadingClipboard() {
        try(var c=note()) {
            QuickNoteActivity a=c.get();ServiceShadow.ready=true;
            a.findViewById(R.id.quick_note_read_page).performClick();idle(500);
            c.recreate();a=c.get();
            assertFalse(a.<CompoundButton>findViewById(R.id.quick_note_clipboard).isChecked());
            assertTrue(a.findViewById(R.id.quick_note_context).isShown());
            assertEquals(ServiceShadow.page.text,a.<EditText>findViewById(R.id.quick_note_original).getText().toString());
            assertEquals(0,ClipboardShadow.reads);
        }
    }
    @Implements(value=QuickNoteClipboard.class,isInAndroidSdk=false)
    public static class ClipboardShadow {
        static int reads;static String value;static boolean fail;
        @Implementation protected static String first(Context context) throws Exception {
            reads++;if(fail)throw new SecurityException("restricted");return value;
        }
    }
    @Implements(value=CaptureAccessibilityService.class,isInAndroidSdk=false)
    public static class ServiceShadow {
        static boolean ready;static int reads,captures;static QuickNotePageContext page;
        static boolean failRead,readOnMain;
        static java.util.concurrent.CountDownLatch entered,release;
        static CaptureAccessibilityService.CaptureCallback callback;
        @Implementation protected static boolean isReady(){return ready;}
        @Implementation protected static QuickNotePageContext readPageOnce(int id,boolean text){
            reads++;readOnMain|=Looper.myLooper()==Looper.getMainLooper();
            if(entered!=null) {
                entered.countDown();
                boolean done=false;while(!done) try {done=release.await(5,TimeUnit.SECONDS);}
                catch(InterruptedException ignored) { /* Model an IPC that ignores cancellation. */ }
            }
            if(failRead)throw new IllegalStateException("simulated provider failure");
            return page;
        }
        @Implementation protected static void captureOnce(CaptureAccessibilityService.CaptureCallback result){captures++;callback=result;}
    }
}
