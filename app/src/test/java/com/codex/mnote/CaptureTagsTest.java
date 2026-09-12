package com.codex.mnote;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.io.IOException;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,30,35},instrumentedPackages="com.codex.mnote",shadows={CaptureAccountTest.Scheduler.class})
public class CaptureTagsTest {
    Context context;
    @Before public void setup() {context=RuntimeEnvironment.getApplication();}
    @Test public void parsesDeduplicatesAndRoundTripsWithoutChangingSourceText() throws Exception {
        JSONArray tags=CaptureTags.parse(" #灵感， 工作;TODO\ntodo；灵感 ,, ");
        assertEquals("[\"灵感\",\"工作\",\"TODO\"]",tags.toString());
        assertEquals(tags.toString(),CaptureTags.parse(CaptureTags.input(tags)).toString());
        assertTrue(CaptureTags.matches(tags,"todo"));assertFalse(CaptureTags.matches(tags,""));
        assertTrue(CaptureTags.matches(tags,null));assertTrue(CaptureTags.matches(new JSONArray(),""));
        CaptureStore.CaptureRecord saved=note(tags);
        assertEquals("  摘录  ",saved.sourceText);
        assertEquals(tags.toString(),CaptureStore.find(context,saved.id).tags.toString());
        tags.put("事后修改数组");assertEquals(3,saved.tags.length());
    }
    @Test public void invalidLabelsAreRejectedRatherThanTruncated() {
        assertThrows(IllegalArgumentException.class,()->CaptureTags.parse("字".repeat(33)));
        assertThrows(IllegalArgumentException.class,()->CaptureTags.normalize(new JSONArray().put(42)));
        assertThrows(IllegalArgumentException.class,()->CaptureTags.normalize(new JSONArray().put("a\nb\nc")));
        JSONArray tooMany=new JSONArray();for(int i=0;i<21;i++)tooMany.put("tag"+i);
        assertThrows(IllegalArgumentException.class,()->CaptureTags.normalize(tooMany));
        assertThrows(IllegalArgumentException.class,()->CaptureTags.parse(" ".repeat(4097)));
    }
    @Test public void oldRecordsRemainUntaggedAndTagOnlyEditsPreserveEvidence() throws Exception {
        CaptureStore.CaptureRecord old=note(null);
        assertEquals(0,old.tags.length());
        CaptureStore.CaptureRecord tagged=edit(old,CaptureTags.parse("灵感，待实践"));
        assertEquals(old.id,tagged.id);assertEquals(old.captureContext.toString(),tagged.captureContext.toString());
        assertEquals(2,tagged.tags.length());assertEquals(0,CaptureStore.readRecordObject(old.metadataFile).getJSONArray("tags").length());
        assertEquals("record_changed",assertThrows(IOException.class,()->edit(old,new JSONArray())).getMessage());
        CaptureStore.CaptureRecord legacyEdit=CaptureRecordEdits.save(context,"guest",tagged.id,
                CaptureRecordEdits.fingerprint(tagged),"修改想法",tagged.sourceText,CaptureRecordEdits.original(tagged));
        assertEquals(tagged.tags.toString(),legacyEdit.tags.toString());
        assertEquals(0,edit(legacyEdit,new JSONArray()).tags.length());
    }
    @Test public void tagsTravelThroughUploadPullAndClearWithoutChangingCanonicalFields() throws Exception {
        CaptureStore.CaptureRecord saved=note(CaptureTags.parse("工作，灵感"));
        JSONObject remote=metadata(saved).put("revision",7).put("extra",new JSONObject().put("keep",true));
        String scope="a".repeat(64);
        CaptureAccountSession.preferences(context).edit().putString("account_id","a".repeat(32)).putString("scope",scope).commit();
        byte[] feed=CaptureRemoteCacheTest.page(1,false,CaptureRemoteCacheTest.change(1,"upsert",saved.id,remote));
        CaptureRemoteCache.pull(context,scope,(path,limit)->feed,()->true);
        CaptureStore.CaptureRecord pulled=CaptureRemoteCache.merged(context,scope,Collections.emptyList()).get(0);
        assertEquals(saved.tags.toString(),pulled.tags.toString());
        CaptureStore.CaptureRecord cleared=CaptureRecordEdits.save(context,scope,pulled.id,CaptureRecordEdits.fingerprint(pulled),
                pulled.comment,pulled.sourceText,CaptureRecordEdits.original(pulled),new JSONArray());
        JSONObject upload=metadata(cleared);
        assertEquals(0,upload.getJSONArray("tags").length());assertEquals(7,upload.getInt("base_revision"));
        assertTrue(upload.getJSONObject("extra").getBoolean("keep"));
        assertEquals(pulled.captureContext.toString(),cleared.captureContext.toString());
    }
    private CaptureStore.CaptureRecord note(JSONArray tags) throws Exception {
        return CaptureStore.save(context,null,null,null,null,"thought","想法","clipboard","  摘录  ","","","",false,
                CaptureContext.text("前文  摘录  后文","user_supplied","  摘录  "),tags);
    }
    private CaptureStore.CaptureRecord edit(CaptureStore.CaptureRecord record,JSONArray tags) throws Exception {
        return CaptureRecordEdits.save(context,"guest",record.id,CaptureRecordEdits.fingerprint(record),
                record.comment,record.sourceText,CaptureRecordEdits.original(record),tags);
    }
    private JSONObject metadata(CaptureStore.CaptureRecord record) {
        return ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class,"metadata",
                ReflectionHelpers.ClassParameter.from(Context.class,context),
                ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class,record));
    }
}
