package com.codex.mnote;

import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureContextTest {
    Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); }
    CaptureStore.CaptureRecord screenshot(boolean retained) throws Exception {
        Bitmap full=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888); full.eraseColor(Color.GREEN);
        File draft=CaptureStore.writeDraftBitmap(context,full);
        Bitmap editor=Bitmap.createScaledBitmap(full,100,150,true);
        CaptureMarkupView view=new CaptureMarkupView(context,null); view.setSourceBitmap(editor);
        ReflectionHelpers.<RectF>getField(view,"cropRect").set(10.2f,20.1f,40.5f,60.2f);
        Bitmap original=view.renderOriginalSelection(), annotated=view.renderAnnotatedSelection();
        CaptureStore.CaptureRecord record=CaptureStore.save(context,draft,original,annotated,view.annotationLayer(),
                "thought","我的想法","screen","","","","",retained,null);
        full.recycle(); editor.recycle(); original.recycle(); annotated.recycle();
        assertFalse(draft.exists()); return record;
    }
    @Test public void retainsFullImageAndExactIntegerSelectionInFullResolutionCoordinates() throws Exception {
        CaptureStore.CaptureRecord record=screenshot(true);
        Bitmap full=BitmapFactory.decodeFile(record.contextFile.getAbsolutePath());
        assertEquals(200,full.getWidth()); assertEquals(300,full.getHeight()); full.recycle();
        Bitmap selected=BitmapFactory.decodeFile(record.originalFile.getAbsolutePath());
        assertEquals(31,selected.getWidth()); assertEquals(41,selected.getHeight()); selected.recycle();
        JSONObject image=record.captureContext.getJSONObject("image"), box=image.getJSONObject("selection");
        assertEquals("context",image.getString("asset_role")); assertTrue(image.getBoolean("retained"));
        assertEquals(20,box.getDouble("left"),0); assertEquals(40,box.getDouble("top"),0);
        assertEquals(82,box.getDouble("right"),0); assertEquals(122,box.getDouble("bottom"),0);
    }
    @Test public void optOutNeverPersistsFullScreenshot() throws Exception {
        CaptureStore.CaptureRecord record=screenshot(false);
        assertNull(record.contextFile); assertFalse(record.captureContext.getJSONObject("image").getBoolean("retained"));
        assertEquals(3,Objects.requireNonNull(record.metadataFile.getParentFile().listFiles()).length);
    }
    @Test public void contextImageAndCoordinatesSurviveUploadPayloadAndRemotePull() throws Exception {
        CaptureStore.CaptureRecord record=screenshot(true);
        File payload=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"createPayload",
                ReflectionHelpers.ClassParameter.from(Context.class,context),
                ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,record));
        JSONObject canonical=new JSONObject(new String(Files.readAllBytes(payload.toPath()),java.nio.charset.StandardCharsets.UTF_8)); payload.delete();
        assertEquals("我的想法",canonical.getString("comment"));
        JSONObject assets=canonical.getJSONObject("assets"); assertEquals(3,assets.length());
        Map<String,byte[]> downloads=new HashMap<>(); JSONObject manifest=new JSONObject();
        for(String role:new String[]{"original","annotated","context"}) {
            byte[] bytes=android.util.Base64.decode(assets.getJSONObject(role).getString("data_base64"),0);
            downloads.put(role,bytes); manifest.put(role,new JSONObject().put("size",bytes.length).put("sha256",CaptureRemoteCache.digest(bytes)));
        }
        canonical.put("assets",manifest).put("revision",1);
        String vault="f".repeat(64);
        byte[] feed=CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert",record.id,canonical));
        CaptureRemoteCache.pull(context,vault,(path,limit)->path.startsWith("/v1/changes") ? feed : downloads.get(path.substring(path.lastIndexOf('/')+1)),()->true);
        CaptureStore.CaptureRecord remote=CaptureRemoteCache.merged(context,vault,Collections.emptyList()).get(0);
        assertArrayEquals(Files.readAllBytes(record.contextFile.toPath()),Files.readAllBytes(remote.contextFile.toPath()));
        assertEquals(record.captureContext.toString(),remote.captureContext.toString());
    }
    @Test public void missingDeclaredContextImageMakesRecordIncomplete() throws Exception {
        CaptureStore.CaptureRecord record=screenshot(true); assertTrue(record.contextFile.delete());
        assertNull(CaptureStore.readRecord(record.metadataFile.getParentFile()));
    }
    @Test public void textOffsetsAreHonestForUniqueAmbiguousAndAbsentSelections() throws Exception {
        JSONObject unique=CaptureContext.text("😀前文选中后文","user_supplied","选中");
        assertEquals(4,unique.getInt("start")); assertEquals(6,unique.getInt("end"));
        assertEquals("utf16_code_units",unique.getString("offset_unit"));
        assertEquals("ambiguous",CaptureContext.text("重复重复","user_supplied","重复").getString("match"));
        assertTrue(CaptureContext.text("原文","user_supplied","不同").isNull("start"));
        assertThrows(IllegalArgumentException.class,()->CaptureContext.text("a".repeat(40001),"user_supplied","a"));
    }
    @Test public void processTextPreservesExactQuoteAndSeparateThoughtWithoutInventingFullText() throws Exception {
        try(ActivityController<CaptureEditorActivity> controller=editor(Intent.ACTION_PROCESS_TEXT,"  选中的文字\n")) {
            CaptureEditorActivity activity=controller.get();
            activity.<EditText>findViewById(R.id.capture_comment_input).setText("我的独立想法");
            save(activity); CaptureStore.CaptureRecord record=CaptureStore.list(activity,10).get(0);
            assertEquals("  选中的文字\n",record.sourceText); assertEquals("我的独立想法",record.comment);
            assertFalse(record.captureContext.has("text")); assertFalse(record.hasImage);
            assertEquals(android.app.Activity.RESULT_CANCELED,shadowOf(activity).getResultCode());
            assertNull(shadowOf(activity).getResultIntent());
        }
    }
    @Test public void selectWithinSharedOriginalCanKeepOrOmitOriginal() throws Exception {
        for(boolean keep:new boolean[]{true,false}) {
            try(ActivityController<CaptureEditorActivity> controller=editor(Intent.ACTION_SEND,"前文：选中这段：后文")) {
                CaptureEditorActivity activity=controller.get();
                CaptureTextExcerpt excerpt=ReflectionHelpers.getField(activity,"textExcerpt"); excerpt.choose(3,7);
                activity.<CheckBox>findViewById(R.id.capture_retain_text_context).setChecked(keep);
                save(activity); CaptureStore.CaptureRecord record=CaptureStore.list(activity,10).get(0);
                assertEquals("选中这段",record.sourceText); assertEquals(keep,record.captureContext.has("text"));
                if(keep) {
                    JSONObject text=record.captureContext.getJSONObject("text");
                    assertEquals("前文：选中这段：后文",text.getString("full_text"));
                    assertEquals("shared_text",text.getString("origin")); assertEquals(3,text.getInt("start"));
                } else assertFalse(new String(Files.readAllBytes(record.metadataFile.toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("前文"));
            }
        }
    }
    @Test public void selectedTextCanAttachManuallyProvidedContext() throws Exception {
        try(ActivityController<CaptureEditorActivity> controller=editor(Intent.ACTION_PROCESS_TEXT,"选中")) {
            CaptureEditorActivity activity=controller.get(); CaptureTextExcerpt excerpt=ReflectionHelpers.getField(activity,"textExcerpt");
            ReflectionHelpers.setField(excerpt,"original","前文选中后文");
            activity.<CheckBox>findViewById(R.id.capture_retain_text_context).setChecked(true);
            save(activity); JSONObject text=CaptureStore.list(activity,10).get(0).captureContext.getJSONObject("text");
            assertEquals("user_supplied",text.getString("origin")); assertEquals(2,text.getInt("start"));
        }
    }
    @Test public void repeatedQuoteKeepsActualChosenOccurrenceAcrossRecreation() throws Exception {
        try(ActivityController<CaptureEditorActivity> controller=editor(Intent.ACTION_SEND,"重复和重复")) {
            CaptureEditorActivity activity=controller.get();
            ReflectionHelpers.<CaptureTextExcerpt>getField(activity,"textExcerpt").choose(3,5);
            activity.<CheckBox>findViewById(R.id.capture_retain_text_context).setChecked(true);
            controller.recreate(); activity=controller.get();
            save(activity); JSONObject text=CaptureStore.list(activity,10).get(0).captureContext.getJSONObject("text");
            assertEquals("user_selected",text.getString("match")); assertEquals(3,text.getInt("start"));
            assertEquals(5,text.getInt("end"));
        }
    }
    @Test public void damagedContextDownloadCannotAdvanceCursorOrExposePartialRecord() throws Exception {
        JSONObject remote=CaptureRemoteCacheTest.record("bad-context",1).put("assets",new JSONObject()
                .put("context",new JSONObject().put("size",3).put("sha256",CaptureRemoteCache.digest(new byte[]{1,2,3}))));
        byte[] feed=CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert","bad-context",remote));
        String vault="b".repeat(64);
        assertThrows(IOException.class,()->CaptureRemoteCache.pull(context,vault,
                (path,limit)->path.startsWith("/v1/changes")?feed:new byte[]{4,5,6},()->true));
        assertTrue(CaptureRemoteCache.merged(context,vault,Collections.emptyList()).isEmpty());
        CaptureRemoteCache.pull(context,vault,(path,limit)-> {
            assertTrue(path.contains("after=0"));
            try { return CaptureRemoteCacheTest.page(0,false); } catch(Exception error) { throw new IOException(error); }
        },()->true);
    }
    private ActivityController<CaptureEditorActivity> editor(String action,String value) {
        Intent intent=new Intent(action).setType("text/plain");
        intent.putExtra(Intent.ACTION_PROCESS_TEXT.equals(action)?Intent.EXTRA_PROCESS_TEXT:Intent.EXTRA_TEXT,value);
        return Robolectric.buildActivity(CaptureEditorActivity.class,intent).setup();
    }
    private void save(CaptureEditorActivity activity) throws Exception {
        activity.findViewById(R.id.capture_editor_save).performClick();
        ReflectionHelpers.<ExecutorService>getField(activity,"executor").submit(()->{}).get(10,TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(activity.isFinishing());
    }
}
