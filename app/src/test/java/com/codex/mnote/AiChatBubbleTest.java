package com.codex.mnote;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",shadows=AiChatUiTest.Crypto.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AiChatBubbleTest {
    private AiChatUiTest fixture;
    @Before public void setup()throws Exception {fixture=new AiChatUiTest();fixture.setup();}
    private static JSONObject message(String role,String text,String status)throws Exception {
        return new JSONObject().put("id",role).put("role",role).put("content",text).put("status",status);
    }
    private static void layout(View view,int width,int height){
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST));
        view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
    }
    private static PopupMenu open(AiChatMessageView row){
        assertTrue(row.findViewById(R.id.ai_message_bubble).performLongClick());
        PopupMenu menu=ShadowPopupMenu.getLatestPopupMenu();assertTrue(shadowOf(menu).isShowing());return menu;
    }
    private static void choose(PopupMenu menu,int id){
        assertTrue(shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().findItem(id)));menu.dismiss();
    }
    @Test public void bothRolesHaveOppositeAlignedBoundedBubblesAndReadableColors()throws Exception{
        try(var controller=Robolectric.buildActivity(Activity.class).setup()){
            Activity a=controller.get();LinearLayout root=JournalUi.column(a);a.setContentView(root);
            for(boolean own:new boolean[]{true,false}){
                AiChatMessageView row=new AiChatMessageView(a,"m",own,()->{});root.addView(row);
                row.bind(message(own?"user":"assistant","短消息","complete"),true);layout(row,320,2000);
                View bubble=row.findViewById(R.id.ai_message_bubble);int shortWidth=bubble.getWidth();
                if(own){assertEquals(320,bubble.getRight());assertEquals(a.getColor(R.color.white),row.content.getCurrentTextColor());}
                else {assertEquals(0,bubble.getLeft());assertEquals(a.getColor(R.color.ink),row.content.getCurrentTextColor());}
                row.bind(message(own?"user":"assistant","这是一条很长的消息。\n".repeat(100),"complete"),true);layout(row,320,20000);
                assertTrue(bubble.getWidth()>shortWidth);assertTrue(bubble.getWidth()<=Math.round(320*.86f));
                assertTrue(row.content.getLineCount()>10);assertTrue(row.content.getLayout().getLineEnd(row.content.getLineCount()-1)>=row.content.length());
            }
        }
    }
    @Test public void longPressOffersRawCopyForBothRolesWithoutAutoCopy()throws Exception{
        try(var c=Robolectric.buildActivity(Activity.class).setup()){
            ClipboardManager clipboard=(ClipboardManager)c.get().getSystemService(Context.CLIPBOARD_SERVICE);
            for(boolean own:new boolean[]{false,true}){
                String raw="**原始文字**\n`code`\n![image](https://example.test/never-load)";
                AiChatMessageView row=new AiChatMessageView(c.get(),"m",own,()->{});c.get().setContentView(row);row.bind(message(own?"user":"assistant",raw,"complete"),true);
                clipboard.setPrimaryClip(ClipData.newPlainText("before","保留剪贴板"));PopupMenu menu=open(row);
                assertEquals("保留剪贴板",clipboard.getPrimaryClip().getItemAt(0).getText());assertNull(menu.getMenu().findItem(AiChatMessageView.RETRY));
                choose(menu,AiChatMessageView.COPY);assertEquals(raw,clipboard.getPrimaryClip().getItemAt(0).getText());
                assertFalse(AiChatUiTest.text(row).contains("复制"));
                assertFalse(row.content.isTextSelectable());
            }
        }
    }
    @Test public void selectionIsScrollableAndFrozenWhileReplyStreams()throws Exception{
        try(var c=Robolectric.buildActivity(Activity.class).setup()){
            AiChatMessageView row=new AiChatMessageView(c.get(),"m",false,()->{});c.get().setContentView(row);
            row.bind(message("assistant","第一段\n".repeat(50),"generating"),true);PopupMenu menu=open(row);
            row.bind(message("assistant","第一段\n".repeat(50)+"继续生成的第二段","generating"),true);
            assertTrue(shadowOf(menu).isShowing());choose(menu,AiChatMessageView.SELECT);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();TextView selection=dialog.findViewById(R.id.ai_message_selection);
            assertTrue(selection.isTextSelectable());assertTrue(selection.getParent() instanceof ScrollView);
            assertEquals("第一段\n".repeat(50),selection.getText().toString());dialog.dismiss();
        }
    }
    @Test public void onlyLastIncompleteAiMessageCanRetryAndEmptyReplyCannotCopy()throws Exception{
        try(var c=Robolectric.buildActivity(Activity.class).setup()){
            int[] attempts={0};AiChatMessageView row=new AiChatMessageView(c.get(),"m",false,()->attempts[0]++);c.get().setContentView(row);
            row.bind(message("assistant","","generating"),true);PopupMenu empty=open(row);
            assertFalse(empty.getMenu().findItem(AiChatMessageView.COPY).isEnabled());assertNull(empty.getMenu().findItem(AiChatMessageView.RETRY));empty.dismiss();
            row.bind(message("assistant","未完成","stopped"),false);PopupMenu old=open(row);assertNull(old.getMenu().findItem(AiChatMessageView.RETRY));old.dismiss();
            row.bind(message("assistant","未完成","stopped"),true);choose(open(row),AiChatMessageView.RETRY);assertEquals(1,attempts[0]);
            row.bind(message("assistant","失败","failed"),true);assertNotNull(open(row).getMenu().findItem(AiChatMessageView.RETRY));
        }
    }
    @Test public void accessibilityAndKeyboardExposeActionsWithoutLongPress()throws Exception{
        try(var c=Robolectric.buildActivity(Activity.class).setup()){
            AiChatMessageView row=new AiChatMessageView(c.get(),"m",true,()->{});c.get().setContentView(row);row.bind(message("user","自己的消息","complete"),true);
            View bubble=row.findViewById(R.id.ai_message_bubble);AccessibilityNodeInfo info=bubble.createAccessibilityNodeInfo();
            assertTrue(info.getActionList().stream().anyMatch(action->action.getId()==AiChatMessageView.COPY));
            assertEquals("你：自己的消息",info.getContentDescription().toString());
            assertTrue(bubble.performAccessibilityAction(AiChatMessageView.COPY,null));
            assertEquals("自己的消息",((ClipboardManager)c.get().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText());
            layout(row,320,680);assertTrue(bubble.requestFocus());
            assertTrue(bubble.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MENU)));
            assertTrue(shadowOf(ShadowPopupMenu.getLatestPopupMenu()).isShowing());
        }
    }
    @Test public void streamingReusesViewsAndDoesNotJumpWhileReadingOlderMessages()throws Exception{
        var note=fixture.note("笔记");String id=fixture.conversation(note,"问题","内容\n".repeat(150));
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(fixture.app,"guest",note.id,id)).setup()){
            AiChatUiTest.drain(c.get(),"io");LinearLayout list=c.get().findViewById(R.id.ai_chat_messages);ScrollView scroll=(ScrollView)list.getParent();
            layout(c.get().getWindow().getDecorView(),390,844);scroll.scrollTo(0,20);
            AiChatMessageView row=(AiChatMessageView)list.getChildAt(1);PopupMenu menu=open(row);
            JSONObject changed=AiChatStore.get(fixture.app,id);changed.getJSONArray("messages").getJSONObject(1).put("content","内容\n".repeat(151));
            ReflectionHelpers.callInstanceMethod(c.get(),"renderChat",ReflectionHelpers.ClassParameter.from(JSONObject.class,changed));
            assertSame(row,list.getChildAt(1));assertTrue(shadowOf(menu).isShowing());
            shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(20,scroll.getScrollY());menu.dismiss();
            // Cancel an already queued follow as well: the user can scroll between two frames.
            scroll.scrollTo(0,list.getHeight());
            ReflectionHelpers.callInstanceMethod(c.get(),"renderChat",ReflectionHelpers.ClassParameter.from(JSONObject.class,changed));
            scroll.scrollTo(0,20);shadowOf(android.os.Looper.getMainLooper()).idle();
            layout(c.get().getWindow().getDecorView(),390,844);assertEquals(20,scroll.getScrollY());
        }
    }
}
