package com.codex.mnote;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.List;

/** Record-local chat entry. History lookup never scans message files on the UI thread. */
final class AiRecordSection {
    static void attach(Activity activity,LinearLayout page,CaptureStore.CaptureRecord record){
        String scope=CaptureAccountSession.scope(activity);
        LinearLayout dock=JournalUi.column(activity);dock.setPadding(AiUi.dp(activity,18),AiUi.dp(activity,8),AiUi.dp(activity,18),AiUi.dp(activity,8));JournalUi.rule(page);page.addView(dock);
        Button primary=AiUi.button(dock,"与 AI 聊聊",true,()->activity.startActivity(AiUi.chat(activity,scope,record.id,null)));primary.setId(R.id.ai_chat_open);
        LinearLayout actions=new LinearLayout(activity);dock.addView(actions);actions.setVisibility(View.GONE);
        Button fresh=AiUi.button(actions,"新建对话",false,()->activity.startActivity(AiUi.chat(activity,scope,record.id,null)));fresh.setId(R.id.ai_chat_new);fresh.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button history=AiUi.button(actions,"查看本条会话",false,()->activity.startActivity(AiUi.history(activity,scope,record.id)));history.setId(R.id.ai_chat_history);history.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        new Thread(()->{try{
            CaptureAccountSession.requireScope(activity,scope);List<JSONObject> conversations=AiChatStore.list(activity,record.id);
            if(conversations.isEmpty())return;JSONObject recent=conversations.get(0);
            activity.runOnUiThread(()->{if(activity.isDestroyed()||activity.isFinishing()||!scope.equals(CaptureAccountSession.scope(activity)))return;
                primary.setText("继续最近对话");primary.setContentDescription("继续最近对话："+recent.optString("title"));
                primary.setOnClickListener(v->activity.startActivity(AiUi.chat(activity,scope,record.id,recent.optString("id"))));
                history.setText("本条会话 · "+conversations.size());actions.setVisibility(View.VISIBLE);
            });
        }catch(Exception ignored){}},"mnote-record-chats").start();
    }
}
