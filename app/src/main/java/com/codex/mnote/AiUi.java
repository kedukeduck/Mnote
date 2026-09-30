package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

/** Small native journal primitives shared by AI pages, never a credential/log surface. */
final class AiUi {
    static final String RECORD = "record_id", SCOPE = "record_scope", CONVERSATION = "conversation_id";
    static Intent chat(Context context, String scope, String record, String conversation) {
        return new Intent(context, AiChatActivity.class).putExtra(SCOPE, scope)
                .putExtra(RECORD, record).putExtra(CONVERSATION, conversation);
    }
    static void open(Context context, String scope, String record) {
        Intent intent=chat(context,scope,record,null);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); }
        catch (RuntimeException error) { Toast.makeText(context,"记录已保存，请从记录详情进入 AI 对话。",Toast.LENGTH_LONG).show(); }
    }
    static Intent history(Context context, String scope, String record) {
        return new Intent(context,AiChatHistoryActivity.class).putExtra(SCOPE,scope).putExtra(RECORD,record);
    }
    static LinearLayout page(Activity activity, String title) {
        LinearLayout root=JournalUi.column(activity);root.setFitsSystemWindows(true);
        root.setBackgroundColor(activity.getColor(R.color.cream));
        JournalUi.header(activity,root,title,activity::finish);activity.setContentView(root);return root;
    }
    static LinearLayout body(Activity activity, LinearLayout root) {
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);
        LinearLayout body=JournalUi.column(activity);body.setPadding(dp(activity,22),dp(activity,12),dp(activity,22),dp(activity,28));
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return body;
    }
    static int dp(Context c,int n) { return JournalUi.dp(c,n); }
    static Button button(LinearLayout parent,String title,boolean primary,Runnable action) {
        Button button=new Button(parent.getContext());button.setText(title);button.setAllCaps(false);
        if(primary)JournalUi.primary(button);else JournalUi.quiet(button);
        parent.addView(button,new LinearLayout.LayoutParams(-1,-2));button.setOnClickListener(v->action.run());return button;
    }
    static EditText input(LinearLayout parent,String label,String hint,boolean secret) {
        JournalUi.section(parent,label);
        EditText input=new EditText(parent.getContext());input.setHint(hint);input.setTextSize(16);
        input.setTextColor(parent.getContext().getColor(R.color.ink));input.setBackgroundResource(R.drawable.bg_input);
        input.setSingleLine(true);input.setMinHeight(dp(parent.getContext(),48));
        input.setPadding(dp(parent.getContext(),12),dp(parent.getContext(),10),dp(parent.getContext(),12),dp(parent.getContext(),10));
        input.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
    }
    static String preview(String value,int limit) {
        String clean=value.replaceAll("\\s+"," ").trim();
        return clean.length()>limit?clean.substring(0,limit)+"…":clean;
    }
    static String summary(JSONObject conversation) {
        JSONObject snapshot=conversation.optJSONObject("snapshot");
        if(snapshot==null)return "记录";
        String text=snapshot.optString("thought");if(text.isEmpty())text=snapshot.optString("excerpt");
        if(text.isEmpty())text=snapshot.optString("original");
        return text.isEmpty()?"截图与页面资料":preview(text,100);
    }
    // Fingerprints are client-local implementation details, not a cross-platform revision ID.
    static boolean snapshotChanged(JSONObject snapshot,CaptureStore.CaptureRecord record) {
        if(snapshot==null||record==null)return false;
        java.util.Set<String> modules=new java.util.HashSet<>();
        org.json.JSONArray chosen=snapshot.optJSONArray("modules");
        if(chosen!=null)for(int i=0;i<chosen.length();i++)modules.add(chosen.optString(i));
        if(modules.contains("thought")&&!record.comment.equals(snapshot.optString("thought")))return true;
        if(modules.contains("excerpt")&&!record.sourceText.equals(snapshot.optString("excerpt")))return true;
        if(modules.contains("original")&&!CaptureRecordEdits.original(record).equals(snapshot.optString("original")))return true;
        if(modules.contains("metadata")){
            if(!record.sourceUrl.equals(snapshot.optString("source_url"))||!record.kind.equals(snapshot.optString("kind")))return true;
            java.util.Set<String> before=new java.util.HashSet<>(),now=new java.util.HashSet<>();
            org.json.JSONArray tags=snapshot.optJSONArray("tags");
            if(tags!=null)for(int i=0;i<tags.length();i++)before.add(tags.optString(i));
            for(int i=0;i<record.tags.length();i++)now.add(record.tags.optString(i));
            return !before.equals(now);
        }
        return false;
    }
    static String localTime(String utc){
        try{return java.time.Instant.parse(utc).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));}
        catch(java.time.DateTimeException error){return "时间未知";}
    }
    static String error(String code) {
        if(code==null)return "操作未完成，内容已保留，请重试。";
        if(code.startsWith("provider_")||code.startsWith("response_")||code.startsWith("conversation_")
                ||code.equals("revision_conflict")||code.equals("model_required")||code.equals("chat_sync_failed")
                ||code.equals("record_unavailable")||code.equals("network_timeout"))return AiChatClient.errorMessage(code);
        if(code.equals("endpoint_changed_reenter_key"))return "服务地址已改变，请重新输入该服务的密钥，避免向新地址发送旧密钥。";
        if(code.equals("api_key_required")||code.equals("invalid_api_key"))return "请填写有效的 API Key，密钥不能包含空格或换行。";
        if(code.equals("invalid_model"))return "请填写模型服务提供的模型 ID。";
        if(code.equals("invalid_label"))return "请填写一个简短的配置名称。";
        if(code.contains("account_changed"))return "账号已变化，请返回当前账号后重试。";
        if(code.contains("401")||code.contains("invalid_key"))return "密钥或登录已失效，请检查模型配置和账号登录。";
        if(code.contains("403")||code.contains("consent")||code.contains("permission"))return "需要确认本次资料的 AI 使用授权。";
        if(code.contains("429"))return "模型服务暂时限流或额度不足，请稍后重试或检查账户额度。";
        if(code.contains("409")||code.contains("conflict")||code.contains("busy"))return "这段对话在另一设备有更新或正在生成，请刷新后继续；输入已保留。";
        if(code.contains("404")||code.contains("record_missing")||code.contains("record_deleted"))return "记录、会话或模型不存在，请返回刷新并检查模型名称。";
        if(code.contains("profile_changed"))return "此会话的模型配置已更改，请恢复原配置，或用新配置新建对话。";
        if(code.contains("profile")||code.contains("model_not_configured"))return "请先在 AI 模型配置中添加可用模型。";
        if(code.contains("vision")||code.contains("image_not_supported"))return "当前模型不支持图片，请换模型，或在新对话中取消截图。";
        if(code.contains("too_large")||code.contains("context")||code.contains("limit"))return "资料或对话已达到容量上限。内容未被截断，请减少资料范围或新建对话。";
        if(code.contains("https")||code.contains("url")||code.contains("endpoint")||code.contains("redirect"))return "服务地址无效或发生重定向，请填写模型服务的直接 HTTPS API 地址。";
        if(code.contains("cancel")||code.contains("stopped"))return "已停止生成，收到的内容已保留。";
        if(code.contains("http_400"))return "模型服务拒绝了请求，请检查模型名称、图片能力和接口兼容性。";
        if(code.contains("sync")||code.contains("login"))return "聊天同步尚未完成，请检查登录和网络后重试。记录已保存在本机。";
        return "请求未完成，内容已保留。请检查网络、模型地址和密钥后重试。";
    }
    static void showError(Activity a,Exception error) { Toast.makeText(a,error(error.getMessage()),Toast.LENGTH_LONG).show(); }
    static void record(Activity activity,String scope,String recordId) {
        new Thread(()->{
            try {
                CaptureStore.CaptureRecord record=CaptureRecordEdits.latest(activity,scope,recordId);
                android.graphics.Bitmap image=record.hasImage?CaptureStore.decodeReviewBitmap(record.annotatedFile):null;
                android.graphics.Bitmap full=record.contextFile==null?null:CaptureStore.decodeReviewBitmap(record.contextFile);
                activity.runOnUiThread(()->{
                    if(activity.isDestroyed()||activity.isFinishing()||!scope.equals(CaptureAccountSession.scope(activity))){
                        if(image!=null)image.recycle();if(full!=null&&full!=image)full.recycle();return;
                    }
                    CaptureRecordPage.show(activity,record,image,full,()->Toast.makeText(activity,"请返回记录列表删除。",Toast.LENGTH_SHORT).show(),url->{
                        String safe=CaptureSourceUrl.clean(url);if(!safe.isEmpty())try{activity.startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(safe)));}catch(RuntimeException ignored){}
                    },()->activity.startActivity(new Intent(activity,CaptureRecordEditActivity.class).putExtra(RECORD,recordId).putExtra(SCOPE,scope)));
                });
            }catch(Exception error){activity.runOnUiThread(()->{if(!activity.isDestroyed())showError(activity,error);});}
        },"mnote-chat-record-preview").start();
    }
}
