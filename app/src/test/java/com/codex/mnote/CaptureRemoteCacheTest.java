package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptureRemoteCacheTest {
    Context context;
    String vault;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        vault = CaptureRemoteCache.digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }
    static JSONObject record(String id, int revision) throws Exception {
        return new JSONObject().put("id",id).put("revision",revision).put("created_at","2026-09-07T00:00:00Z")
                .put("kind","thought").put("comment","另一台设备的灵感")
                .put("ai_access","deny").put("annotations",new JSONArray())
                .put("source",new JSONObject().put("type","browser").put("url","https://example.com/note")
                        .put("text","原文").put("app_id","com.example.browser"));
    }
    static JSONObject change(long seq, String op, String id, JSONObject record) throws Exception {
        JSONObject value = new JSONObject().put("sequence",seq).put("operation",op).put("capture_id",id);
        if (record != null) value.put("record",record);
        return value;
    }
    static byte[] page(long next, boolean more, JSONObject... changes) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject change : changes) array.put(change);
        return new JSONObject().put("changes",array).put("next_sequence",next).put("has_more",more)
                .toString().getBytes(StandardCharsets.UTF_8);
    }
    List<CaptureStore.CaptureRecord> records() { return CaptureRemoteCache.merged(context,vault,Collections.emptyList()); }
    int pull(byte[] page) throws Exception {
        return CaptureRemoteCache.pull(context,vault,(path,limit) -> page,() -> true);
    }
    @Test public void pullsTextWithoutMakingItUploadableAndReusesCursor() throws Exception {
        assertEquals(1,pull(page(1,false,change(1,"upsert","desktop-note",record("desktop-note",1)))));
        CaptureStore.CaptureRecord note = records().get(0);
        assertEquals("另一台设备的灵感",note.comment);
        assertEquals("https://example.com/note",note.sourceUrl);
        assertEquals("deny",note.aiAccess);
        assertEquals(CaptureStore.SYNC_SYNCED,note.syncState);
        assertTrue(CaptureStore.list(context,100).isEmpty());
        byte[] empty = page(1,false);
        assertEquals(0,CaptureRemoteCache.pull(context,vault,(path,limit) -> {
            assertEquals("/v1/changes?after=1&limit=50",path); return empty;
        },() -> true));
        assertEquals(1,records().size());
    }
    @Test public void downloadsAndVerifiesScreenshotIgnoringForeignHref() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG,100,output); bitmap.recycle();
        byte[] png = output.toByteArray();
        JSONObject note = record("image-note",1).put("assets",new JSONObject().put("original",new JSONObject()
                .put("size",png.length).put("sha256",CaptureRemoteCache.digest(png)).put("href","https://untrusted.example/steal")));
        byte[] feed = page(1,false,change(1,"upsert","image-note",note));
        CaptureRemoteCache.pull(context,vault,(path,limit) -> {
            if (path.startsWith("/v1/changes?")) return feed;
            assertEquals("/v1/captures/image-note/assets/original",path); return png;
        },() -> true);
        CaptureStore.CaptureRecord stored = records().get(0);
        assertTrue(stored.hasImage);
        assertArrayEquals(png,java.nio.file.Files.readAllBytes(stored.originalFile.toPath()));
        assertArrayEquals(png,java.nio.file.Files.readAllBytes(stored.annotatedFile.toPath()));
    }
    @Test public void badAssetDoesNotAdvanceCursorOrExposePartialRecord() throws Exception {
        JSONObject note = record("bad-image",1).put("assets",new JSONObject().put("annotated",new JSONObject()
                .put("size",3).put("sha256",CaptureRemoteCache.digest(new byte[]{1,2,3}))));
        byte[] feed = page(1,false,change(1,"upsert","bad-image",note));
        assertThrows(IOException.class,() -> CaptureRemoteCache.pull(context,vault,
                (path,limit) -> path.startsWith("/v1/changes?") ? feed : new byte[]{4,5,6},() -> true));
        assertTrue(records().isEmpty());
        byte[] empty = page(0,false);
        CaptureRemoteCache.pull(context,vault,(path,limit) -> { assertTrue(path.contains("after=0&")); return empty; },() -> true);
    }
    @Test public void followsPagesAndHandlesDeleteRestoreAndPurge() throws Exception {
        byte[] one = page(1,true,change(1,"upsert","note",record("note",1)));
        byte[] two = page(2,false,change(2,"upsert","other",record("other",1)));
        CaptureRemoteCache.pull(context,vault,(path,limit) -> path.contains("after=0&") ? one : two,() -> true);
        assertEquals(2,records().size());
        pull(page(3,false,change(3,"delete","note",null))); assertEquals(1,records().size());
        pull(page(4,false,change(4,"restore","note",record("note",2)))); assertEquals(2,records().size());
        pull(page(5,false,change(5,"purge","note",null))); assertEquals(1,records().size());
    }
    @Test public void localRecordsWinEvenWhenRemoteIsDeleted() throws Exception {
        CaptureStore.CaptureRecord local = CaptureStore.save(context,null,null,null,null,"thought","未上传","quick_note","","");
        pull(page(1,false,change(1,"upsert",local.id,record(local.id,2))));
        assertEquals("未上传",CaptureRemoteCache.merged(context,vault,Collections.singletonList(local)).get(0).comment);
        pull(page(2,false,change(2,"delete",local.id,null)));
        assertEquals(1,CaptureRemoteCache.merged(context,vault,Collections.singletonList(local)).size());
        assertEquals("未上传",CaptureStore.list(context,100).get(0).comment);
    }
    @Test public void vaultsAreIsolatedAndConfigurationChangeDoesNotCommit() throws Exception {
        byte[] feed = page(1,false,change(1,"upsert","note",record("note",1)));
        assertThrows(IOException.class,() -> CaptureRemoteCache.pull(context,vault,(path,limit) -> feed,() -> false));
        assertTrue(records().isEmpty());
        pull(feed);
        assertTrue(CaptureRemoteCache.merged(context,CaptureRemoteCache.digest(new byte[]{1}),Collections.emptyList()).isEmpty());
        CaptureSyncPreferences.Config a = new CaptureSyncPreferences.Config("https://example.com","test-token-one","deny");
        CaptureSyncPreferences.Config b = new CaptureSyncPreferences.Config("https://example.com","test-token-two","deny");
        assertNotEquals(CaptureRemoteCache.vault(a),CaptureRemoteCache.vault(b));
    }
    @Test public void traversalAndMalformedCursorAreRejected() throws Exception {
        assertThrows(IOException.class,() -> pull(page(1,false,change(1,"upsert","../outside",record("../outside",1)))));
        assertThrows(IOException.class,() -> pull(page(9,false,change(1,"upsert","note",record("note",1)))));
        assertTrue(records().isEmpty());
    }
    @Test public void previousPageStaysVisibleWhenNextPageFails() throws Exception {
        byte[] first = page(1,true,change(1,"upsert","note",record("note",1)));
        assertThrows(IOException.class,() -> CaptureRemoteCache.pull(context,vault,(path,limit) -> {
            if (path.contains("after=0&")) return first; throw new IOException("offline");
        },() -> true));
        assertEquals(1,records().size());
    }
}
