package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,instrumentedPackages="com.codex.mnote")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UnifiedCaptureDraftTest {
    @Test public void restoreUsesPrivateStateAndRejectsAnotherAccountOrExternalPath()throws Exception {
        Context app=RuntimeEnvironment.getApplication();UnifiedCaptureDraft draft=UnifiedCaptureDraft.create(app,null,CaptureSourceContext.EMPTY);
        draft.excerpt="草稿即使未选也保留";draft.selected.remove("excerpt");draft.persist();
        UnifiedCaptureDraft restored=UnifiedCaptureDraft.restore(app,draft.stateFile.getAbsolutePath());
        assertEquals(draft.excerpt,restored.excerpt);assertFalse(restored.selected.contains("excerpt"));
        File external=File.createTempFile("mnote-unified-",".json");
        try {assertThrows(Exception.class,()->UnifiedCaptureDraft.restore(app,external.getAbsolutePath()));}
        finally {assertTrue(external.delete());}
        CaptureAccountSession.preferences(app).edit().putString("scope","b".repeat(64)).commit();
        assertThrows(Exception.class,()->UnifiedCaptureDraft.restore(app,draft.stateFile.getAbsolutePath()));draft.discard(app);
    }
    @Test public void cropPersistFailureRollsBackFilesLayerAndSelection()throws Exception {
        Context app=RuntimeEnvironment.getApplication();UnifiedCaptureDraft draft=UnifiedCaptureDraft.create(app,null,CaptureSourceContext.EMPTY);
        assertTrue(draft.stateFile.delete());assertTrue(draft.stateFile.mkdir());
        Bitmap bitmap=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
        try {
            assertThrows(Exception.class,()->draft.confirmCrop(app,bitmap,bitmap,new JSONObject()));
            assertFalse(draft.selected.contains("crop"));assertNull(draft.cropOriginal);assertNull(draft.cropAnnotated);assertNull(draft.layer);
        } finally{bitmap.recycle();assertTrue(draft.stateFile.delete());}
    }
    @Test public void annotationRestoreIsAtomicAndKeepsOriginalBitmapUnchanged()throws Exception {
        Context app=RuntimeEnvironment.getApplication();Bitmap source=Bitmap.createBitmap(100,160,Bitmap.Config.ARGB_8888);source.eraseColor(0xffabcdef);
        CaptureMarkupView view=new CaptureMarkupView(app);view.setSourceBitmap(source);
        JSONObject layer=new JSONObject().put("sourceWidth",100).put("sourceHeight",160)
                .put("selection",new JSONObject().put("left",10).put("top",20).put("right",80).put("bottom",140))
                .put("strokes",new JSONArray().put(new JSONObject().put("tool","pen").put("width",3)
                        .put("points",new JSONArray().put(new JSONArray().put(20).put(30)).put(new JSONArray().put(40).put(50)))));
        view.restoreAnnotationLayer(layer);assertTrue(view.canUndo());String good=view.annotationLayer().toString();
        JSONObject invalid=new JSONObject(layer.toString());invalid.getJSONObject("selection").put("right",999);
        assertThrows(Exception.class,()->view.restoreAnnotationLayer(invalid));assertEquals(good,view.annotationLayer().toString());
        Bitmap crop=view.renderOriginalSelection();assertEquals(70,crop.getWidth());assertEquals(120,crop.getHeight());
        assertEquals(0xffabcdef,source.getPixel(20,30));assertEquals(0xffabcdef,crop.getPixel(10,10));crop.recycle();
    }
}
