package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.function.Consumer;

/** A continuous, thought-first reader. Saved material is never hidden behind a preview tab. */
final class CaptureRecordPage {
    static AlertDialog show(Activity activity, CaptureStore.CaptureRecord record, Bitmap crop,
                            Bitmap full, Runnable delete, Consumer<String> openSource) {
        return show(activity,record,crop,full,delete,openSource,null);
    }
    static AlertDialog show(Activity activity, CaptureStore.CaptureRecord record, Bitmap crop,
                            Bitmap full, Runnable delete, Consumer<String> openSource,Runnable edit) {
        LinearLayout page=JournalUi.column(activity);
        page.setBackgroundColor(activity.getColor(R.color.cream));
        page.setFitsSystemWindows(true);
        LinearLayout header=new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity,12),dp(activity,8),dp(activity,12),dp(activity,8));
        Button close=new Button(activity); close.setText("返回"); close.setId(R.id.capture_review_close); JournalUi.quiet(close);
        header.addView(close,new LinearLayout.LayoutParams(-2,-2));
        TextView title=JournalUi.text(activity,"记录",18,R.color.ink);
        title.setGravity(Gravity.CENTER);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button share=new Button(activity);share.setId(R.id.share_card_open);share.setText("分享");JournalUi.quiet(share);
        share.setOnClickListener(v->activity.startActivity(new android.content.Intent(activity,ShareCardActivity.class)
            .putExtra(CaptureRecordEditActivity.ID,record.id)
            .putExtra(CaptureRecordEditActivity.SCOPE,CaptureAccountSession.scope(activity))));
        header.addView(share,new LinearLayout.LayoutParams(-2,-2));
        Button editButton=new Button(activity);editButton.setText("编辑");editButton.setId(R.id.record_edit_open);JournalUi.quiet(editButton);
        if(edit!=null)header.addView(editButton,new LinearLayout.LayoutParams(-2,-2));
        page.addView(header);JournalUi.rule(page);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=JournalUi.column(activity);body.setId(R.id.capture_review_body);
        body.setPadding(dp(activity,22),0,dp(activity,22),dp(activity,32));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        org.json.JSONObject imageMetadata=record.captureContext.optJSONObject("image");
        boolean contextOnly=imageMetadata!=null && "page_context".equals(imageMetadata.optString("purpose"));
        Bitmap excerptImage=contextOnly?null:crop;
        Bitmap pageImage=full!=null?full:contextOnly?crop:null;
        if(!record.comment.isEmpty()) {
            JournalUi.section(body,"我的想法");
            TextView thought=readable(activity,record.comment,18);
            thought.setId(R.id.journal_review_thought);body.addView(thought);
            if(!record.sourceText.isEmpty() || record.hasImage || record.contextFile!=null) { space(body,24);JournalUi.rule(body); }
        }
        if(!record.sourceText.isEmpty()) {
            space(body,24);
            TextView tab=JournalUi.text(activity,"摘录",13,R.color.white);
            tab.setBackgroundResource(R.drawable.bg_material_tab);tab.setPadding(dp(activity,12),dp(activity,4),dp(activity,12),dp(activity,4));
            body.addView(tab,new LinearLayout.LayoutParams(-2,-2));
            TextView excerpt=readable(activity,record.sourceText,16);
            excerpt.setId(R.id.journal_review_excerpt);
            excerpt.setBackgroundResource(R.drawable.bg_capture_text_preview);
            excerpt.setPadding(dp(activity,14),dp(activity,14),dp(activity,14),dp(activity,14));
            body.addView(excerpt,new LinearLayout.LayoutParams(-1,-2));
            if("clipboard".equals(record.sourceType)) {
                TextView warning=JournalUi.text(activity,"来自剪贴板，附加页面不一定是其原始出处",13,R.color.ink_muted);
                warning.setPadding(0,dp(activity,8),0,0);body.addView(warning);
            }
        }
        if(excerptImage!=null) {
            if(record.sourceText.isEmpty())JournalUi.section(body,"圈选截图");else space(body,16);
            image(body,excerptImage,null,R.id.capture_selection_preview,"圈选截图");
        }
        if(pageImage!=null) {
            JournalUi.section(body,"完整页面");
            image(body,pageImage,imageMetadata,
                excerptImage==null?R.id.capture_selection_preview:R.id.journal_review_context,"完整页面截图");
        }
        if(crop==null && full==null && (record.hasImage || record.contextFile!=null)) {
            JournalUi.section(body,"截图");
            body.addView(JournalUi.text(activity,"截图暂时无法显示，原记录仍保留。可返回刷新后重试。",14,R.color.ink_muted));
        }
        String original=CaptureRecordEdits.original(record);
        if(!original.isEmpty()) {
            space(body,24);JournalUi.rule(body);JournalUi.section(body,"页面原文");
            TextView text=readable(activity,original,16);text.setId(R.id.journal_review_original);body.addView(text);
            TextView note=JournalUi.text(activity,"记录时保存，可能不完整",13,R.color.ink_muted);
            note.setPadding(0,dp(activity,12),0,0);body.addView(note);
        }
        if(!record.sourceUrl.isEmpty()) {
            space(body,24);JournalUi.rule(body);
            Button source=new Button(activity);JournalUi.quiet(source);
            String host=android.net.Uri.parse(record.sourceUrl).getHost();
            source.setText(host==null?"打开来源链接":"打开来源 · "+host);
            source.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            source.setContentDescription("打开来源链接："+record.sourceUrl);
            source.setOnClickListener(view->openSource.accept(record.sourceUrl));
            body.addView(source,new LinearLayout.LayoutParams(-1,-2));
        }
        space(body,24);JournalUi.rule(body);
        String info=android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",record.createdAt).toString()
            +" · "+activity.getString("todo".equals(record.kind)?R.string.capture_kind_todo:
                "thought".equals(record.kind)?R.string.capture_kind_thought:R.string.capture_kind_comment);
        if(!record.sourcePackage.isEmpty())info+=" · "+CaptureSourceContext.appLabel(activity,record.sourcePackage);
        if(record.tags.length()>0)info+="\n"+CaptureTags.display(record.tags);
        TextView details=JournalUi.text(activity,info,13,R.color.ink_muted);details.setTextIsSelectable(true);
        details.setPadding(0,dp(activity,16),0,dp(activity,12));body.addView(details);
        Button remove=new Button(activity);remove.setText("删除记录");remove.setId(R.id.capture_review_delete);
        JournalUi.quiet(remove);remove.setTextColor(activity.getColor(R.color.danger));
        body.addView(remove,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(activity).create();dialog.setView(page,0,0,0,0);
        close.setOnClickListener(view->dialog.dismiss());
        editButton.setOnClickListener(view->{dialog.dismiss();if(edit!=null)edit.run();});
        remove.setOnClickListener(view->{dialog.dismiss();delete.run();});
        AiRecordSection.attach(activity,page,record);
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.cream)));
        dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);
        return dialog;
    }
    private static TextView readable(Activity activity,String value,int size) {
        TextView text=JournalUi.text(activity,value,size,R.color.ink);
        text.setLineSpacing(dp(activity,4),1.2f);text.setTextIsSelectable(true);return text;
    }
    private static void image(LinearLayout body,Bitmap bitmap,org.json.JSONObject metadata,int id,String description) {
        Activity activity=(Activity)body.getContext();
        CaptureContextPreview preview=new CaptureContextPreview(activity,bitmap,metadata);
        preview.setId(id);preview.setAdjustViewBounds(true);preview.setBackgroundResource(R.drawable.bg_capture_text_preview);preview.setClipToOutline(true);
        int natural=Math.round((activity.getResources().getDisplayMetrics().widthPixels-dp(activity,44))*(float)bitmap.getHeight()/Math.max(1,bitmap.getWidth()));
        int compact=Math.min(dp(activity,320),Math.max(dp(activity,100),natural));
        body.addView(preview,new LinearLayout.LayoutParams(-1,compact));
        preview.setContentDescription(description+"，点击放大查看");
        preview.setOnClickListener(v->{
            LinearLayout fullPage=JournalUi.column(activity);fullPage.setBackgroundColor(activity.getColor(R.color.cream));
            Button close=new Button(activity);close.setText("关闭图片");JournalUi.quiet(close);fullPage.addView(close);
            ScrollView scroll=new ScrollView(activity);
            CaptureContextPreview fullImage=new CaptureContextPreview(activity,bitmap,metadata);
            fullImage.setAdjustViewBounds(true);scroll.addView(fullImage,new ScrollView.LayoutParams(-1,-2));
            fullPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            AlertDialog viewer=new AlertDialog.Builder(activity).create();viewer.setView(fullPage,0,0,0,0);viewer.show();
            viewer.getWindow().setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.cream)));
            viewer.getWindow().setLayout(-1,-1);close.setOnClickListener(button->viewer.dismiss());
        });
    }
    private static void space(LinearLayout body,int value) {
        View space=new View(body.getContext());body.addView(space,new LinearLayout.LayoutParams(1,dp(body.getContext(),value)));
    }
    private static int dp(android.content.Context context,int value) {return JournalUi.dp(context,value);}
}
