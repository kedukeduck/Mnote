package com.codex.mnote;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Atomic, account-isolated conversation storage; local wrapper metadata never enters wire JSON. */
final class AiChatStore {
    static final String ACTION_CHANGED = "com.codex.mnote.AI_CHAT_CHANGED";
    static final int MAX_BYTES=8*1024*1024, MAX_MESSAGE=100_000, MAX_MESSAGES=500;
    static final Object LOCK = new Object();
    static String now() { return Instant.now().toString(); }
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static void requireId(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("conversation_unavailable");
        try { UUID.fromString(id); } catch (RuntimeException error) { throw new IOException("conversation_unavailable"); }
    }
    static File directory(Context context, String scope) throws IOException {
        if (!scope.equals("guest") && !scope.matches("[a-f0-9]{64}")) throw new IOException("account_changed");
        File dir=new File(context.getFilesDir(),"ai_chats/"+scope);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("chat_storage_failed");
        return dir;
    }
    static JSONObject read(Context context,String scope,String id) throws Exception {
        CaptureAccountSession.requireScope(context,scope); requireId(id);
        AtomicFile file=new AtomicFile(new File(directory(context,scope),id+".json"));
        if (!file.getBaseFile().exists()) throw new IOException("conversation_unavailable");
        if(file.getBaseFile().length()>MAX_BYTES+1024*1024) throw new IOException("conversation_too_large");
        return new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
    }
    static void write(Context context,String scope,String id,JSONObject wrapper) throws Exception {
        CaptureAccountSession.requireScope(context,scope); requireId(id);
        JSONObject conversation=wrapper.optJSONObject("conversation");
        if(conversation!=null) validate(conversation);
        AtomicFile file=new AtomicFile(new File(directory(context,scope),id+".json")); FileOutputStream stream=null;
        try { stream=file.startWrite(); stream.write(wrapper.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch(Exception error) { file.failWrite(stream); throw error; }
        try { context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName())); }
        catch(RuntimeException ignored) { /* The atomic write already succeeded; notification failure is not data loss. */ }
    }
    static void validate(JSONObject conversation) throws Exception {
        if(conversation.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw new IOException("conversation_too_large");
        JSONArray messages=conversation.getJSONArray("messages");
        if(messages.length()>MAX_MESSAGES) throw new IOException("conversation_too_long");
        for(int i=0;i<messages.length();i++) if(messages.getJSONObject(i).optString("content").length()>MAX_MESSAGE)
            throw new IOException("message_too_long");
    }
    static List<String> ids(Context context,String scope) throws Exception {
        CaptureAccountSession.requireScope(context,scope);
        List<String> result=new ArrayList<>(); File[] files=directory(context,scope).listFiles();
        if(files!=null) for(File file:files) if(file.getName().matches("[0-9a-f-]{36}\\.json")) result.add(file.getName().substring(0,36));
        return result;
    }
    static CaptureStore.CaptureRecord record(Context context,String scope,String id) throws Exception {
        return CaptureRecordEdits.latest(context,scope,id);
    }
    static JSONObject snapshot(Context context,String recordId,Set<String> modules) throws Exception {
        String scope=CaptureAccountSession.scope(context); CaptureStore.CaptureRecord record;
        synchronized(CaptureAccountSession.LOCK) { record=record(context,scope,recordId); }
        Set<String> selected=new LinkedHashSet<>();
        for(String key:Arrays.asList("thought","excerpt","original","images","metadata")) if(modules.contains(key)) selected.add(key);
        JSONObject result=new JSONObject().put("record_id",record.id).put("fingerprint",CaptureRecordEdits.fingerprint(record))
                .put("record_revision",record.serverRevision).put("record_created_at",selected.contains("metadata")?Instant.ofEpochMilli(record.createdAt).toString():"")
                .put("thought",selected.contains("thought")?record.comment:"")
                .put("excerpt",selected.contains("excerpt")?record.sourceText:"")
                .put("original",selected.contains("original")?CaptureRecordEdits.original(record):"")
                .put("kind",selected.contains("metadata")?record.kind:"")
                .put("tags",selected.contains("metadata")?record.tags:new JSONArray())
                .put("source_url",selected.contains("metadata")?record.sourceUrl:"")
                .put("source_app",selected.contains("metadata")?record.sourcePackage:"")
                .put("source_type",selected.contains("metadata")?record.sourceType:"")
                .put("context_note",contextNote(record,selected))
                .put("modules",new JSONArray(selected)).put("consent","record_chat_only");
        JSONArray images=new JSONArray();
        if(selected.contains("images")) {
            File crop=record.annotatedFile!=null && record.annotatedFile.isFile()?record.annotatedFile:record.originalFile;
            JSONObject imageMetadata=record.captureContext.optJSONObject("image");
            boolean contextOnly=imageMetadata!=null && "page_context".equals(imageMetadata.optString("purpose"));
            if(contextOnly) {
                File full=record.contextFile!=null && record.contextFile.isFile()?record.contextFile:crop;
                if(full!=null) images.put(image(full,"当时的完整页面截图（未指定摘录位置）"));
            } else {
                if(record.hasImage) images.put(image(crop,"圈选截图"));
                if(record.contextFile!=null && record.contextFile.isFile()) images.put(image(record.contextFile,"完整页面截图"));
            }
        }
        result.put("images",images); CaptureAccountSession.requireScope(context,scope);
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>6*1024*1024) throw new IOException("snapshot_too_large");
        return result;
    }
    private static String contextNote(CaptureStore.CaptureRecord record,Set<String> selected) {
        StringBuilder text=new StringBuilder();
        if(selected.contains("original")) text.append("原文仅为保存时的页面内容，可能不完整。");
        JSONObject metadata=record.captureContext.optJSONObject("image");
        if(selected.contains("excerpt") && "clipboard".equals(record.sourceType)) text.append("摘录来自剪贴板，和当前页面的关联未经确认。");
        if(selected.contains("images") && metadata!=null) {
            if("page_context".equals(metadata.optString("purpose"))) text.append("页面截图记录当时环境，不表示摘录位于图中某个选区。");
            else if(metadata.optBoolean("retained") && metadata.optJSONObject("selection")!=null) {
                JSONObject bounds=metadata.optJSONObject("selection");
                text.append("圈选图来自完整截图中的矩形区域，原始完整截图尺寸为 ")
                        .append(metadata.optInt("width")).append("×").append(metadata.optInt("height"))
                        .append(" 像素，矩形 left/top/right/bottom=")
                        .append(bounds.optDouble("left")).append('/').append(bounds.optDouble("top")).append('/')
                        .append(bounds.optDouble("right")).append('/').append(bounds.optDouble("bottom"))
                        .append("。提供给模型的图片可能等比例降采样；该坐标以原始完整截图为准。");
            }
        }
        return text.toString();
    }
    private static JSONObject image(File file,String label) throws Exception {
        if(file==null || !file.isFile() || file.length()>16L*1024*1024) throw new IOException("image_unavailable");
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        if(bounds.outWidth<1 || bounds.outHeight<1 || (long)bounds.outWidth*bounds.outHeight>64_000_000) throw new IOException("image_invalid");
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1600) options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(bitmap==null) throw new IOException("image_invalid");
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,85,bytes)) throw new IOException("image_invalid");
            if(bytes.size()>2*1024*1024) throw new IOException("image_too_large");
            return new JSONObject().put("label",label).put("content_type","image/jpeg")
                    .put("data_base64",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP));
        } finally { bitmap.recycle(); }
    }
    static String create(Context context,JSONObject snapshot,String profileId) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); record(context,scope,snapshot.getString("record_id"));
            AiModelPreferences.Config profile=AiModelPreferences.load(context,profileId);
            String id=UUID.randomUUID().toString(), time=now();
            JSONObject conversation=new JSONObject().put("schema_version",1).put("id",id).put("record_id",snapshot.getString("record_id"))
                    .put("title","新对话").put("created_at",time).put("updated_at",time).put("snapshot",copy(snapshot))
                    .put("model",profile.metadata()).put("messages",new JSONArray());
            write(context,scope,id,new JSONObject().put("conversation",conversation).put("profile_id",profile.id)
                    .put("revision",0).put("dirty",false).put("draft","")); return id;
        } }
    }
    static JSONObject get(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject wrapper=read(context,scope,id);
            if(wrapper.optBoolean("deleted")) throw new IOException("conversation_unavailable");
            JSONObject conversation=wrapper.getJSONObject("conversation"); record(context,scope,conversation.getString("record_id"));
            return copy(conversation);
        } }
    }
    static List<JSONObject> list(Context context,String recordId) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); List<JSONObject> result=new ArrayList<>();
            AiChatSync.pruneDeletedRecords(context,scope);
            Set<String> live=new HashSet<>(); for(CaptureStore.CaptureRecord record:CaptureRemoteCache.merged(context,CaptureStore.list(context,Integer.MAX_VALUE))) live.add(record.id);
            for(String id:ids(context,scope)) {
                JSONObject wrapper=read(context,scope,id), c=wrapper.optJSONObject("conversation");
                if(wrapper.optBoolean("deleted") || c==null || c.getJSONArray("messages").length()==0 || !live.contains(c.getString("record_id"))) continue;
                if(recordId==null || recordId.equals(c.getString("record_id"))) result.add(copy(c));
            }
            result.sort((a,b)->b.optString("updated_at").compareTo(a.optString("updated_at"))); return result;
        } }
    }
    static Map<String,Integer> completedCounts(Context context) throws Exception {
        Map<String,Integer> counts=new HashMap<>();
        for(JSONObject c:list(context,null)) { JSONArray messages=c.getJSONArray("messages");
            for(int i=0;i<messages.length();i++) { JSONObject m=messages.getJSONObject(i);
                if("assistant".equals(m.optString("role")) && "complete".equals(m.optString("status")) && !m.optString("content").isEmpty()) {
                    String recordId=c.getString("record_id"); counts.put(recordId,counts.getOrDefault(recordId,0)+1); break;
                }
            }
        } return counts;
    }
    static String draft(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) { return read(context,CaptureAccountSession.scope(context),id).optString("draft"); } }
    }
    static String profileId(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) { return read(context,CaptureAccountSession.scope(context),id).optString("profile_id"); } }
    }
    static void saveDraft(Context context,String id,String text) throws Exception {
        if(text.length()>MAX_MESSAGE) throw new IOException("message_too_long");
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject wrapper=read(context,scope,id); wrapper.put("draft",text); write(context,scope,id,wrapper);
        } }
    }
    static boolean needsConsent(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject wrapper=read(context,scope,id);
            CaptureStore.CaptureRecord r=record(context,scope,wrapper.getJSONObject("conversation").getString("record_id"));
            return !r.aiAccess.equals(wrapper.optString("consent_policy"));
        } }
    }
    static void authorize(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject wrapper=read(context,scope,id);
            CaptureStore.CaptureRecord r=record(context,scope,wrapper.getJSONObject("conversation").getString("record_id"));
            wrapper.put("consent_policy",r.aiAccess); write(context,scope,id,wrapper);
        } }
    }
    static void rename(Context context,String id,String title) throws Exception {
        if(title.trim().isEmpty() || title.length()>200) throw new IOException("invalid_title");
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject w=read(context,scope,id);
            w.getJSONObject("conversation").put("title",title.trim()).put("updated_at",now()); w.put("dirty",true); write(context,scope,id,w);
        } }
    }
    static void delete(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject w=read(context,scope,id);
            AiChatClient.cancel(context,id); String recordId=w.getJSONObject("conversation").getString("record_id");
            write(context,scope,id,new JSONObject().put("deleted",true).put("dirty",true).put("revision",w.optInt("revision"))
                    .put("record_id",recordId));
        } }
    }
    static void deleteForRecord(Context context,String recordId) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context);
            for(String id:ids(context,scope)) { JSONObject w=read(context,scope,id), c=w.optJSONObject("conversation");
                if(c!=null && recordId.equals(c.optString("record_id"))) delete(context,id);
            }
        } }
    }
    static String beginTurn(Context context,String id,String text) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject w=read(context,scope,id);
            if(w.optBoolean("deleted") || needsConsent(context,id)) throw new IOException("consent_required");
            JSONObject c=w.getJSONObject("conversation"); JSONArray messages=c.getJSONArray("messages");
            if(text.trim().isEmpty() || text.length()>MAX_MESSAGE) throw new IOException("message_too_long");
            if(messages.length()>0 && "generating".equals(messages.getJSONObject(messages.length()-1).optString("status"))) throw new IOException("conversation_busy");
            String request=UUID.randomUUID().toString(), time=now();
            if(messages.length()==0) c.put("title",text.substring(0,Math.min(50,text.length())));
            messages.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("role","user").put("content",text)
                    .put("created_at",time).put("status","complete").put("request_id",request).put("model",c.getJSONObject("model").getString("model")).put("error",""));
            messages.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("role","assistant").put("content","")
                    .put("created_at",time).put("status","generating").put("request_id",request).put("model",c.getJSONObject("model").getString("model")).put("error",""));
            c.put("updated_at",time); w.put("dirty",true).put("draft",""); write(context,scope,id,w); return request;
        } }
    }
    static String beginRetry(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject w=read(context,scope,id);
            if(w.optBoolean("deleted") || needsConsent(context,id)) throw new IOException("consent_required");
            JSONObject c=w.getJSONObject("conversation"); JSONArray messages=c.getJSONArray("messages");
            if(messages.length()==0) throw new IOException("retry_unavailable");
            JSONObject last=messages.getJSONObject(messages.length()-1);
            if(!"assistant".equals(last.optString("role")) || !("failed".equals(last.optString("status")) || "stopped".equals(last.optString("status"))))
                throw new IOException("retry_unavailable");
            String request=UUID.randomUUID().toString(), time=now();
            messages.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("role","assistant").put("content","")
                    .put("created_at",time).put("status","generating").put("request_id",request)
                    .put("model",c.getJSONObject("model").getString("model")).put("error",""));
            c.put("updated_at",time); w.put("dirty",true); write(context,scope,id,w); return request;
        } }
    }
    static void recoverInterrupted(Context context,String id) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject w=read(context,scope,id);
            JSONObject c=w.getJSONObject("conversation"); JSONArray messages=c.getJSONArray("messages");
            if(messages.length()==0) return;
            JSONObject last=messages.getJSONObject(messages.length()-1);
            if(!"generating".equals(last.optString("status"))) return;
            if(w.optInt("revision")>0 && Instant.parse(c.getString("updated_at")).plusSeconds(330).isAfter(Instant.now())) throw new IOException("conversation_busy");
            finish(context,id,last.getString("request_id"),last.optString("content"),"failed","response_incomplete");
        } }
    }
    static void finish(Context context,String id,String requestId,String text,String status,String error) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(LOCK) {
            String scope=CaptureAccountSession.scope(context); JSONObject w=read(context,scope,id);
            if(w.optBoolean("deleted")) throw new IOException("conversation_unavailable");
            JSONObject c=w.getJSONObject("conversation"); JSONArray messages=c.getJSONArray("messages");
            JSONObject last=messages.getJSONObject(messages.length()-1);
            record(context,scope,c.getString("record_id"));
            if(!requestId.equals(last.optString("request_id")) || !"assistant".equals(last.optString("role"))) throw new IOException("revision_conflict");
            if(!"generating".equals(last.optString("status"))) throw new IOException("revision_conflict");
            if(!Arrays.asList("generating","complete","stopped","failed").contains(status)) throw new IOException("invalid_status");
            last.put("content",text).put("status",status).put("error",error==null?"":error);
            c.put("updated_at",now()); w.put("dirty",true); write(context,scope,id,w);
        } }
    }
}
