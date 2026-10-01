package com.codex.mnote;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Record-scoped journal chat. Opening this page never sends a provider request. */
public final class AiChatActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> modules=new LinkedHashSet<>(Arrays.asList("thought","excerpt","original","images","metadata"));
    private String scope,recordId,conversationId,profileId="";
    private JSONObject conversation;private CaptureStore.CaptureRecord record;
    private LinearLayout messages,starters;private ScrollView scroll;private EditText input;
    private TextView status;private Button context,model,send,stop,latest;
    private boolean destroyed,busy,rendering,receiverRegistered,consentOpen,draftLoaded,followNextLayout;private int loadGeneration;
    private String unsavedDraft="";private Runnable draftSave;
    private final BroadcastReceiver changed=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(valid()&&conversationId!=null)load();}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        scope=getIntent().getStringExtra(AiUi.SCOPE);if(scope==null)scope=CaptureAccountSession.scope(this);
        recordId=getIntent().getStringExtra(AiUi.RECORD);conversationId=getIntent().getStringExtra(AiUi.CONVERSATION);
        if(state!=null){conversationId=state.getString("conversation",conversationId);profileId=state.getString("profile","");unsavedDraft=state.getString("draft","");
            ArrayList<String> saved=state.getStringArrayList("modules");if(saved!=null){modules.clear();modules.addAll(saved);}}
        if(recordId==null||!scope.equals(CaptureAccountSession.scope(this))){finish();return;}
        if(state==null&&conversationId==null)unsavedDraft=getSharedPreferences("ai_new_chat_drafts_"+scope,MODE_PRIVATE).getString(recordId,"");
        build();input.setText(unsavedDraft);
        IntentFilter filter=new IntentFilter(AiChatStore.ACTION_CHANGED);filter.addAction(CaptureStore.ACTION_RECORDS_CHANGED);
        filter.addAction(CaptureSyncWorker.ACTION_SYNC_CHANGED);
        ContextCompat.registerReceiver(this,changed,filter,ContextCompat.RECEIVER_NOT_EXPORTED);receiverRegistered=true;
    }
    private void build(){
        LinearLayout root=AiUi.page(this,"围绕这条聊聊");
        LinearLayout top=JournalUi.column(this);top.setPadding(AiUi.dp(this,18),AiUi.dp(this,4),AiUi.dp(this,18),AiUi.dp(this,4));
        root.addView(top);
        LinearLayout actions=new LinearLayout(this);top.addView(actions);
        model=AiUi.button(actions,"选择模型",false,this::chooseModel);model.setId(R.id.ai_chat_model);
        model.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));model.setMaxLines(1);model.setEllipsize(TextUtils.TruncateAt.END);
        Button history=AiUi.button(actions,"本条会话",false,()->startActivity(AiUi.history(this,scope,recordId)));
        history.setId(R.id.ai_chat_history);history.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
        Button fresh=AiUi.button(actions,"新建",false,()->startActivity(AiUi.chat(this,scope,recordId,null)));fresh.setId(R.id.ai_chat_new);fresh.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
        history.setTextSize(13);fresh.setTextSize(13);model.setTextSize(13);
        context=AiUi.button(top,"查看本次资料",false,this::showContext);context.setId(R.id.ai_chat_context);context.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);context.setTextSize(13);
        status=JournalUi.text(this,"正在准备记录…",12,R.color.ink_muted);status.setId(R.id.ai_chat_status);top.addView(status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);JournalUi.rule(root);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        messages=JournalUi.column(this);messages.setId(R.id.ai_chat_messages);messages.setPadding(AiUi.dp(this,16),AiUi.dp(this,18),AiUi.dp(this,16),0);
        scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        latest=AiUi.button(root,"回到最新 ↓",false,this::scrollToLatest);latest.setVisibility(View.GONE);
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{if(y<oldY)followNextLayout=false;latest.setVisibility(atBottom()?View.GONE:View.VISIBLE);});
        messages.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(followNextLayout){followNextLayout=false;scroll.scrollTo(0,Math.max(0,messages.getHeight()-scroll.getHeight()));}
        });
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(ob>ot&&b-t!=ob-ot&&messages.getHeight()-(ob-ot+scroll.getScrollY())<AiUi.dp(this,90))scrollToLatest();
        });
        starters=JournalUi.column(this);starters.setPadding(AiUi.dp(this,18),0,AiUi.dp(this,18),0);root.addView(starters);
        // Suggestions are drafts, not hidden paid requests.
        String[] prompts={"梳理这条记录","提出不同看法","这对我有什么启发？","转化成行动"};
        for(int row=0;row<2;row++){
            LinearLayout pair=new LinearLayout(this);starters.addView(pair);
            for(int col=0;col<2;col++){
                String prompt=prompts[row*2+col];Button suggestion=AiUi.button(pair,prompt,false,()->{input.setText(prompt);input.setSelection(input.length());input.requestFocus();});
                suggestion.setTextSize(13);suggestion.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
            }
        }
        JournalUi.rule(root);LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM);composer.setPadding(AiUi.dp(this,12),AiUi.dp(this,10),AiUi.dp(this,12),AiUi.dp(this,10));root.addView(composer);
        input=new EditText(this);input.setId(R.id.ai_chat_input);input.setHint("聊聊你的想法…");input.setTextSize(16);
        input.setTextColor(getColor(R.color.ink));input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(AiUi.dp(this,12),AiUi.dp(this,10),AiUi.dp(this,12),AiUi.dp(this,10));input.setMinLines(1);input.setMaxLines(5);
        input.setGravity(Gravity.TOP|Gravity.START);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setSaveEnabled(false);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);input.setMinimumHeight(AiUi.dp(this,48));composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout buttons=new LinearLayout(this);LinearLayout.LayoutParams buttonLayout=new LinearLayout.LayoutParams(-2,-2);buttonLayout.leftMargin=AiUi.dp(this,8);composer.addView(buttons,buttonLayout);
        stop=AiUi.button(buttons,"停止",false,()->{AiChatClient.Call call=AiChatClient.active(this,conversationId);if(call!=null)call.cancel();});stop.setId(R.id.ai_chat_stop);stop.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));stop.setVisibility(View.GONE);
        send=AiUi.button(buttons,"发送",true,this::send);send.setId(R.id.ai_chat_send);send.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
        send.setMinWidth(AiUi.dp(this,64));send.setMinimumWidth(AiUi.dp(this,64));send.setSingleLine(true);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence s,int a,int before,int count){unsavedDraft=s.toString();starters.setVisibility(conversationId==null&&unsavedDraft.isEmpty()?View.VISIBLE:View.GONE);saveDraftLater();}});
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    private boolean atBottom(){return messages.getHeight()-(scroll.getHeight()+scroll.getScrollY())<AiUi.dp(this,90);}
    private void scrollToLatest(){
        followNextLayout=true;
        scroll.post(()->{if(!destroyed&&followNextLayout)scroll.scrollTo(0,Math.max(0,messages.getHeight()-scroll.getHeight()));});
    }
    private boolean valid(){return !destroyed&&!isFinishing()&&scope.equals(CaptureAccountSession.scope(this));}
    @Override protected void onResume(){super.onResume();if(!scope.equals(CaptureAccountSession.scope(this))){finish();return;}load();}
    private void load(){
        int generation=++loadGeneration;String id=conversationId;
        io.execute(()->{try{
            CaptureAccountSession.requireScope(this,scope);CaptureStore.CaptureRecord note=CaptureRecordEdits.latest(this,scope,recordId);
            JSONObject chat=id==null?null:AiChatStore.get(this,id);
            if(chat!=null&&!recordId.equals(chat.optString("record_id")))throw new java.io.IOException("record_missing");
            String profile=id==null?(profileId.isEmpty()?AiModelPreferences.defaultId(this):profileId):AiChatStore.profileId(this,id);
            String draft=id==null?unsavedDraft:AiChatStore.draft(this,id);
            JSONArray profiles=AiModelPreferences.list(this);
            if(chat!=null&&(profile==null||profile.isEmpty())){
                JSONObject expected=chat.getJSONObject("model");
                for(int i=0;i<profiles.length();i++){JSONObject candidate=profiles.getJSONObject(i);
                    if(expected.optString("base_url").equals(candidate.optString("base_url"))&&expected.optString("model").equals(candidate.optString("model"))){profile=candidate.optString("id");break;}}
            }
            String selectedProfile=profile;
            runOnUiThread(()->{
                if(!valid()||generation!=loadGeneration)return;record=note;conversation=chat;profileId=selectedProfile==null?"":selectedProfile;
                if(!draftLoaded&&input.length()==0&&!draft.isEmpty()){rendering=true;input.setText(draft);rendering=false;}
                draftLoaded=true;
                if(chat==null){
                    String label="配置模型";for(int i=0;i<profiles.length();i++){JSONObject p=profiles.optJSONObject(i);if(p!=null&&profileId.equals(p.optString("id")))label=p.optString("label")+" · "+p.optString("model");}
                    model.setText(label);context.setText("本次资料 · "+moduleLabel(modules)+"  ›");
                    messages.removeAllViews();TextView heading=JournalUi.text(this,"让这条记录，\n多一些新的可能。",28,R.color.ink);heading.setTypeface(android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL));messages.addView(heading);
                    TextView summary=JournalUi.text(this,AiUi.preview(!note.comment.isEmpty()?note.comment:!note.sourceText.isEmpty()?note.sourceText:"一张截图，一段当时的场景。",160),16,R.color.ink_muted);summary.setPadding(0,AiUi.dp(this,20),0,AiUi.dp(this,12));messages.addView(summary);
                    status.setText("只使用这条记录。发送前可查看资料；打开页面不会调用 AI。");
                }else renderChat(chat);
                setBusy(busy);
            });
        }catch(Exception error){runOnUiThread(()->{if(valid()&&generation==loadGeneration){status.setText(AiUi.error(error.getMessage()));send.setEnabled(false);}});}});
    }
    private void renderChat(JSONObject c){
        boolean follow=atBottom();followNextLayout=follow;conversation=c;starters.setVisibility(View.GONE);
        JSONObject m=c.optJSONObject("model");model.setText(m==null?"模型配置":m.optString("label")+" · "+m.optString("model"));
        JSONObject snapshot=c.optJSONObject("snapshot");context.setText("查看本会话的记录版本与资料  ›");
        boolean changed=AiUi.snapshotChanged(snapshot,record);
        status.setText(changed?"记录已有更新 · 本会话仍使用原资料":"仅围绕本条记录 · 长按气泡可复制或选择文字");
        JSONArray entries=c.optJSONArray("messages");
        int position=0;
        if(entries!=null)for(int i=0;i<entries.length();i++){
            JSONObject message=entries.optJSONObject(i);if(message==null)continue;
            boolean own="user".equals(message.optString("role"));String id=message.optString("id");
            View existing=messages.getChildAt(position);AiChatMessageView row;
            if(existing instanceof AiChatMessageView&&id.equals(((AiChatMessageView)existing).messageId)&&own==((AiChatMessageView)existing).own){
                row=(AiChatMessageView)existing;
            }else{
                // Only replace changed structure. Streaming must not detach the pressed bubble/menu.
                if(position<messages.getChildCount())messages.removeViews(position,messages.getChildCount()-position);
                row=new AiChatMessageView(this,id,own,this::retry);messages.addView(row,new LinearLayout.LayoutParams(-1,-2));
            }
            row.bind(message,i==entries.length()-1);position++;
        }
        if(position<messages.getChildCount())messages.removeViews(position,messages.getChildCount()-position);
        if(follow)scrollToLatest();setBusy(false);
    }
    private void setBusy(boolean preparing){
        busy=preparing;boolean generating=conversationId!=null&&AiChatClient.active(this,conversationId)!=null;
        send.setEnabled(!preparing&&!generating&&record!=null);stop.setVisibility(generating?View.VISIBLE:View.GONE);
        send.setVisibility(generating?View.GONE:View.VISIBLE);
        send.setText(preparing?"准备中…":"发送");
    }
    private void chooseModel(){
        if(conversationId!=null){new AlertDialog.Builder(this).setTitle("本会话的模型").setMessage("旧会话不会随默认配置自动更换模型。你可以检查本机配置，或新建对话使用另一个模型。")
            .setNegativeButton("关闭",null).setNeutralButton("模型配置",(d,w)->startActivity(new Intent(this,AiModelSettingsActivity.class)))
            .setPositiveButton("新建对话",(d,w)->startActivity(AiUi.chat(this,scope,recordId,null))).show();return;}
        try{
            JSONArray profiles=AiModelPreferences.list(this);String[] names=new String[profiles.length()+1];for(int i=0;i<profiles.length();i++)names[i]=profiles.getJSONObject(i).optString("label")+" · "+profiles.getJSONObject(i).optString("model");names[names.length-1]="管理 / 添加模型";
            new AlertDialog.Builder(this).setTitle("选择本次模型").setItems(names,(d,w)->{if(w==profiles.length())startActivity(new Intent(this,AiModelSettingsActivity.class));else{profileId=profiles.optJSONObject(w).optString("id");load();}}).show();
        }catch(Exception e){AiUi.showError(this,e);}
    }
    private static String moduleLabel(Set<String> selected){
        List<String> labels=new ArrayList<>();for(String key:selected)labels.add("thought".equals(key)?"想法":"excerpt".equals(key)?"摘录":"original".equals(key)?"原文":"images".equals(key)?"截图":"来源等信息");return String.join(" · ",labels);
    }
    private void showContext(){
        if(record==null)return;
        if(conversationId==null){
            String[] keys={"thought","excerpt","original","images","metadata"};String[] names={"我的想法","摘录文字","页面原文","圈选图与完整截图","来源、时间、类型和标签"};boolean[] checked=new boolean[keys.length];
            for(int i=0;i<keys.length;i++)checked[i]=modules.contains(keys[i]);
            new AlertDialog.Builder(this).setTitle("选择本次资料").setMultiChoiceItems(names,checked,(d,w,on)->checked[w]=on)
                .setNegativeButton("取消",null).setNeutralButton("查看记录",(d,w)->AiUi.record(this,scope,recordId))
                .setPositiveButton("使用这些资料",(d,w)->{modules.clear();for(int i=0;i<keys.length;i++)if(checked[i])modules.add(keys[i]);load();}).show();return;
        }
        JSONObject snapshot=conversation==null?null:conversation.optJSONObject("snapshot");if(snapshot==null)return;
        LinearLayout content=JournalUi.column(this);content.setPadding(AiUi.dp(this,20),0,AiUi.dp(this,20),AiUi.dp(this,20));
        for(String key:new String[]{"thought","excerpt","original","source_url","context_note"})if(!snapshot.optString(key).isEmpty()){
            JournalUi.section(content,"thought".equals(key)?"我的想法":"excerpt".equals(key)?"摘录":"original".equals(key)?"原文":"source_url".equals(key)?"来源":"上下文说明");
            TextView text=JournalUi.text(this,snapshot.optString(key),16,R.color.ink);text.setTextIsSelectable(true);content.addView(text);
        }
        JSONArray chosen=snapshot.optJSONArray("modules");boolean metadata=false;
        if(chosen!=null)for(int i=0;i<chosen.length();i++)if("metadata".equals(chosen.optString(i)))metadata=true;
        if(metadata){
            JournalUi.section(content,"其他来源信息");
            String details="记录时间："+AiUi.localTime(snapshot.optString("record_created_at"))+"\n类型："+snapshot.optString("kind")
                +"\n标签："+snapshot.optJSONArray("tags")+"\n来源应用："+snapshot.optString("source_app")+"\n采集方式："+snapshot.optString("source_type");
            TextView text=JournalUi.text(this,details,14,R.color.ink_muted);text.setTextIsSelectable(true);content.addView(text);
        }
        JSONArray images=snapshot.optJSONArray("images");if(images!=null)for(int i=0;i<images.length();i++){
            JSONObject image=images.optJSONObject(i);if(image==null)continue;JournalUi.section(content,image.optString("label","截图"));
            ImageView view=new ImageView(this);view.setAdjustViewBounds(true);view.setContentDescription(image.optString("label","本会话发送的截图"));content.addView(view,new LinearLayout.LayoutParams(-1,-2));
            io.execute(()->{try{byte[] bytes=android.util.Base64.decode(image.getString("data_base64"),android.util.Base64.DEFAULT);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);runOnUiThread(()->{if(valid())view.setImageBitmap(bitmap);else if(bitmap!=null)bitmap.recycle();});}catch(Exception ignored){}});
        }
        ScrollView pane=new ScrollView(this);pane.addView(content);new AlertDialog.Builder(this).setTitle("本会话实际使用的资料").setView(pane).setPositiveButton("关闭",null).show();
    }
    private void send(){
        if(busy||record==null)return;String text=input.getText().toString().trim();if(text.isEmpty()){input.setError("写下想聊的问题");return;}
        if(text.length()>100_000){input.setError("单条消息最多 10 万字");return;}
        if(profileId.isEmpty()){chooseModel();return;}
        setBusy(true);String id=conversationId,selectedProfile=profileId;Set<String> selected=new LinkedHashSet<>(modules);
        io.execute(()->{try{
            CaptureAccountSession.requireScope(this,scope);AiModelPreferences.Config config=AiModelPreferences.load(this,selectedProfile);
            JSONObject snapshot=id==null?AiChatStore.snapshot(this,recordId,selected):AiChatStore.get(this,id).getJSONObject("snapshot");
            if(snapshot.optJSONArray("images")!=null&&snapshot.optJSONArray("images").length()>0&&!config.vision)throw new java.io.IOException("vision_required");
            boolean grant=id==null||AiChatStore.needsConsent(this,id);
            runOnUiThread(()->{if(!valid())return;if(grant)consent(snapshot,selectedProfile,text);else launch(text);});
        }catch(Exception error){runOnUiThread(()->{if(valid()){setBusy(false);status.setText(AiUi.error(error.getMessage()));Toast.makeText(this,status.getText(),Toast.LENGTH_LONG).show();}});}});
    }
    private void consent(JSONObject snapshot,String profile,String text){
        consent(snapshot,profile,()->launch(text));
    }
    private void consent(JSONObject snapshot,String profile,Runnable resume){
        if(consentOpen){setBusy(false);return;}consentOpen=true;
        String address="";try{address=AiModelPreferences.load(this,profile).baseUrl;}catch(Exception ignored){}
        JSONArray chosen=snapshot.optJSONArray("modules");Set<String> included=new LinkedHashSet<>();if(chosen!=null)for(int i=0;i<chosen.length();i++)included.add(chosen.optString(i));
        String restriction=record!=null&&("deny".equals(record.aiAccess)||"local_only".equals(record.aiAccess))?"此记录原本不允许远程 AI 自动读取。下面的确认只授权本次会话，不更改记录的 MCP 或全库权限。\n\n":"";
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("将这些资料用于本次对话？")
            .setMessage(restriction+"发送到："+address+"\n资料："+moduleLabel(included)+"\n\n包括本会话后续消息，模型服务可能收费并按其规则保留数据。"+
                (CaptureAccountSession.hasAccount(this)?"聊天与资料副本会同步到当前 Mnote 账号。":"聊天暂存在本机。")+"不会读取其他记录或自动修改笔记。")
            .setNegativeButton("取消",(d,w)->{consentOpen=false;setBusy(false);})
            .setPositiveButton("仅授权本会话并发送",(d,w)->{
                consentOpen=false;io.execute(()->{try{
                    CaptureAccountSession.requireScope(this,scope);String id=conversationId;
                    if(id==null)id=AiChatStore.create(this,snapshot,profile);
                    AiChatStore.authorize(this,id);String created=id;
                    runOnUiThread(()->{if(valid()){conversationId=created;resume.run();}});
                }catch(Exception e){runOnUiThread(()->{if(valid()){setBusy(false);status.setText(AiUi.error(e.getMessage()));}});}});
            }).create();dialog.setOnCancelListener(d->{consentOpen=false;setBusy(false);});dialog.show();
    }
    private void launch(String text){
        if(!valid())return;status.setText("正在同步并准备本轮对话…");
        String id=conversationId;setBusy(true);
        io.execute(()->{try{
            CaptureAccountSession.requireScope(this,scope);AiChatStore.saveDraft(this,id,text);
            runOnUiThread(()->{if(!valid())return;
                try{
                    AiChatClient.start(getApplicationContext(),id,text,new UiListener(this));
                    getSharedPreferences("ai_new_chat_drafts_"+scope,MODE_PRIVATE).edit().remove(recordId).apply();
                    rendering=true;input.setText("");unsavedDraft="";rendering=false;setBusy(false);load();
                }catch(Exception error){setBusy(false);status.setText(AiUi.error(error.getMessage()));}
            });
        }catch(Exception error){runOnUiThread(()->{if(valid()){setBusy(false);status.setText(AiUi.error(error.getMessage()));}});}});
    }
    private static final class UiListener implements AiChatClient.Listener {
        private final java.lang.ref.WeakReference<AiChatActivity> owner;
        UiListener(AiChatActivity activity){owner=new java.lang.ref.WeakReference<>(activity);}
        public void onUpdate(JSONObject c){AiChatActivity a=owner.get();if(a!=null&&a.valid())a.renderChat(c);}
        public void onDone(JSONObject c){AiChatActivity a=owner.get();if(a!=null&&a.valid()){a.renderChat(c);a.setBusy(false);}}
        public void onError(String code){AiChatActivity a=owner.get();if(a!=null&&a.valid()){a.draftLoaded=false;a.load();a.main.postDelayed(()->{if(a.valid()){a.setBusy(false);a.status.setText(AiUi.error(code));}},200);}}
    }
    private void retry(){
        if(conversation==null||conversationId==null||busy)return;
        String id=conversationId;setBusy(true);
        io.execute(()->{try{
            CaptureAccountSession.requireScope(this,scope);
            boolean grant=AiChatStore.needsConsent(this,id);JSONObject snapshot=AiChatStore.get(this,id).getJSONObject("snapshot");
            runOnUiThread(()->{if(!valid())return;setBusy(false);
                if(grant){consent(snapshot,profileId,this::launchRetry);return;}
                new AlertDialog.Builder(this).setTitle("重试这一轮？").setMessage("保留原问题和未完成的回复，重新请求一次回答。服务商可能再次计费，不会重复添加你的问题。")
                    .setNegativeButton("取消",null).setPositiveButton("重试",(d,w)->launchRetry()).show();
            });
        }catch(Exception e){runOnUiThread(()->{if(valid()){setBusy(false);AiUi.showError(this,e);}});}});
    }
    private void launchRetry(){
        try{AiChatClient.retry(getApplicationContext(),conversationId,new UiListener(this));setBusy(false);}
        catch(Exception e){setBusy(false);AiUi.showError(this,e);}
    }
    private void saveDraftLater(){
        if(rendering||destroyed)return;if(draftSave!=null)main.removeCallbacks(draftSave);
        draftSave=()->persistDraft();main.postDelayed(draftSave,450);
    }
    private void persistDraft(){
        if(destroyed||input==null||!scope.equals(CaptureAccountSession.scope(this)))return;
        if(conversationId==null){getSharedPreferences("ai_new_chat_drafts_"+scope,MODE_PRIVATE).edit().putString(recordId,input.getText().toString()).apply();return;}
        String id=conversationId,text=input.getText().toString();
        io.execute(()->{try{CaptureAccountSession.requireScope(this,scope);AiChatStore.saveDraft(this,id,text);}catch(Exception ignored){}});
    }
    @Override protected void onPause(){persistDraft();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("conversation",conversationId);state.putString("profile",profileId);state.putString("draft",input==null?unsavedDraft:input.getText().toString());state.putStringArrayList("modules",new ArrayList<>(modules));super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;if(draftSave!=null)main.removeCallbacks(draftSave);if(receiverRegistered)unregisterReceiver(changed);io.shutdown();super.onDestroy();}
}
