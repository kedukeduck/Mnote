package com.codex.mnote;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** One stable, accessible IM message. Copy always uses the original text, not rendered Markdown. */
final class AiChatMessageView extends LinearLayout {
    static final int COPY=R.id.ai_message_copy, SELECT=R.id.ai_message_select, RETRY=R.id.ai_message_retry;
    final String messageId;
    final boolean own;
    final TextView content;
    private final LinearLayout bubble;
    private final TextView state;
    private final Runnable retry;
    private String raw="", lastRendered;
    private boolean retryable;
    private PopupMenu popup;
    private AlertDialog selectionDialog;

    AiChatMessageView(Context context,String id,boolean own,Runnable retry) {
        super(context);this.messageId=id;this.own=own;this.retry=retry;
        setOrientation(VERTICAL);setGravity(own?Gravity.RIGHT:Gravity.LEFT);
        setPadding(0,0,0,AiUi.dp(context,20));
        TextView name=JournalUi.text(context,own?"你":"AI",12,R.color.ink_muted);
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams label=new LayoutParams(-2,-2);label.bottomMargin=AiUi.dp(context,5);addView(name,label);
        bubble=JournalUi.column(context);bubble.setId(R.id.ai_message_bubble);
        bubble.setPadding(AiUi.dp(context,15),AiUi.dp(context,11),AiUi.dp(context,15),AiUi.dp(context,11));
        bubble.setMinimumHeight(AiUi.dp(context,48));bubble.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background=new GradientDrawable();background.setColor(context.getColor(own?R.color.coral:R.color.white));
        float round=AiUi.dp(context,18),corner=AiUi.dp(context,5);
        background.setCornerRadii(own?new float[]{round,round,corner,corner,round,round,round,round}
                :new float[]{corner,corner,round,round,round,round,round,round});
        if(!own)background.setStroke(AiUi.dp(context,1),context.getColor(R.color.line));
        bubble.setBackground(background);bubble.setFocusable(true);
        content=JournalUi.text(context,"",16,own?R.color.white:R.color.ink);content.setId(R.id.ai_message_content);
        content.setLineSpacing(AiUi.dp(context,3),1.08f);content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        bubble.addView(content,new LayoutParams(-2,-2));addView(bubble,new LayoutParams(-2,-2));
        state=JournalUi.text(context,"",12,R.color.ink_muted);state.setId(R.id.ai_message_state);
        LayoutParams stateLayout=new LayoutParams(-2,-2);stateLayout.topMargin=AiUi.dp(context,6);addView(state,stateLayout);
        state.setVisibility(GONE);
        bubble.setOnLongClickListener(v->{showActions();return true;});
        bubble.setOnKeyListener((v,key,event)->{
            if(event.getAction()==KeyEvent.ACTION_UP&&(key==KeyEvent.KEYCODE_MENU||(key==KeyEvent.KEYCODE_F10&&event.isShiftPressed()))){showActions();return true;}return false;
        });
        bubble.setAccessibilityDelegate(new AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"消息操作"));
                if(!raw.isEmpty()){
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(COPY,"复制消息"));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(SELECT,"选择文字"));
                }
                if(retryable)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(RETRY,"重试本轮"));
            }
            @Override public boolean performAccessibilityAction(View host,int action,Bundle args){
                if(action==COPY&&!raw.isEmpty()){copy(raw);return true;}
                if(action==SELECT&&!raw.isEmpty()){select(raw);return true;}
                if(action==RETRY&&retryable){retry.run();return true;}
                return super.performAccessibilityAction(host,action,args);
            }
        });
    }

    void bind(JSONObject message,boolean latest) {
        raw=message.optString("content");String status=message.optString("status");
        retryable=!own&&latest&&("failed".equals(status)||"stopped".equals(status));
        String displayed=raw.isEmpty()?("generating".equals(status)?"正在思考…":"未收到回复"):raw;
        if(!displayed.equals(lastRendered)){
            content.setText(AiMessageText.render(displayed,getContext().getColor(own?R.color.cream:R.color.copper)));
            lastRendered=displayed;
        }
        String detail=own?"":"generating".equals(status)?"正在回复…":"failed".equals(status)?AiUi.error(message.optString("error"))
                :"stopped".equals(status)?"已停止 · 回复未完成":"";
        if(retryable)detail+=" · 长按可重试";
        state.setText(detail);state.setVisibility(detail.isEmpty()?GONE:VISIBLE);
        bubble.setContentDescription((own?"你：":"AI：")+content.getText());
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        // Based on the actual pane, not display metrics: split screen and font scaling stay usable.
        int available=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        int maximum=Math.round(available*.86f);
        content.setMaxWidth(Math.max(1,maximum-bubble.getPaddingLeft()-bubble.getPaddingRight()));
        state.setMaxWidth(maximum);super.onMeasure(widthSpec,heightSpec);
    }

    private void showActions(){
        if(popup!=null)popup.dismiss();
        // Freeze this menu's copy/selection text while the stable bubble continues streaming.
        String selected=raw;
        popup=new PopupMenu(getContext(),bubble,own?Gravity.END:Gravity.START);
        Menu menu=popup.getMenu();
        menu.add(Menu.NONE,COPY,0,"复制消息").setEnabled(!selected.isEmpty());
        menu.add(Menu.NONE,SELECT,1,"选择文字").setEnabled(!selected.isEmpty());
        if(retryable)menu.add(Menu.NONE,RETRY,2,"重试本轮");
        popup.setOnMenuItemClickListener(item->{
            if(item.getItemId()==COPY){copy(selected);return true;}
            if(item.getItemId()==SELECT){select(selected);return true;}
            if(item.getItemId()==RETRY&&retryable){retry.run();return true;}return false;
        });popup.show();
    }

    private void copy(String text){
        ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(own?"我的消息":"AI 回复",text));
        Toast.makeText(getContext(),"已复制",Toast.LENGTH_SHORT).show();
    }
    private void select(String text){
        TextView selection=JournalUi.text(getContext(),text,16,R.color.ink);selection.setId(R.id.ai_message_selection);
        selection.setTextIsSelectable(true);selection.setPadding(AiUi.dp(getContext(),22),AiUi.dp(getContext(),12),AiUi.dp(getContext(),22),AiUi.dp(getContext(),18));
        ScrollView scroll=new ScrollView(getContext());scroll.addView(selection);
        selectionDialog=new AlertDialog.Builder(getContext()).setTitle("选择文字").setView(scroll)
                .setNegativeButton("关闭",null).setPositiveButton("复制全部",(d,w)->copy(text)).show();
    }
    @Override protected void onDetachedFromWindow(){
        if(popup!=null)popup.dismiss();if(selectionDialog!=null)selectionDialog.dismiss();super.onDetachedFromWindow();
    }
}
