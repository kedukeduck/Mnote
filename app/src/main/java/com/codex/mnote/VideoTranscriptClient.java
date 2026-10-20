package com.codex.mnote;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Only the account server is contacted. Video URLs and transcript-service secrets are never fetched here. */
final class VideoTranscriptClient {
    static final int REQUEST_TIMEOUT_MILLIS=30_000;
    interface ConnectionFactory {HttpURLConnection open(URI target)throws IOException;}
    // A platform DNS lookup cannot always be interrupted. Bound its threads as
    // well as its caller's wait, instead of accumulating one thread per retry.
    private static final ExecutorService NETWORK=new ThreadPoolExecutor(0,2,20,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"mnote-video-network");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    static final class Rejected extends IOException {final int status;Rejected(String code,int status){super(code);this.status=status;}}
    static JSONObject request(Context context,String scope,String method,String suffix,JSONObject body) throws Exception {
        return request(context,scope,method,suffix,body,target->(HttpURLConnection)target.toURL().openConnection());
    }
    static JSONObject request(Context context,String scope,String method,String suffix,JSONObject body,ConnectionFactory factory)throws Exception {
        return request(context,scope,method,suffix,body,factory,REQUEST_TIMEOUT_MILLIS);
    }
    static JSONObject request(Context context,String scope,String method,String suffix,JSONObject body,
                              ConnectionFactory factory,int timeoutMillis)throws Exception {
        if(timeoutMillis<1||timeoutMillis>REQUEST_TIMEOUT_MILLIS)throw new IOException("invalid_timeout");
        if(!suffix.equals("/config")&&!suffix.equals("/jobs")&&!suffix.matches("/jobs/[a-f0-9-]{36}"))throw new IOException("invalid_job");
        if(!("GET".equals(method)||"POST".equals(method)||"DELETE".equals(method)))throw new IOException("invalid_method");
        CaptureAccountSession.requireScope(context,scope);
        CaptureSyncPreferences.Config config=CaptureAccountSession.snapshot(context,scope);
        URI target=URI.create(CaptureSyncPreferences.validateAndNormalizeBaseUrl(config.baseUrl)+"/v1/transcripts"+suffix);
        if(!"https".equals(target.getScheme()))throw new IOException("https_required");
        byte[] bytes=body==null?null:body.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes!=null&&bytes.length>16*1024)throw new IOException("request_too_large");
        Future<JSONObject> pending;
        try {pending=NETWORK.submit(()->exchange(context,scope,method,target,config.writeToken,bytes,factory,timeoutMillis));}
        catch(RejectedExecutionException error){throw new IOException("connection_busy");}
        try {
            JSONObject result=pending.get(timeoutMillis,TimeUnit.MILLISECONDS);
            CaptureAccountSession.requireScope(context,scope);return result;
        }catch(TimeoutException error){pending.cancel(true);throw new IOException("request_timeout");}
        catch(InterruptedException error){pending.cancel(true);Thread.currentThread().interrupt();throw new IOException("cancelled");}
        catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("network_failed");}
    }
    private static void check(Context context,String scope)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("cancelled");
        CaptureAccountSession.requireScope(context,scope);
    }
    private static JSONObject exchange(Context context,String scope,String method,URI target,String token,byte[] bytes,
                                       ConnectionFactory factory,int timeoutMillis)throws Exception {
        check(context,scope);HttpURLConnection connection=factory.open(target);
        try {
            check(context,scope);
            connection.setConnectTimeout(Math.min(10_000,timeoutMillis));connection.setReadTimeout(Math.min(20_000,timeoutMillis));connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Cache-Control","no-store");
            if(bytes!=null){connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");
                try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            JSONObject response=new JSONObject();
            if(stream!=null)try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))!=-1){check(context,scope);if(output.size()+read>2*1024*1024)throw new IOException("response_too_large");output.write(buffer,0,read);}
                response=new JSONObject(output.toString("UTF-8"));
            }
            check(context,scope);
            if(status<200||status>=300){JSONObject error=response.optJSONObject("error");throw new Rejected(error==null?"http_"+status:error.optString("code","http_"+status),status);}
            return response;
        } finally {connection.disconnect();}
    }
    static String error(Exception exception) {
        String code=exception.getMessage()==null?"":exception.getMessage();
        if(code.contains("account_changed"))return "账号已变化，请返回后重新打开。";
        if(code.contains("login")||code.contains("401")||code.equals("unauthorized")||code.equals("invalid_session"))return "登录状态已过期或失效，请到「设置 → 账号与同步」重新登录。";
        if(code.equals("forbidden"))return "当前凭据没有文字稿解析权限，请使用 Mnote 账号重新登录。";
        if(code.equals("connection_busy"))return "上次网络连接仍在结束，请稍后重新连接；不会重复提交任务。";
        if(code.contains("timeout")||exception instanceof SocketTimeoutException)return "连接解析服务超时，请点击「重新连接服务」重试；已有内容没有变化。";
        if(code.contains("not_configured")||code.contains("404"))return "当前同步服务器尚未启用视频文字稿服务，请更新服务端。";
        if(code.contains("image_post"))return "这是一条图文作品，暂不支持视频文字稿。";
        if(code.contains("platform_restricted")||code.contains("source_forbidden"))return "来源平台暂时无法公开访问这条视频，请稍后重试。";
        if(code.contains("limit")||code.contains("busy")||code.contains("429"))return "解析任务或今日额度已满，请稍后再试。";
        if(code.contains("too_long"))return "视频超过服务允许时长，请换一条较短的视频。";
        if(code.contains("too_large"))return "视频或文字稿过大，未截断任何内容，请换一条较短的视频。";
        if(code.contains("unsafe_url")||code.contains("unsupported")||code.contains("invalid_url"))return "链接不受支持，请填写一条公开的 B 站、抖音视频分享链接。";
        if(code.contains("submission_uncertain"))return "提交结果暂不确定。请勿重复提交，稍后再试。";
        if(code.contains("agreement")||code.contains("consent")||code.contains("rights"))return "请重新确认授权依据、说明和本次使用声明。";
        if(code.contains("timeout")||code.contains("network")||code.contains("upstream")||code.contains("browser"))return "解析服务暂时不可用，请检查网络或稍后重试；已有摘录没有变化。";
        return "解析未完成，已有摘录没有变化。请稍后重试。";
    }
}
