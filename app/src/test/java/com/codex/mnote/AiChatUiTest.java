package com.codex.mnote;

import android.app.*;
import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",shadows=AiChatUiTest.Crypto.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AiChatUiTest {
    Context app;String profile;
    @Before public void setup() throws Exception {
        app=RuntimeEnvironment.getApplication();shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        profile=AiModelPreferences.save(app,null,"我的思考伙伴","https://model.example.test/v1","example-model","synthetic-test-key",false,true);
    }
    @Implements(value=AiModelPreferences.class,isInAndroidSdk=false)
    public static class Crypto {
        @Implementation protected static CaptureSyncPreferences.EncryptedValue encrypt(String secret){return new CaptureSyncPreferences.EncryptedValue("encrypted:"+secret,"test-iv");}
        @Implementation protected static String decrypt(String value,String iv){return value.substring("encrypted:".length());}
    }
    CaptureStore.CaptureRecord note(String text) throws Exception {
        return CaptureStore.save(app,null,null,null,null,"thought",text,"quick_note","摘录内容","","https://example.test/article","user",false,CaptureContext.text("页面原文","user_edited","摘录内容"),CaptureTags.parse("学习"));
    }
    String conversation(CaptureStore.CaptureRecord record,String question,String answer) throws Exception {
        String id=AiChatStore.create(app,AiChatStore.snapshot(app,record.id,new HashSet<>(Arrays.asList("thought","excerpt","original","metadata"))),profile);
        AiChatStore.authorize(app,id);String turn=AiChatStore.beginTurn(app,id,question);AiChatStore.finish(app,id,turn,answer,"complete","");return id;
    }
    static void drain(Object owner,String executorField)throws Exception{
        for(int i=0;i<5;i++){
            ExecutorService io=ReflectionHelpers.getField(owner,executorField);
            if(!io.isShutdown())io.submit(()->{}).get(10,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    static String text(View v){
        StringBuilder result=new StringBuilder();if(v instanceof TextView)result.append(((TextView)v).getText()).append('\n');
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)result.append(text(g.getChildAt(i)));}return result.toString();
    }
    @Test public void settingsRoutesToModelsAndAllHistoryWithoutProviderCall(){
        try(var c=Robolectric.buildActivity(SettingsActivity.class).setup()){
            c.get().findViewById(R.id.ai_settings_models).performClick();assertEquals(AiModelSettingsActivity.class.getName(),shadowOf(c.get()).getNextStartedActivity().getComponent().getClassName());
            c.get().findViewById(R.id.ai_settings_history).performClick();Intent intent=shadowOf(c.get()).getNextStartedActivity();
            assertEquals(AiChatHistoryActivity.class.getName(),intent.getComponent().getClassName());assertNull(intent.getStringExtra(AiUi.RECORD));assertEquals("guest",intent.getStringExtra(AiUi.SCOPE));
        }
    }
    @Test public void openingChatIsFreeAndPermissionCancellationRetainsDraft()throws Exception{
        var record=note("只讨论当前记录");
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(app,"guest",record.id,null)).setup()){
            var a=c.get();drain(a,"io");assertTrue(AiChatStore.list(app,null).isEmpty());
            assertTrue(a.findViewById(R.id.ai_chat_send).isEnabled());
            a.<EditText>findViewById(R.id.ai_chat_input).setText("请解释一下");a.findViewById(R.id.ai_chat_send).performClick();drain(a,"io");
            AlertDialog consent=ShadowAlertDialog.getLatestAlertDialog();assertTrue(consent.isShowing());
            String wording=consent.<TextView>findViewById(android.R.id.message).getText().toString();assertTrue(wording.contains("model.example.test"));assertFalse(wording.contains("synthetic-test-key"));assertTrue(wording.contains("不更改记录"));
            consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals("请解释一下",a.<EditText>findViewById(R.id.ai_chat_input).getText().toString());assertTrue(AiChatStore.list(app,null).isEmpty());
            assertEquals("local_only",CaptureRecordEdits.latest(app,"guest",record.id).aiAccess);
        }
    }
    @Test public void newChatDraftSurvivesLeavingWithoutCreatingHistory()throws Exception{
        var record=note("保留草稿");
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(app,"guest",record.id,null)).setup()){
            drain(c.get(),"io");c.get().<EditText>findViewById(R.id.ai_chat_input).setText("尚未发送的思考");c.pause();
        }
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(app,"guest",record.id,null)).setup()){
            drain(c.get(),"io");assertEquals("尚未发送的思考",c.get().<EditText>findViewById(R.id.ai_chat_input).getText().toString());assertTrue(AiChatStore.list(app,null).isEmpty());
        }
    }
    @Test public void noteSaveAndChatSavesBeforeLaunchingAndOrdinarySaveStillOnlySaves()throws Exception{
        try(var c=Robolectric.buildActivity(QuickNoteActivity.class).setup()){
            c.get().<EditText>findViewById(R.id.capture_comment_input).setText("先保存，之后再讨论");c.get().findViewById(R.id.ai_save_chat).performClick();drain(c.get(),"executor");
            Intent intent=shadowOf(c.get()).getNextStartedActivity();assertNotNull(intent);assertEquals(AiChatActivity.class.getName(),intent.getComponent().getClassName());
            String record=intent.getStringExtra(AiUi.RECORD);assertEquals("先保存，之后再讨论",CaptureRecordEdits.latest(app,"guest",record).comment);assertTrue(AiChatStore.list(app,null).isEmpty());
        }
        try(var c=Robolectric.buildActivity(QuickNoteActivity.class).setup()){
            c.get().<EditText>findViewById(R.id.capture_comment_input).setText("只保存");c.get().findViewById(R.id.capture_editor_save).performClick();drain(c.get(),"executor");assertNull(shadowOf(c.get()).getNextStartedActivity());
        }
    }
    @Test public void historyIsRecordScopedAndGlobalHistorySearchesMessages()throws Exception{
        var one=note("第一条记录");var two=note("第二条记录");conversation(one,"第一段问题","回答甲");conversation(two,"第二段问题","回答乙");
        try(var c=Robolectric.buildActivity(AiChatHistoryActivity.class,AiUi.history(app,"guest",one.id)).setup()){
            drain(c.get(),"io");String view=text(c.get().findViewById(R.id.ai_history_list));assertTrue(view.contains("第一段问题"));assertFalse(view.contains("第二段问题"));
        }
        try(var c=Robolectric.buildActivity(AiChatHistoryActivity.class,AiUi.history(app,"guest",null)).setup()){
            drain(c.get(),"io");assertTrue(text(c.get().findViewById(R.id.ai_history_list)).contains("第二段问题"));
            c.get().<EditText>findViewById(R.id.ai_history_search).setText("回答甲");drain(c.get(),"io");String view=text(c.get().findViewById(R.id.ai_history_list));assertTrue(view.contains("第一段问题"));assertFalse(view.contains("第二段问题"));
        }
    }
    @Test public void recordListBadgeAndChatFilterAreIndependentFromTags()throws Exception{
        var one=note("聊过的记录");note("还没聊的记录");conversation(one,"讨论主题","回答");
        try(var c=Robolectric.buildActivity(CaptureInboxActivity.class).setup()){
            var a=c.get();drain(a,"refreshExecutor");LinearLayout list=a.findViewById(R.id.capture_records);assertEquals(2,list.getChildCount());
            int badges=0;for(int i=0;i<list.getChildCount();i++)if(list.getChildAt(i).findViewById(R.id.ai_record_badge).getVisibility()==View.VISIBLE)badges++;assertEquals(1,badges);
            a.findViewById(R.id.capture_tag_filter).performClick();AlertDialog filter=ShadowAlertDialog.getLatestAlertDialog();filter.<RadioGroup>findViewById(R.id.ai_filter_group).check(R.id.ai_filter_chatted);filter.findViewById(R.id.journal_filter_apply).performClick();
            assertEquals(1,list.getChildCount());assertTrue(text(list).contains("聊过的记录"));
            a.findViewById(R.id.journal_filter_summary).performClick();assertEquals(2,list.getChildCount());
        }
    }
    @Test public void changedRecordKeepsOldConversationSnapshotVisible()throws Exception{
        var record=note("原来的想法");String id=conversation(record,"为什么？","这是旧资料的回答");
        CaptureRecordEdits.save(app,"guest",record.id,CaptureRecordEdits.fingerprint(record),"更新后的想法",record.sourceText,CaptureRecordEdits.original(record));
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(app,"guest",record.id,id)).setup()){
            drain(c.get(),"io");assertTrue(c.get().<TextView>findViewById(R.id.ai_chat_status).getText().toString().contains("更新"));
            c.get().findViewById(R.id.ai_chat_context).performClick();AlertDialog context=ShadowAlertDialog.getLatestAlertDialog();String content=text(context.getWindow().getDecorView());
            assertTrue(content.contains("原来的想法"));assertFalse(content.contains("更新后的想法"));context.dismiss();
        }
    }
    @Test public void markdownIsStyledButNeverExecutesHtmlOrLoadsImages(){
        CharSequence text=AiMessageText.render("# 标题\n**重点**\n<script>alert(1)</script>\n![remote](https://example.test/tracker)");
        assertTrue(text instanceof android.text.Spanned);assertTrue(text.toString().contains("<script>"));
        assertEquals(0,((android.text.Spanned)text).getSpans(0,text.length(),android.text.style.ImageSpan.class).length);
        assertEquals(0,((android.text.Spanned)text).getSpans(0,text.length(),android.text.style.URLSpan.class).length);
    }
    @Test public void crossPlatformFingerprintDoesNotFalselyMarkSnapshotChanged()throws Exception{
        var record=note("原来的想法");
        JSONObject snapshot=AiChatStore.snapshot(app,record.id,new HashSet<>(Arrays.asList("excerpt","metadata")));
        snapshot.put("fingerprint","windows-local-hash");assertFalse(AiUi.snapshotChanged(snapshot,record));
        CaptureRecordEdits.save(app,"guest",record.id,CaptureRecordEdits.fingerprint(record),"未包含的想法变了",record.sourceText,CaptureRecordEdits.original(record));
        assertFalse(AiUi.snapshotChanged(snapshot,CaptureRecordEdits.latest(app,"guest",record.id)));
    }
}
