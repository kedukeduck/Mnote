package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/** Private, bounded recovery state. Bundles contain its filename, never articles or bitmaps. */
final class UnifiedCaptureDraft {
    final File stateFile;
    final String scope;
    final Set<String> selected = new LinkedHashSet<>();
    String thought="", excerpt="", original="", url="", tags="", kind="thought";
    String recordId="";
    boolean clipboardRead, pageRead, editing;
    CaptureSourceContext source=CaptureSourceContext.EMPTY;
    File screenshot, cropOriginal, cropAnnotated;
    JSONObject layer, editingLayer;

    private UnifiedCaptureDraft(File file,String scope) {this.stateFile=file;this.scope=scope;}
    static UnifiedCaptureDraft create(Context context,File screenshot,CaptureSourceContext source) throws IOException {
        UnifiedCaptureDraft draft=new UnifiedCaptureDraft(File.createTempFile("mnote-unified-",".json",context.getCacheDir()).getCanonicalFile(),CaptureAccountSession.scope(context));
        draft.screenshot=screenshot;draft.source=source==null?CaptureSourceContext.EMPTY:source;
        draft.url=draft.source.url;draft.selected.add("thought");
        if(screenshot!=null)draft.selected.add("context");
        if(!draft.source.appPackage.isEmpty()||!draft.url.isEmpty())draft.selected.add("source");
        draft.persist();return draft;
    }
    static UnifiedCaptureDraft restore(Context context,String path) throws Exception {
        if(path==null)throw new IOException("Missing editor state");
        File candidate=new File(path),file=candidate.getCanonicalFile();
        if(Files.isSymbolicLink(candidate.toPath())||!context.getCacheDir().getCanonicalFile().equals(file.getParentFile())
                ||!file.getName().startsWith("mnote-unified-")||!file.getName().endsWith(".json")
                ||!file.isFile()||file.length()>4L*1024*1024)throw new IOException("Invalid editor state");
        JSONObject data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        String scope=data.getString("scope");CaptureAccountSession.requireScope(context,scope);
        UnifiedCaptureDraft draft=new UnifiedCaptureDraft(file,scope);
        draft.thought=data.optString("thought");draft.excerpt=data.optString("excerpt");draft.original=data.optString("original");
        draft.url=data.optString("url");draft.tags=data.optString("tags");draft.kind=data.optString("kind","thought");
        draft.clipboardRead=data.optBoolean("clipboard");draft.pageRead=data.optBoolean("page");draft.editing=data.optBoolean("editing");
        draft.recordId=data.optString("record");
        draft.source=new CaptureSourceContext(data.optString("package"),data.optString("detected_url"),data.optString("origin"));
        draft.screenshot=CaptureStore.safeDraftFile(context,data.optString("screenshot"));
        draft.cropOriginal=CaptureStore.safeDraftFile(context,data.optString("crop_original"));
        draft.cropAnnotated=CaptureStore.safeDraftFile(context,data.optString("crop_annotated"));
        draft.layer=data.optJSONObject("layer");draft.editingLayer=data.optJSONObject("editing_layer");
        JSONArray selected=data.optJSONArray("selected");
        if(selected!=null)for(int i=0;i<selected.length();i++)draft.selected.add(selected.optString(i));
        return draft;
    }
    synchronized void persist() throws IOException {
        try {
            JSONObject data=new JSONObject().put("scope",scope).put("thought",thought).put("excerpt",excerpt)
                    .put("original",original).put("url",url).put("tags",tags).put("kind",kind)
                    .put("clipboard",clipboardRead).put("page",pageRead).put("editing",editing).put("record",recordId)
                    .put("package",source.appPackage).put("detected_url",source.url).put("origin",source.origin)
                    .put("selected",new JSONArray(selected)).put("screenshot",path(screenshot))
                    .put("crop_original",path(cropOriginal)).put("crop_annotated",path(cropAnnotated))
                    .put("layer",layer==null?JSONObject.NULL:layer).put("editing_layer",editingLayer==null?JSONObject.NULL:editingLayer);
            if(data.toString().getBytes(StandardCharsets.UTF_8).length>4L*1024*1024)throw new IOException("Editor draft too large");
            CaptureStore.writeJson(data,stateFile);
        } catch(org.json.JSONException error) {throw new IOException("Cannot persist editor state",error);}
    }
    synchronized void confirmCrop(Context context,Bitmap original,Bitmap annotated,JSONObject layer) throws IOException {
        File first=null,second=null;
        File oldFirst=cropOriginal,oldSecond=cropAnnotated;JSONObject oldLayer=this.layer;
        boolean oldEditing=editing,oldSelected=selected.contains("crop");
        try {
            first=CaptureStore.writeDraftBitmap(context,original);second=CaptureStore.writeDraftBitmap(context,annotated);
            cropOriginal=first;cropAnnotated=second;this.layer=layer;editing=false;selected.add("crop");
            persist();
        } catch(IOException|RuntimeException error) {
            cropOriginal=oldFirst;cropAnnotated=oldSecond;this.layer=oldLayer;editing=oldEditing;
            if(!oldSelected)selected.remove("crop");
            CaptureStore.discardDraft(context,first);CaptureStore.discardDraft(context,second);throw error;
        }
        CaptureStore.discardDraft(context,oldFirst);CaptureStore.discardDraft(context,oldSecond);
    }
    synchronized void discard(Context context) {
        CaptureStore.discardDraft(context,screenshot);CaptureStore.discardDraft(context,cropOriginal);
        CaptureStore.discardDraft(context,cropAnnotated);stateFile.delete();
    }
    private static String path(File file) {return file==null?"":file.getAbsolutePath();}
}
