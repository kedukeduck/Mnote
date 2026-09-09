package com.codex.mnote;

import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},instrumentedPackages="com.codex.mnote",shadows={CaptureAccountTest.Crypto.class,CaptureAccountTest.Scheduler.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptureRecordEditsTest {
    Context context;
    @Before public void setup() {context=RuntimeEnvironment.getApplication();}
    private CaptureStore.CaptureRecord note() throws Exception {
        return CaptureStore.save(context,null,null,null,null,"thought","旧想法","share_text","摘录","",
                "https://example.com/page","shared_text",false,CaptureContext.text("前文摘录后文","shared_text","摘录"));
    }
    private CaptureStore.CaptureRecord edit(CaptureStore.CaptureRecord record,String comment,String quote,String original) throws Exception {
        return CaptureRecordEdits.save(context,CaptureAccountSession.scope(context),record.id,CaptureRecordEdits.fingerprint(record),comment,quote,original);
    }
    @Test public void updatesSameRecordAndRecomputesTextProvenanceWithoutTouchingOldSnapshot() throws Exception {
        CaptureStore.CaptureRecord before=note();
        byte[] old=Files.readAllBytes(before.metadataFile.toPath());
        CaptureStore.CaptureRecord after=edit(before,"新的想法","重点","修改后的原文包含重点。");
        assertEquals(before.id,after.id);assertEquals(before.createdAt,after.createdAt);
        assertEquals(1,CaptureStore.list(context,10).size());assertEquals("新的想法",CaptureStore.find(context,before.id).comment);
        assertEquals("user_edited",after.captureContext.getJSONObject("text").getString("origin"));
        assertEquals(8,after.captureContext.getJSONObject("text").getInt("start"));
        assertTrue(after.captureContext.getJSONObject("text_edit").getBoolean("quote_modified"));
        assertEquals(before.aiAccess,after.aiAccess);assertEquals(before.sourceUrl,after.sourceUrl);
        assertArrayEquals(old,Files.readAllBytes(before.metadataFile.toPath()));
        assertEquals(CaptureStore.SYNC_LOCAL_ONLY,after.syncState);
        CaptureStore.CaptureRecord removed=edit(after,"新的想法","重点","");
        assertFalse(removed.captureContext.has("text"));
        assertEquals(1,CaptureStore.list(context,10).size());
    }
    @Test public void commentOnlyEditLeavesExactSelectionOffsetsAndTextOriginUnchanged() throws Exception {
        CaptureStore.CaptureRecord before=note();
        CaptureStore.CaptureRecord after=edit(before,"只改想法",before.sourceText,CaptureRecordEdits.original(before));
        assertEquals(before.captureContext.toString(),after.captureContext.toString());
        assertFalse(after.captureContext.has("text_edit"));
    }
    @Test public void staleDeletedAndWrongAccountEditsAreRejectedWithoutDataLoss() throws Exception {
        CaptureStore.CaptureRecord before=note();edit(before,"第一次修改",before.sourceText,CaptureRecordEdits.original(before));
        assertEquals("record_changed",assertThrows(IOException.class,()->edit(before,"过期页面","","")).getMessage());
        assertEquals("account_changed",assertThrows(IOException.class,()->CaptureRecordEdits.save(context,"f".repeat(64),
                before.id,CaptureRecordEdits.fingerprint(before),"其他账号","","")).getMessage());
        CaptureDeletionStore.delete(context,before.id);
        assertEquals("record_unavailable",assertThrows(IOException.class,()->edit(before,"不能复活","","")).getMessage());
    }
    @Test public void failedPointerCommitKeepsPreviouslyVisibleRecord() throws Exception {
        CaptureStore.CaptureRecord before=note();
        File blocked=new File(before.metadataFile.getParentFile(),"current.json.part");assertTrue(blocked.mkdir());
        assertThrows(IOException.class,()->edit(before,"不能发布的修改","摘录","原文"));
        assertEquals("旧想法",CaptureStore.find(context,before.id).comment);
        assertEquals(1,CaptureStore.list(context,10).size());
    }
    @Test public void imageEditsAndGuestImportKeepAllPngBytesAndContextCoordinates() throws Exception {
        CaptureContextTest fixture=new CaptureContextTest();fixture.setup();
        CaptureStore.CaptureRecord before=fixture.screenshot(true);
        CaptureStore.CaptureRecord after=edit(before,"截图的新想法","手动补充摘录","这里是手动补充摘录的原文");
        assertArrayEquals(Files.readAllBytes(before.originalFile.toPath()),Files.readAllBytes(after.originalFile.toPath()));
        assertArrayEquals(Files.readAllBytes(before.annotatedFile.toPath()),Files.readAllBytes(after.annotatedFile.toPath()));
        assertArrayEquals(Files.readAllBytes(before.contextFile.toPath()),Files.readAllBytes(after.contextFile.toPath()));
        assertEquals(before.captureContext.getJSONObject("image").toString(),after.captureContext.getJSONObject("image").toString());
        assertEquals(3,payload(after).getJSONObject("assets").length());
        assertTrue(CaptureStore.updateSyncState(context,after.id,CaptureStore.SYNC_SYNCED,"",4));
        CaptureStore.CaptureRecord synced=CaptureStore.find(context,after.id);
        CaptureStore.CaptureRecord editedAgain=edit(synced,"再次修改",synced.sourceText,CaptureRecordEdits.original(synced));
        assertEquals(0,payload(editedAgain).getJSONObject("assets").length());
        assertEquals(4,payload(editedAgain).getInt("base_revision"));
        account();assertEquals(1,CaptureAccountImport.run(context));
        CaptureStore.CaptureRecord imported=CaptureStore.find(context,before.id);
        assertEquals("再次修改",imported.comment);assertEquals(0,imported.serverRevision);
        assertEquals(3,payload(imported).getJSONObject("assets").length());
        assertArrayEquals(Files.readAllBytes(before.contextFile.toPath()),Files.readAllBytes(imported.contextFile.toPath()));
    }
    @Test public void remoteOnlyEditPreservesCanonicalMetadataAndUsesRevisionForUpload() throws Exception {
        account();String scope=CaptureAccountSession.scope(context);
        JSONObject remote=CaptureRemoteCacheTest.record("desktop-record",7)
                .put("ocr",new JSONArray().put(new JSONObject().put("text","existing OCR")))
                .put("custom_data",new JSONObject().put("keep",true))
                .put("evidence",new JSONObject().put("custom_evidence","keep me"));
        remote.getJSONObject("source").put("page_title","page title");
        CaptureRemoteCache.pull(context,scope,(path,limit)->{
            try{return CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert","desktop-record",remote));}
            catch(Exception e){throw new IOException(e);}
        },()->true);
        CaptureStore.CaptureRecord before=CaptureRecordEdits.latest(context,scope,"desktop-record");
        CaptureStore.CaptureRecord after=edit(before,"更新云端摘录","手动修订","手动修订的完整原文");
        JSONObject payload=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"metadata",
                ReflectionHelpers.ClassParameter.from(Context.class,context),
                ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,after));
        assertEquals(7,payload.getInt("base_revision"));assertEquals(remote.getJSONArray("ocr").toString(),payload.getJSONArray("ocr").toString());
        assertTrue(payload.getJSONObject("custom_data").getBoolean("keep"));
        assertEquals("page title",payload.getJSONObject("source").getString("page_title"));
        assertEquals("keep me",payload.getJSONObject("evidence").getString("custom_evidence"));
        assertEquals("user_edited",payload.getJSONObject("evidence").getJSONObject("exact_text").getString("delivered_by"));
        assertEquals(1,CaptureRemoteCache.merged(context,CaptureStore.list(context,10)).size());
        assertEquals("更新云端摘录",CaptureStore.find(context,"desktop-record").comment);
        assertEquals("另一台设备的灵感",CaptureStore.readRecord(before.metadataFile.getParentFile()).comment);
    }
    @Test public void excessiveInputAndEmptyRecordAreNotSilentlyTruncatedOrSaved() throws Exception {
        CaptureStore.CaptureRecord before=CaptureStore.save(context,null,null,null,null,"thought","only note","quick_note","","");
        assertEquals("empty_record",assertThrows(IOException.class,()->edit(before,"  ","","")).getMessage());
        assertEquals("text_too_long",assertThrows(IOException.class,()->edit(before,"x".repeat(20001),"","")).getMessage());
        assertEquals("only note",CaptureStore.find(context,before.id).comment);
    }
    @Test public void unreadableCanonicalBaseRefusesEditInsteadOfDroppingCloudFields() throws Exception {
        account();String scope=CaptureAccountSession.scope(context);
        JSONObject remote=CaptureRemoteCacheTest.record("oversized-metadata",1).put("extra","x".repeat(530000));
        CaptureRemoteCache.pull(context,scope,(path,limit)->{
            try{return CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert","oversized-metadata",remote));}
            catch(Exception error){throw new IOException(error);}
        },()->true);
        CaptureStore.CaptureRecord before=CaptureRecordEdits.latest(context,scope,"oversized-metadata");
        assertEquals("canonical_unavailable",assertThrows(IOException.class,()->edit(before,"不能丢掉附加信息",before.sourceText,"")).getMessage());
        assertTrue(CaptureStore.list(context,10).isEmpty());
        assertEquals(before.comment,CaptureRecordEdits.latest(context,scope,before.id).comment);
    }
    private void account() {
        CaptureAccountSession.preferences(context).edit().putString("account_id","a".repeat(32))
                .putString("scope","a".repeat(64)).commit();
    }
    private JSONObject payload(CaptureStore.CaptureRecord record) throws Exception {
        File payload=ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"createPayload",
                ReflectionHelpers.ClassParameter.from(Context.class,context),
                ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,record));
        try {return new JSONObject(new String(Files.readAllBytes(payload.toPath()),java.nio.charset.StandardCharsets.UTF_8));}
        finally {assertTrue(payload.delete());}
    }
}
