package com.codex.mnote;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Copy-on-write edits. A single atomic pointer publishes metadata and all unchanged image assets. */
final class CaptureRecordEdits {
    static CaptureStore.CaptureRecord latest(Context context,String scope,String id) throws IOException {
        CaptureAccountSession.requireScope(context,scope);
        if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || id.contains("..")) throw new IOException("record_unavailable");
        for(CaptureStore.CaptureRecord record:CaptureRemoteCache.merged(context,CaptureStore.list(context,Integer.MAX_VALUE)))
            if(record.id.equals(id)) return record;
        throw new IOException("record_unavailable");
    }
    static String original(CaptureStore.CaptureRecord record) {
        JSONObject text=record.captureContext.optJSONObject("text");
        return text==null ? "" : text.optString("full_text","");
    }
    static String fingerprint(CaptureStore.CaptureRecord record) throws Exception {
        return fingerprint(record.comment,record.sourceText,original(record));
    }
    static String fingerprint(String comment,String quote,String original) throws Exception {
        return CaptureRemoteCache.digest(new JSONObject().put("comment",comment)
                .put("quote",quote).put("original",original).toString().getBytes(StandardCharsets.UTF_8));
    }
    static CaptureStore.CaptureRecord save(Context context,String scope,String id,String baseline,
            String comment,String quote,String original) throws Exception {
        CaptureStore.CaptureRecord result;
        synchronized(CaptureAccountSession.LOCK) {
            CaptureStore.CaptureRecord current=latest(context,scope,id);
            if(!fingerprint(current).equals(baseline)) throw new IOException("record_changed");
            if(comment.length()>20_000 || quote.length()>100_000 || original.length()>CaptureContext.MAX_TEXT)
                throw new IOException("text_too_long");
            if(!current.hasImage && comment.trim().isEmpty() && quote.trim().isEmpty()
                    && original.trim().isEmpty() && current.sourceUrl.isEmpty()) throw new IOException("empty_record");
            if(comment.equals(current.comment) && quote.equals(current.sourceText) && original.equals(original(current))) return current;
            JSONObject metadata=CaptureStore.readRecordObject(current.metadataFile);
            if(metadata==null) throw new IOException("record_unavailable");
            File canonicalFile=new File(current.metadataFile.getParentFile(),"remote.json");
            JSONObject canonical=CaptureStore.readRecordObject(canonicalFile);
            if(canonicalFile.exists() && canonical==null) throw new IOException("canonical_unavailable");
            if(canonical!=null) {
                for(String key:new String[]{"assets","revision","updated_at","deleted"}) canonical.remove(key);
                metadata.put("canonicalBase",canonical);
            }
            JSONObject evidence=new JSONObject(current.captureContext.toString());
            boolean quoteChanged=!quote.equals(current.sourceText), originalChanged=!original.equals(original(current));
            if(quoteChanged || originalChanged) {
                if(original.isEmpty()) evidence.remove("text");
                else {
                    JSONObject previous=evidence.optJSONObject("text");
                    JSONObject text=CaptureContext.text(original,originalChanged ? "user_edited"
                            : previous==null ? "user_supplied" : previous.optString("origin","user_supplied"),quote);
                    if(!originalChanged && previous!=null) text.put("extent",previous.optString("extent","provided_text"));
                    if(previous!=null) for(String key:new String[]{"relation_to_quote","source_package","source_url"})
                        if(previous.has(key)) text.put(key,previous.get(key));
                    evidence.put("text",text);
                }
                JSONObject edits=evidence.optJSONObject("text_edit");
                if(edits==null) edits=new JSONObject();
                if(quoteChanged) edits.put("quote_modified",true);
                if(originalChanged) edits.put("original_modified",true);
                evidence.put("text_edit",edits.put("edited_at",System.currentTimeMillis()));
            }
            metadata.put("comment",comment).put("sourceText",quote).put("captureContext",evidence)
                    .put("textOnlyEdit",true)
                    .put("editedAt",System.currentTimeMillis()).put("syncLastError",JSONObject.NULL)
                    .put("syncState",CaptureAccountSession.hasAccount(context) ? CaptureStore.SYNC_PENDING : CaptureStore.SYNC_LOCAL_ONLY);
            File root=new File(CaptureStore.inboxDirectory(context),id);
            String version=UUID.randomUUID().toString();
            File destination=new File(root,"edits/"+version);
            if(!destination.mkdirs()) throw new IOException("storage_unavailable");
            for(File asset:new File[]{current.originalFile,current.annotatedFile,current.contextFile}) {
                if(asset==null) continue;
                File target=new File(destination,asset.getName());
                Files.copy(asset.toPath(),target.toPath());
                try(FileOutputStream output=new FileOutputStream(target,true)) {output.getFD().sync();}
            }
            CaptureStore.writeJson(metadata,new File(destination,"record.json"));
            result=CaptureStore.readRecord(destination);
            if(result==null) throw new IOException("incomplete_edit");
            // The previous record remains intact even if writing any staged file fails.
            CaptureStore.writeJson(new JSONObject().put("version",version),new File(root,"current.json"));
        }
        try { context.sendBroadcast(new Intent(CaptureStore.ACTION_RECORDS_CHANGED).setPackage(context.getPackageName())); }
        catch(RuntimeException ignored) { }
        try { CaptureAccountSync.enqueue(context); } catch(RuntimeException ignored) { }
        return result;
    }
    static String errorMessage(Exception error) {
        String code=error.getMessage();
        if("account_changed".equals(code)) return "账号已切换，未保存到其他账号。请返回后重新打开记录。";
        if("record_changed".equals(code)) return "记录已有新修改，本次未覆盖。请先复制输入内容，再重新打开记录核对。";
        if("record_unavailable".equals(code)) return "记录已删除或暂不可用，本次未保存。";
        if("empty_record".equals(code)) return "请至少保留一些内容；删除记录请使用删除功能。";
        if("text_too_long".equals(code)) return "内容超过长度限制，请缩短后再保存。";
        if("canonical_unavailable".equals(code)) return "云端记录的附加信息过大或不可读取，本次未保存，以免丢失原有内容。";
        return "保存失败，输入内容仍保留，请重试。";
    }
}
