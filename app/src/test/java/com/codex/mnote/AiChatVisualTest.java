package com.codex.mnote;

import android.graphics.*;
import android.view.View;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;

/** Synthetic real-native UI renders; no provider traffic, production accounts or personal notes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="zh-rCN-w390dp-h844dp-mdpi",instrumentedPackages="com.codex.mnote",shadows=AiChatUiTest.Crypto.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AiChatVisualTest {
    @Test public void nativeConversationScreensAndKeyboardSizedViewport()throws Exception{
        AiChatUiTest fixture=new AiChatUiTest();fixture.setup();
        var record=fixture.note("想把每周回顾变成习惯。不只看收藏了什么，也看看自己为什么会被触动。");
        String id=fixture.conversation(record,"我总是收藏很多，却很少回看。怎样开始才不会又变成负担？",
                "可以先把回顾做得很小。\n\n## 本周只选一条\n不必整理所有收藏，先找那条让你最想继续想下去的记录。\n\n> 重要的不是收藏数量，而是有没有形成自己的理解。\n\n- 看看当时写下的想法\n- 问自己：现在还有同样的感受吗？\n- 留下一个五分钟能做的小行动\n\n你更想先回看哪一类记录？");
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(fixture.app,"guest",record.id,id)).setup()){
            AiChatUiTest.drain(c.get(),"io");frame(c.get().getWindow().getDecorView(),390,844,"ai-chat-conversation");
            c.get().<EditText>findViewById(R.id.ai_chat_input).setText("我想从读书笔记开始。");
            frame(c.get().getWindow().getDecorView(),390,500,"ai-chat-keyboard-space");
            int[] location=new int[2];View input=c.get().findViewById(R.id.ai_chat_input);input.getLocationOnScreen(location);
            assertTrue("Input must remain in visible resized area",location[1]+input.getHeight()<=500);
            frame(c.get().getWindow().getDecorView(),320,680,"ai-chat-narrow");
        }
        String exchange=fixture.conversation(record,"总是收藏很多，却很少回看。怎么开始才不会有负担？",
                "先不整理所有收藏。每周只选 **一条最有触动的记录**，问问自己：\n\n当时为什么想记下来？现在有什么新的理解？");
        String turn=AiChatStore.beginTurn(fixture.app,exchange,"我想先从读书笔记开始。");
        AiChatStore.finish(fixture.app,exchange,turn,"可以。下次读到有共鸣的一段，除了摘录，再留下自己的一句话。\n\n回顾时，先读这句话。","complete","");
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(fixture.app,"guest",record.id,exchange)).setup()){
            AiChatUiTest.drain(c.get(),"io");frame(c.get().getWindow().getDecorView(),390,844,"ai-chat-im-bubbles");
        }
        RuntimeEnvironment.setFontScale(1.5f);
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(fixture.app,"guest",record.id,exchange)).setup()){
            AiChatUiTest.drain(c.get(),"io");View root=c.get().getWindow().getDecorView();
            frame(root,320,500,"ai-chat-large-text-keyboard");
            View input=c.get().findViewById(R.id.ai_chat_input),send=c.get().findViewById(R.id.ai_chat_send);
            int[] location=new int[2];input.getLocationOnScreen(location);
            assertTrue("Large-text input stays above keyboard",location[1]+input.getHeight()<=500);
            send.getLocationOnScreen(location);assertTrue("Send stays on screen",location[0]+send.getWidth()<=320);
            assertTrue("Composer retains usable width",input.getWidth()>=160);
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(c.get(),"setBusy",org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class,true));
            frame(root,320,500,"ai-chat-large-text-preparing");
            android.widget.TextView button=(android.widget.TextView)send;
            assertEquals("Preparing label stays on one line",1,button.getLayout().getLineCount());
            assertTrue("Preparing label fits width",button.getLayout().getLineWidth(0)<=button.getWidth()-button.getCompoundPaddingLeft()-button.getCompoundPaddingRight());
            assertTrue("Large text is not clipped vertically",button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom()<=button.getHeight());
        }finally{RuntimeEnvironment.setFontScale(1f);}
        try(var c=Robolectric.buildActivity(AiChatActivity.class,AiUi.chat(fixture.app,"guest",record.id,null)).setup()){
            AiChatUiTest.drain(c.get(),"io");frame(c.get().getWindow().getDecorView(),390,844,"ai-chat-new");
        }
        try(var c=Robolectric.buildActivity(AiChatHistoryActivity.class,AiUi.history(fixture.app,"guest",null)).setup()){
            AiChatUiTest.drain(c.get(),"io");frame(c.get().getWindow().getDecorView(),390,844,"ai-chat-history");
        }
        try(var c=Robolectric.buildActivity(AiModelSettingsActivity.class).setup()){
            frame(c.get().getWindow().getDecorView(),390,844,"ai-chat-models");
        }
    }
    private static void frame(View root,int width,int height,String name)throws Exception{
        // Drain window-manager traversals first; otherwise they restore the full display size
        // after our synthetic IME-resized viewport and turn this into a cropped screenshot.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        File directory=new File("build/ui-previews");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream stream=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{bitmap.recycle();}
    }
}
