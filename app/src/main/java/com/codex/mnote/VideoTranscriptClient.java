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
        if(exception instanceof Rejected&&((Rejected)exception).status==401)code="unauthorized";
        else if(exception instanceof SocketTimeoutException)code="request_timeout";
        else if(exception instanceof UnknownHostException||exception instanceof ConnectException
                ||exception instanceof SocketException||exception instanceof javax.net.ssl.SSLException)code="network_failed";
        // Only fixed, known codes may appear in the UI. Exception/server messages
        // can contain credentials, signed media URLs or untrusted response text.
        String message=switch(code){
            case "account_changed"->"账号已变化，请返回后重新打开。";
            case "login_required","unauthorized","invalid_session","http_401"->"登录状态已过期或失效，请到「设置 → 账号与同步」重新登录。";
            case "forbidden","http_403"->"当前凭据没有文字稿解析权限，请使用 Mnote 账号重新登录。";
            case "connection_busy"->"上次网络连接仍在结束，请稍后重新连接；不会重复提交任务。";
            case "request_timeout"->"连接解析服务超时，请刷新或重新连接；已有内容没有变化，请勿重复提交任务。";
            case "timeout"->"本次解析处理超时，请稍后重新解析；已有摘录没有变化。";
            case "not_configured","http_404"->"当前同步服务器尚未启用视频文字稿服务，请更新服务端。";
            case "not_ready"->"语音识别服务尚未准备好，请稍后重新连接；已有摘录没有变化。";
            case "image_post_unsupported"->"这是一条图文作品，暂不支持视频文字稿。";
            case "platform_restricted","source_forbidden"->"来源平台暂时无法公开访问这条视频，请稍后重试。";
            case "source_rate_limited"->"来源平台暂时限制了访问频率，请稍后重试；已有摘录没有变化。";
            case "account_busy","account_limit","daily_limit","queue_full","browser_busy","http_429"->"解析任务或可用额度已满，请稍后再试。";
            case "too_long"->"视频超过服务允许时长，请换一条较短的视频。";
            case "too_large","request_too_large","response_too_large"->"视频或文字稿过大，未截断任何内容，请换一条较短的视频。";
            case "unsafe_url","unsupported_source","invalid_url"->"链接不受支持，请填写一条公开的 B 站、抖音视频分享链接。";
            case "media_unavailable"->"没有取得这条视频可转写的音频，请稍后重试；已有摘录没有变化。";
            case "invalid_media"->"已获取视频，但媒体内容无法读取或转换为音频。请稍后重新解析；已有摘录没有变化。";
            case "transcription_failed"->"音频转写未完成，请稍后重新解析；已有摘录没有变化。";
            case "interrupted"->"解析任务因服务中断而停止，请重新解析；已有摘录没有变化。";
            case "not_found"->"临时任务已过期或不存在，请取消该任务后重新解析；已有摘录没有变化。";
            case "not_completed"->"任务尚未完成，请刷新任务进度后再导入。";
            case "submission_uncertain"->"提交结果暂不确定。请勿重复提交，稍后恢复本次提交。";
            case "request_conflict"->"本次请求与已提交内容不一致，请取消临时任务后重新解析；已有摘录没有变化。";
            case "agreement_required","consent_required","rights_required"->"请重新确认授权依据、说明和本次使用声明。";
            case "network_failed","upstream_unavailable","browser_unavailable","storage_unavailable"->"解析服务暂时不可用，请检查网络或稍后重试；已有摘录没有变化。";
            case "internal_error"->"解析服务处理失败，请稍后重试；已有摘录没有变化。";
            case "invalid_request","invalid_job"->"解析服务返回的任务信息有误，请稍后重试；已有摘录没有变化。";
            case "cancelled"->"连接已取消，可重新连接继续查看任务；已有摘录没有变化。";
            case "https_required"->"解析服务地址必须使用 HTTPS，请检查账号的服务器配置。";
            default->null;
        };
        return message==null?"解析未完成，已有摘录没有变化。请稍后重试。（错误标识：unknown_error）"
                :message+"（错误标识："+code+"）";
    }
}
