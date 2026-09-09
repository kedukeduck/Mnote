package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.function.Consumer;

/** Full-height reading surface; original material and the user's thoughts never share a block. */
final class CaptureRecordPage {
    static AlertDialog show(Activity activity, CaptureStore.CaptureRecord record, Bitmap crop,
                            Bitmap full, Runnable delete, Consumer<String> openSource) {
        return show(activity,record,crop,full,delete,openSource,null);
    }
    static AlertDialog show(Activity activity, CaptureStore.CaptureRecord record, Bitmap crop,
                            Bitmap full, Runnable delete, Consumer<String> openSource,Runnable edit) {
        LinearLayout page=column(activity);
        page.setBackgroundColor(activity.getColor(R.color.cream));
        page.setFitsSystemWindows(true);
        LinearLayout header=new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity,12),dp(activity,8),dp(activity,12),dp(activity,8));
        Button close=new Button(activity); close.setText("返回"); close.setId(R.id.capture_review_close);
        headerAction(activity,close);
        header.addView(close,new LinearLayout.LayoutParams(-2,-2));
        TextView title=text(activity,"记录详情",17); title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button remove=new Button(activity); remove.setText("删除"); remove.setId(R.id.capture_review_delete);
        headerAction(activity,remove);
        remove.setTextColor(activity.getColor(R.color.danger));
        Button editButton=new Button(activity);editButton.setText("编辑");editButton.setId(R.id.record_edit_open);
        headerAction(activity,editButton);
        header.addView(edit==null ? remove : editButton,new LinearLayout.LayoutParams(-2,-2));
        page.addView(header);
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=column(activity); body.setId(R.id.capture_review_body);
        body.setPadding(dp(activity,20),dp(activity,8),dp(activity,20),dp(activity,24));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(crop!=null || full!=null) {
            CaptureContextPreview preview=new CaptureContextPreview(activity,crop!=null ? crop : full,
                    crop!=null ? null : record.captureContext.optJSONObject("image"));
            preview.setId(R.id.capture_selection_preview); preview.setAdjustViewBounds(false);
            preview.setBackgroundResource(R.drawable.bg_capture_text_preview); preview.setClipToOutline(true);
            body.addView(preview,new LinearLayout.LayoutParams(-1,dp(activity,320)));
            preview.setContentDescription("截图预览，轻点展开或收起");
            preview.setOnClickListener(view->{
                android.view.ViewGroup.LayoutParams bounds=preview.getLayoutParams();
                if(bounds.height!=dp(activity,320)) bounds.height=dp(activity,320);
                else if(preview.getDrawable()!=null) bounds.height=Math.max(dp(activity,320),
                        Math.round((float)preview.getWidth()*preview.getDrawable().getIntrinsicHeight()
                                /Math.max(1,preview.getDrawable().getIntrinsicWidth())));
                preview.setLayoutParams(bounds);
            });
            RadioGroup modes=CapturePreviewModes.create(activity,false,showFull ->
            {
                preview.setContent(showFull ? full : crop,showFull ? record.captureContext.optJSONObject("image") : null);
                preview.getLayoutParams().height=dp(activity,320); preview.requestLayout();
            });
            modes.findViewById(R.id.capture_preview_full).setEnabled(full!=null);
            modes.findViewById(R.id.capture_preview_crop).setEnabled(crop!=null);
            if(crop==null) modes.check(R.id.capture_preview_full);
            add(body,modes,12);
            if(full==null) add(body,text(activity,"未保留完整截图",12),8);
        } else if(record.hasImage) {
            add(body,text(activity,"截图暂时无法显示，记录仍保留。",14),8);
        }
        if(!record.sourceText.isEmpty() || !CaptureRecordEdits.original(record).isEmpty()) {
            TextView quote=block(activity,record.sourceText.isEmpty() ? "未记录摘录文字" : record.sourceText,16);
            quote.setMinHeight(dp(activity,220));
            add(body,quote,12);
            org.json.JSONObject context=record.captureContext.optJSONObject("text");
            String original=context==null ? "" : context.optString("full_text","");
            if(!original.isEmpty()) {
                RadioGroup textModes=CapturePreviewModes.create(activity,true,
                        showFull -> quote.setText(showFull ? original : record.sourceText.isEmpty() ? "未记录摘录文字" : record.sourceText));
                add(body,textModes,12);
                if(record.sourceText.isEmpty()) textModes.check(R.id.capture_text_preview_original);
            }
        }
        Button source=new Button(activity);
        String host=android.net.Uri.parse(record.sourceUrl).getHost();
        source.setText(record.sourceUrl.isEmpty() ? "未记录来源链接"
                : "↗  "+(host==null ? "打开来源" : host)+"  ›");
        source.setEnabled(!record.sourceUrl.isEmpty());
        source.setOnClickListener(view->openSource.accept(record.sourceUrl));
        if(!record.sourceUrl.isEmpty()) add(body,source,16);
        add(body,text(activity,"我的想法",13),24);
        TextView thought=block(activity,record.comment.isEmpty() ? "这条记录没有附加想法。" : record.comment,16);
        thought.setMinHeight(dp(activity,144)); add(body,thought,8);
        TextView details=text(activity,android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",record.createdAt)
                +"\n"+activity.getString("todo".equals(record.kind) ? R.string.capture_kind_todo
                        : "thought".equals(record.kind) ? R.string.capture_kind_thought : R.string.capture_kind_comment)
                +"\n"+(record.sourcePackage.isEmpty() ? "来源应用未记录" : CaptureSourceContext.appLabel(activity,record.sourcePackage))
                +"\n"+(CaptureStore.SYNC_SYNCED.equals(record.syncState) ? "已同步" : "同步状态以首页为准")
                +"\n保真级别："+record.fidelityLevel+" · AI 权限："+record.aiAccess
                +(record.sourceUrl.isEmpty() ? "" : "\n"+record.sourceUrl),13);
        details.setTextIsSelectable(true);
        details.setVisibility(View.GONE);
        Button information=new Button(activity); information.setText("记录信息  ›");
        information.setOnClickListener(view->details.setVisibility(details.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE));
        add(body,information,20); add(body,details,8);
        if(edit!=null) add(body,remove,20);
        AlertDialog dialog=new AlertDialog.Builder(activity).create();
        dialog.setView(page,0,0,0,0);
        close.setOnClickListener(view->dialog.dismiss());
        editButton.setOnClickListener(view->{dialog.dismiss();if(edit!=null) edit.run();});
        remove.setOnClickListener(view->{dialog.dismiss();delete.run();});
        dialog.setOnDismissListener(ignored->{
            if(crop!=null && !crop.isRecycled()) crop.recycle();
            if(full!=null && full!=crop && !full.isRecycled()) full.recycle();
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.cream)));
        dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);
        return dialog;
    }
    private static LinearLayout column(Activity a) { LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private static void headerAction(Activity a,Button button) {
        android.util.TypedValue background=new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,background,true);
        button.setBackgroundResource(background.resourceId);
        button.setMinWidth(dp(a,64)); button.setMinimumWidth(dp(a,64));
        button.setMinHeight(dp(a,48)); button.setMinimumHeight(dp(a,48));
    }
    private static TextView text(Activity a,String value,int size) {
        TextView v=new TextView(a);v.setText(value);v.setTextSize(size);
        v.setTextColor(a.getColor(size<=13 ? R.color.ink_muted : R.color.ink));v.setLineSpacing(dp(a,4),1);return v;
    }
    private static TextView block(Activity a,String value,int size) {
        TextView v=text(a,value,size);v.setTextIsSelectable(true);
        v.setBackgroundResource(R.drawable.bg_card);v.setPadding(dp(a,16),dp(a,16),dp(a,16),dp(a,16));return v;
    }
    private static void add(LinearLayout column,View view,int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(column.getContext(),top);column.addView(view,p);
    }
    private static int dp(android.content.Context context,int value) {return CaptureReadingLayout.dp(context,value);}
}
