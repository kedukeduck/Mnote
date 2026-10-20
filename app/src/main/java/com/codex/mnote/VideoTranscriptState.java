package com.codex.mnote;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** A private, bounded result hand-off. Never put whole transcripts into Binder or saved-state Bundles. */
final class VideoTranscriptState {
    static final int MAX_TEXT=100_000;
    final File file;final String scope;
    String share="",basis="",note="",agreement="",text="",sourceUrl="",problem="",requestId=UUID.randomUUID().toString();
    boolean consent,importSource,creating,imported;
    JSONObject job;
    VideoTranscriptState(File file,String scope){this.file=file;this.scope=scope;}
    static VideoTranscriptState create(Context context,String scope)throws Exception {
        CaptureAccountSession.requireScope(context,scope);
        VideoTranscriptState state=new VideoTranscriptState(File.createTempFile("mnote-video-",".json",context.getCacheDir()).getCanonicalFile(),scope);
        state.persist();return state;
    }
    static VideoTranscriptState read(Context context,String path)throws Exception {
        if(path==null)throw new IOException("missing_transcript");File candidate=new File(path),file=candidate.getCanonicalFile();
        if(Files.isSymbolicLink(candidate.toPath())||!context.getCacheDir().getCanonicalFile().equals(file.getParentFile())
                ||!file.getName().matches("mnote-video-.*\\.json")||!file.isFile()||file.length()>2*1024*1024)throw new IOException("invalid_transcript");
        JSONObject json=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        String scope=json.getString("scope");CaptureAccountSession.requireScope(context,scope);
        VideoTranscriptState state=new VideoTranscriptState(file,scope);
        state.share=json.optString("share");state.basis=json.optString("basis");state.note=json.optString("note");
        state.agreement=json.optString("agreement");state.text=json.optString("text");state.sourceUrl=json.optString("source_url");
        state.consent=json.optBoolean("consent");state.importSource=json.optBoolean("import_source");state.creating=json.optBoolean("creating");
        state.imported=json.optBoolean("imported");state.job=json.optJSONObject("job");state.problem=json.optString("problem");
        state.requestId=json.optString("request_id",state.requestId);
        if(!state.requestId.matches("[a-f0-9-]{36}"))throw new IOException("invalid_job");
        if(state.text.length()>MAX_TEXT||state.share.length()>8192||state.note.length()>1000)throw new IOException("response_too_large");
        if(state.job!=null&&!state.job.optString("id").matches("[a-f0-9-]{36}"))throw new IOException("invalid_job");
        return state;
    }
    synchronized void persist()throws Exception {
        JSONObject json=new JSONObject().put("scope",scope).put("share",share).put("basis",basis).put("note",note)
                .put("agreement",agreement).put("text",text).put("source_url",sourceUrl).put("consent",consent)
                .put("import_source",importSource).put("creating",creating).put("imported",imported).put("problem",problem)
                .put("request_id",requestId)
                .put("job",job==null?JSONObject.NULL:job);
        if(json.toString().getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new IOException("response_too_large");
        CaptureStore.writeJson(json,file,2*1024*1024);
    }
    String id(){return job==null?"":job.optString("id");}
    boolean completed(){return job!=null&&"completed".equals(job.optString("status"));}
    boolean terminal(){return job!=null&&("completed".equals(job.optString("status"))||"failed".equals(job.optString("status")));}
    void discard(){file.delete();}
}
