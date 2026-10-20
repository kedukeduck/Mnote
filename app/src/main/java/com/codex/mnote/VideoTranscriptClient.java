package com.codex.mnote;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Only the account server is contacted. Video URLs and transcript-service secrets are never fetched here. */
final class VideoTranscriptClient {
    static final class Rejected extends IOException {final int status;Rejected(String code,int status){super(code);this.status=status;}}
    static JSONObject request(Context context,String scope,String method,String suffix,JSONObject body) throws Exception {
        if(!suffix.equals("/config")&&!suffix.equals("/jobs")&&!suffix.matches("/jobs/[a-f0-9-]{36}"))throw new IOException("invalid_job");
        final CaptureSyncPreferences.Config config;
        synchronized(CaptureAccountSession.LOCK){CaptureAccountSession.requireScope(context,scope);config=CaptureAccountSession.config(context);}
        URI target=URI.create(CaptureSyncPreferences.validateAndNormalizeBaseUrl(config.baseUrl)+"/v1/transcripts"+suffix);
        if(!"https".equals(target.getScheme()))throw new IOException("https_required");
        HttpURLConnection connection=(HttpURLConnection)target.toURL().openConnection();
        try {
            connection.setConnectTimeout(15000);connection.setReadTimeout(45000);connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+config.writeToken);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Cache-Control","no-store");
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");
                try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            JSONObject response=new JSONObject();
            if(stream!=null)try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))!=-1){if(output.size()+read>2*1024*1024)throw new IOException("response_too_large");output.write(buffer,0,read);}
                response=new JSONObject(output.toString("UTF-8"));
            }
            CaptureAccountSession.requireScope(context,scope);
            if(status<200||status>=300){JSONObject error=response.optJSONObject("error");throw new Rejected(error==null?"http_"+status:error.optString("code","http_"+status),status);}
            return response;
        } finally {connection.disconnect();}
    }
    static String error(Exception exception) {
        String code=exception.getMessage()==null?"":exception.getMessage();
        if(code.contains("account_changed"))return "账号已变化，请返回后重新打开。";
        if(code.contains("login")||code.contains("401"))return "请先登录账号，再解析视频文字稿。";
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
