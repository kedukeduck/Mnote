package com.codex.mnote;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;

/** Chat CAS sync is separate from capture revisions and never holds the account mutex over I/O. */
final class AiChatSync {
    static final Object NETWORK_LOCK=new Object();
    interface Transport { JSONObject request(String method,String path,JSONObject body,Integer revision) throws Exception; }
    static Transport transport(CaptureSyncPreferences.Config config) {
        return (method,path,body,revision)->CaptureAccountHttp.request(config.baseUrl,method,path,config.writeToken,body,revision);
    }
    static int run(Context context) throws Exception {
        if(!CaptureAccountSession.hasAccount(context)) { pruneDeletedRecords(context,CaptureAccountSession.scope(context)); return 0; }
        CaptureSyncPreferences.Config config;
        synchronized(CaptureAccountSession.LOCK) { config=CaptureAccountSession.config(context); }
        return run(context,config.accountKey,transport(config));
    }
    static int run(Context context,String scope,Transport remote) throws Exception {
        synchronized(NETWORK_LOCK) {
            CaptureAccountSession.requireScope(context,scope);
            pruneDeletedRecords(context,scope);
            int changed=pull(context,scope,remote); List<String> ids;
            synchronized(CaptureAccountSession.LOCK) { synchronized(AiChatStore.LOCK) { ids=AiChatStore.ids(context,scope); } }
            boolean conflict=false;
            for(String id:ids) {
                try { if(push(context,scope,id,remote)) changed++; }
                catch(IOException error) {
                    if("revision_conflict".equals(error.getMessage())) conflict=true; else throw error;
                }
            }
            changed+=pull(context,scope,remote);
            if(conflict) throw new IOException("revision_conflict");
            return changed;
        }
    }
    static void push(Context context,String id) throws Exception {
        CaptureSyncPreferences.Config config;
        synchronized(CaptureAccountSession.LOCK) {
            if(!CaptureAccountSession.hasAccount(context)) return;
            config=CaptureAccountSession.config(context);
        }
        synchronized(NETWORK_LOCK) { push(context,config.accountKey,id,transport(config)); }
    }
    static boolean push(Context context,String scope,String id,Transport remote) throws Exception {
        JSONObject initial; int revision; boolean deleted; JSONObject body;
        synchronized(CaptureAccountSession.LOCK) { synchronized(AiChatStore.LOCK) {
            initial=AiChatStore.read(context,scope,id);
            if(!initial.optBoolean("dirty")) return false;
            revision=initial.optInt("revision"); deleted=initial.optBoolean("deleted");
            body=deleted?null:AiChatStore.copy(initial.getJSONObject("conversation"));
            if(!deleted) {
                AiChatStore.record(context,scope,body.getString("record_id"));
                if(body.getJSONArray("messages").length()==0) return false;
                // Renaming a downloaded history item is not a new disclosure to a model.
                // New snapshots still require explicit local grant before their first upload.
                if(revision==0 && !initial.has("consent_policy")) throw new IOException("consent_required");
            }
            if(deleted && revision==0) { initial.put("dirty",false); AiChatStore.write(context,scope,id,initial); return true; }
        } }
        JSONObject response;
        try { response=remote.request(deleted?"DELETE":"PUT","/v1/chat/conversations/"+id,body,revision); }
        catch(IOException error) {
            if("http_409".equals(error.getMessage())) {
                JSONObject latest=remote.request("GET","/v1/chat/conversations/"+id,null,null);
                boolean conflict;
                synchronized(CaptureAccountSession.LOCK) { synchronized(AiChatStore.LOCK) {
                    JSONObject w=AiChatStore.read(context,scope,id); conflict=recoverConflict(context,scope,id,w,latest);
                } }
                if(conflict) throw new IOException("revision_conflict");
                return true;
            }
            if(deleted && "http_404".equals(error.getMessage())) response=new JSONObject().put("revision",revision);
            else throw error;
        }
        synchronized(CaptureAccountSession.LOCK) { synchronized(AiChatStore.LOCK) {
            JSONObject latest=AiChatStore.read(context,scope,id);
            // A concurrently persisted partial/draft/delete is never replaced by an older upload acknowledgement.
            latest.put("revision",response.getInt("revision"));
            boolean same=deleted?latest.optBoolean("deleted"):
                    !latest.optBoolean("deleted") && body.toString().equals(latest.getJSONObject("conversation").toString());
            latest.put("dirty",!same); AiChatStore.write(context,scope,id,latest);
        } }
        return true;
    }
    static int pull(Context context,String scope,Transport remote) throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("ai_chat_sync_"+scope,Context.MODE_PRIVATE);
        long cursor=prefs.getLong("cursor",0); int changed=0; boolean recovered=false;
        for(int pageIndex=0;pageIndex<40;pageIndex++) {
            CaptureAccountSession.requireScope(context,scope);
            JSONObject page=remote.request("GET","/v1/chat/changes?after="+cursor+"&limit=100",null,null);
            JSONArray changes=page.getJSONArray("changes"); long previous=cursor;
            for(int i=0;i<changes.length();i++) {
                JSONObject change=changes.getJSONObject(i); long sequence=change.getLong("sequence");
                if(sequence<=previous) throw new IOException("invalid_chat_cursor"); previous=sequence;
                String id=change.getString("id"); AiChatStore.requireId(id); JSONObject incoming=null;
                if(!change.optBoolean("deleted")) {
                    try { incoming=remote.request("GET","/v1/chat/conversations/"+id,null,null); }
                    catch(IOException error) { if(!"http_404".equals(error.getMessage())) throw error; }
                }
                synchronized(CaptureAccountSession.LOCK) { synchronized(AiChatStore.LOCK) {
                    CaptureAccountSession.requireScope(context,scope); JSONObject existing=null;
                    try { existing=AiChatStore.read(context,scope,id); }
                    catch(IOException error) { if(!"conversation_unavailable".equals(error.getMessage())) throw error; }
                    int revision=incoming==null?change.getInt("revision"):incoming.getInt("revision");
                    if(incoming==null) {
                        AiChatClient.cancel(context,id);
                        AiChatStore.write(context,scope,id,new JSONObject().put("deleted",true).put("dirty",false).put("revision",revision)
                                .put("record_id",change.getString("record_id"))); changed++; continue;
                    }
                    if(!id.equals(incoming.getString("id")) || !change.getString("record_id").equals(incoming.getString("record_id"))) throw new IOException("invalid_chat_response");
                    incoming.remove("revision"); AiChatStore.validate(incoming);
                    if(existing!=null && revision<=existing.optInt("revision")) continue;
                    if(existing!=null && existing.optBoolean("dirty")) {
                        incoming.put("revision",revision); recovered|=recoverConflict(context,scope,id,existing,incoming); changed++; continue;
                    }
                    JSONObject wrapper=existing==null?new JSONObject().put("draft",""):existing;
                    wrapper.put("conversation",incoming).put("revision",revision).put("dirty",false).put("deleted",false).put("conflict",false);
                    AiChatStore.write(context,scope,id,wrapper); changed++;
                } }
            }
            long next=page.getLong("next_cursor");
            if(next!=previous || (page.getBoolean("has_more") && next<=cursor)) throw new IOException("invalid_chat_cursor");
            synchronized(CaptureAccountSession.LOCK) { CaptureAccountSession.requireScope(context,scope);
                if(!prefs.edit().putLong("cursor",next).commit()) throw new IOException("chat_storage_failed"); }
            cursor=next; if(!page.getBoolean("has_more")) { if(recovered) throw new IOException("revision_conflict"); return changed; }
        }
        throw new IOException("more_chats_pending");
    }
    /** Keep every locally authored message visible in a separate conversation; never overwrite the remote reply. */
    private static boolean recoverConflict(Context context,String scope,String id,JSONObject local,JSONObject remote) throws Exception {
        if(!id.equals(remote.getString("id"))) throw new IOException("invalid_chat_response");
        int revision=remote.getInt("revision"); JSONObject incoming=AiChatStore.copy(remote); incoming.remove("revision");
        if(local.optBoolean("deleted")) {
            // A user deletion remains a deletion; the next CAS uses the refreshed revision.
            local.put("revision",revision).put("dirty",true).put("conflict",false); AiChatStore.write(context,scope,id,local); return true;
        }
        JSONObject original=local.getJSONObject("conversation");
        if(!original.getString("record_id").equals(incoming.getString("record_id"))) throw new IOException("invalid_chat_response");
        if(equivalent(original,incoming)) {
            local.put("revision",revision).put("dirty",false).put("conflict",false);
            AiChatStore.write(context,scope,id,local); return false;
        }
        JSONArray originalMessages=original.getJSONArray("messages");
        if(local.optString("draft").isEmpty() && originalMessages.length()>0
                && "generating".equals(originalMessages.getJSONObject(originalMessages.length()-1).optString("status"))) {
            for(int i=originalMessages.length()-1;i>=0;i--) if("user".equals(originalMessages.getJSONObject(i).optString("role"))) {
                local.put("draft",originalMessages.getJSONObject(i).optString("content")); break;
            }
        }
        JSONObject fork=AiChatStore.copy(local), body=fork.getJSONObject("conversation");
        String newId=UUID.randomUUID().toString(); body.put("id",newId).put("updated_at",AiChatStore.now())
                .put("title","本机冲突副本 · "+body.optString("title").substring(0,Math.min(180,body.optString("title").length())));
        JSONArray messages=body.getJSONArray("messages");
        if(messages.length()>0) { JSONObject last=messages.getJSONObject(messages.length()-1);
            if("generating".equals(last.optString("status"))) last.put("status","failed").put("error","revision_conflict");
        }
        fork.put("revision",0).put("dirty",true).put("conflict",false);
        AiChatStore.write(context,scope,newId,fork);
        local.put("conversation",incoming).put("revision",revision).put("dirty",false).put("conflict",false);
        AiChatStore.write(context,scope,id,local);
        return true;
    }
    static boolean equivalent(Object left,Object right) throws Exception {
        if(left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a=(JSONObject)left,b=(JSONObject)right; if(a.length()!=b.length()) return false;
            Iterator<String> keys=a.keys(); while(keys.hasNext()) { String key=keys.next(); if(!b.has(key) || !equivalent(a.get(key),b.get(key))) return false; }
            return true;
        }
        if(left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a=(JSONArray)left,b=(JSONArray)right; if(a.length()!=b.length()) return false;
            for(int i=0;i<a.length();i++) if(!equivalent(a.get(i),b.get(i))) return false; return true;
        }
        if(left instanceof Number && right instanceof Number) return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString()))==0;
        return Objects.equals(left,right);
    }
    static void pruneDeletedRecords(Context context,String scope) throws Exception {
        synchronized(CaptureAccountSession.LOCK) { synchronized(AiChatStore.LOCK) {
            CaptureAccountSession.requireScope(context,scope);
            Set<String> deleted=CaptureDeletionStore.hidden(context);
            if(!"guest".equals(scope)) deleted.addAll(CaptureRemoteCache.deletedIds(context,scope));
            for(String id:AiChatStore.ids(context,scope)) {
                JSONObject w=AiChatStore.read(context,scope,id), c=w.optJSONObject("conversation");
                if(c!=null && deleted.contains(c.optString("record_id"))) {
                    AiChatClient.cancel(context,id);
                    AiChatStore.write(context,scope,id,new JSONObject().put("deleted",true).put("dirty",w.optInt("revision")>0)
                            .put("record_id",c.getString("record_id")).put("revision",w.optInt("revision")));
                }
            }
        } }
    }
}
