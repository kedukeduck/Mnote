package com.codex.mnote;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** One history screen with an enforced optional parent-record scope. */
public final class AiChatHistoryActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String scope,record;private LinearLayout list;private EditText search;private TextView status;
    private boolean destroyed;private int generation;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);scope=getIntent().getStringExtra(AiUi.SCOPE);if(scope==null)scope=CaptureAccountSession.scope(this);
        record=getIntent().getStringExtra(AiUi.RECORD);
        LinearLayout root=AiUi.page(this,record==null?"全部 AI 对话":"这条记录的对话");
        LinearLayout body=AiUi.body(this,root);
        if(record!=null)AiUi.button(body,"围绕这条记录新建对话",true,()->startActivity(AiUi.chat(this,scope,record,null))).setId(R.id.ai_chat_new);
        search=AiUi.input(body,"搜索对话","搜索标题、消息或记录摘要",false);search.setId(R.id.ai_history_search);
        status=JournalUi.text(this,"",13,R.color.ink_muted);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        AiUi.button(body,"刷新聊天历史",false,this::sync);
        list=JournalUi.column(this);list.setId(R.id.ai_history_list);body.addView(list);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){load();}});
    }
    @Override protected void onResume(){super.onResume();if(!scope.equals(CaptureAccountSession.scope(this))){finish();return;}load();sync();}
    private void sync(){
        if(!CaptureAccountSession.hasAccount(this)){status.setText("本机会话 · 登录后可同步账号中的聊天历史");return;}
        String owner=scope;status.setText("正在同步聊天…");
        io.execute(()->{String message="聊天历史已同步";try{CaptureAccountSession.requireScope(this,owner);AiChatSync.run(this);}catch(Exception error){message=AiUi.error(error.getMessage());}
            String value=message;runOnUiThread(()->{if(valid()){status.setText(value);load();}});
        });
    }
    private boolean valid(){return !destroyed&&!isFinishing()&&scope.equals(CaptureAccountSession.scope(this));}
    private void load(){
        if(search==null)return;int ticket=++generation;String query=search.getText().toString().toLowerCase(Locale.ROOT);
        io.execute(()->{try{
            CaptureAccountSession.requireScope(this,scope);List<JSONObject> matches=new ArrayList<>();
            for(JSONObject c:AiChatStore.list(this,record)){
                StringBuilder content=new StringBuilder(c.optString("title")).append('\n').append(AiUi.summary(c));
                JSONArray messages=c.optJSONArray("messages");if(messages==null||messages.length()==0)continue;
                for(int i=0;i<messages.length();i++)content.append('\n').append(messages.getJSONObject(i).optString("content"));
                if(content.toString().toLowerCase(Locale.ROOT).contains(query))matches.add(c);
            }
            runOnUiThread(()->{if(!valid()||ticket!=generation)return;list.removeAllViews();
                if(matches.isEmpty())list.addView(JournalUi.text(this,query.isEmpty()?"还没有对话。先从一条记录开始聊聊。":"没有匹配的对话。",16,R.color.ink_muted));
                for(JSONObject c:matches)row(c);
            });
        }catch(Exception e){runOnUiThread(()->{if(valid()&&ticket==generation)status.setText(AiUi.error(e.getMessage()));});}});
    }
    private void row(JSONObject c){
        String id=c.optString("id"),recordId=c.optString("record_id");
        JSONArray messages=c.optJSONArray("messages");JSONObject last=messages==null?null:messages.optJSONObject(messages.length()-1);
        JSONObject model=c.optJSONObject("model");
        LinearLayout card=JournalUi.column(this);card.setPadding(0,AiUi.dp(this,16),0,AiUi.dp(this,12));
        TextView title=JournalUi.text(this,c.optString("title","新的讨论"),19,R.color.ink);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);card.addView(title);
        TextView parent=JournalUi.text(this,"来自记录 · "+AiUi.summary(c),13,R.color.copper);parent.setMaxLines(2);parent.setEllipsize(TextUtils.TruncateAt.END);card.addView(parent);
        if(last!=null){TextView excerpt=JournalUi.text(this,AiUi.preview(AiMessageText.render(last.optString("content")).toString(),120),15,R.color.ink_muted);excerpt.setMaxLines(3);excerpt.setEllipsize(TextUtils.TruncateAt.END);card.addView(excerpt);}
        String state=last==null?"":"generating".equals(last.optString("status"))?" · 正在生成":"failed".equals(last.optString("status"))?" · 请求失败":"stopped".equals(last.optString("status"))?" · 已停止":"";
        card.addView(JournalUi.text(this,AiUi.localTime(c.optString("updated_at"))+"\n"+(model==null?"":model.optString("model"))+state,12,R.color.ink_muted));
        card.setClickable(true);card.setFocusable(true);card.setOnClickListener(v->startActivity(AiUi.chat(this,scope,recordId,id)));
        list.addView(card);AiUi.button(list,"更多操作",false,()->more(c));JournalUi.rule(list);
    }
    private void more(JSONObject c){
        String id=c.optString("id");new AlertDialog.Builder(this).setTitle(c.optString("title")).setItems(new String[]{"继续对话","查看所属记录","重命名","删除对话"},(dialog,which)->{
            if(which==0)startActivity(AiUi.chat(this,scope,c.optString("record_id"),id));
            if(which==1)AiUi.record(this,scope,c.optString("record_id"));
            if(which==2){EditText name=new EditText(this);name.setText(c.optString("title"));name.setSingleLine(true);
                new AlertDialog.Builder(this).setTitle("重命名对话").setView(name).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->mutate(()->AiChatStore.rename(this,id,name.getText().toString()))).show();}
            if(which==3)new AlertDialog.Builder(this).setTitle("删除这段对话？").setMessage("删除聊天及其资料副本，不影响原记录。联网后同步删除；不能撤回已发给模型服务商的数据。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->mutate(()->AiChatStore.delete(this,id))).show();
        }).show();
    }
    private interface Mutation{void run()throws Exception;}
    private void mutate(Mutation action){io.execute(()->{try{CaptureAccountSession.requireScope(this,scope);action.run();runOnUiThread(()->{if(valid()){load();sync();}});}catch(Exception e){runOnUiThread(()->{if(valid())AiUi.showError(this,e);});}});}
    @Override protected void onDestroy(){destroyed=true;io.shutdown();super.onDestroy();}
}
