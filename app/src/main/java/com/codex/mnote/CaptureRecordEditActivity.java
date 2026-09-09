package com.codex.mnote;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Explicit editing of saved text; the record identity and captured images stay unchanged. */
public final class CaptureRecordEditActivity extends Activity {
    static final String ID="record_id", SCOPE="record_scope";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private EditText comment,quote,original;
    private TextView status;
    private Button save,cancel;
    private String id,scope,baseline="";
    private boolean loading=true,saving,destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        id=getIntent().getStringExtra(ID); scope=getIntent().getStringExtra(SCOPE);
        buildPage();
        if(android.os.Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::requestClose);
        if(state!=null && state.containsKey("baseline")) {
            baseline=state.getString("baseline","");comment.setText(state.getString("comment",""));
            quote.setText(state.getString("quote",""));original.setText(state.getString("original",""));
        }
        executor.execute(()->{
            try {
                CaptureStore.CaptureRecord record;
                synchronized(CaptureAccountSession.LOCK) {record=CaptureRecordEdits.latest(this,scope,id);}
                String fingerprint=CaptureRecordEdits.fingerprint(record);
                runOnUiThread(()->{
                    if(destroyed || isFinishing()) return;
                    baseline=state==null ? fingerprint : state.getString("baseline",fingerprint);
                    comment.setText(state==null ? record.comment : state.getString("comment",record.comment));
                    quote.setText(state==null ? record.sourceText : state.getString("quote",record.sourceText));
                    original.setText(state==null ? CaptureRecordEdits.original(record)
                            : state.getString("original",CaptureRecordEdits.original(record)));
                    try {
                        if(fingerprint.equals(CaptureRecordEdits.fingerprint(comment.getText().toString(),
                                quote.getText().toString(),original.getText().toString()))) baseline=fingerprint;
                    } catch(Exception ignored) { }
                    loading=false;setBusy(false);
                    status.setText("http_409".equals(record.syncLastError)
                            ? "云端存在另一版本，本机修改已保留，尚未覆盖云端。"
                            : record.hasImage ? "修改文字不会改变已保存的截图和圈选区域。"
                            : "guest".equals(scope) ? "直接更新这条本机记录。" : "直接更新这条记录，联网后同步到当前账号。");
                });
            } catch(Exception error) {runOnUiThread(()->{
                if(destroyed) return;
                status.setText(CaptureRecordEdits.errorMessage(error));
                if(state!=null && state.containsKey("baseline")) {loading=false;setBusy(false);}
                cancel.setEnabled(true);
            });}
        });
    }

    private void buildPage() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.cream));root.setFitsSystemWindows(true);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12),dp(8),dp(12),dp(8));
        cancel=new Button(this);cancel.setId(R.id.record_edit_cancel);cancel.setText("取消");
        cancel.setOnClickListener(view->requestClose());header.addView(cancel);
        TextView title=label("编辑记录",17);title.setGravity(Gravity.CENTER);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        save=new Button(this);save.setId(R.id.record_edit_save);save.setText("保存");
        save.setBackgroundResource(R.drawable.bg_button_secondary);save.setOnClickListener(view->save());header.addView(save);
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20),dp(4),dp(20),dp(24));scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=label("正在读取记录…",13);status.setId(R.id.record_edit_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        comment=field(body,"我的想法","写下你的想法（最多 2 万字）",R.id.record_edit_comment);
        quote=field(body,"摘录文字","当时摘录的文字，可补充或修改（最多 10 万字）",R.id.record_edit_quote);
        original=field(body,"保留的原文","可补充原文；留空则从当前记录移除原文（最多 4 万字）",R.id.record_edit_original);
        TextView help=label("原文与摘录修改后会标记为手动编辑；历史截图保持不变。",12);
        LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(-1,-2);helpParams.topMargin=dp(16);body.addView(help,helpParams);
        setContentView(root);setBusy(true);cancel.setEnabled(true);
    }
    private EditText field(LinearLayout body,String title,String hint,int id) {
        TextView label=label(title,13);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(24);body.addView(label,lp);
        EditText input=new EditText(this);input.setId(id);input.setHint(hint);input.setTextSize(16);
        input.setTextColor(getColor(R.color.ink));input.setHintTextColor(getColor(R.color.ink_muted));
        input.setBackgroundResource(R.drawable.bg_input);input.setPadding(dp(16),dp(16),dp(16),dp(16));
        input.setGravity(Gravity.TOP|Gravity.START);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);input.setMaxLines(8);input.setLineSpacing(dp(4),1);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setSaveEnabled(false);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.topMargin=dp(8);body.addView(input,ip);
        return input;
    }
    private TextView label(String text,int size) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);
        view.setTextColor(getColor(size<=13 ? R.color.ink_muted : R.color.ink));return view;
    }
    private void setBusy(boolean busy) {
        save.setEnabled(!busy);cancel.setEnabled(!busy);
        for(EditText input:new EditText[]{comment,quote,original}) if(input!=null) input.setEnabled(!busy);
    }
    private void save() {
        if(loading || saving) return;
        String note=comment.getText().toString(),selected=quote.getText().toString(),full=original.getText().toString();
        saving=true;setBusy(true);status.setText("正在保存…");
        executor.execute(()->{
            try {
                CaptureRecordEdits.save(this,scope,id,baseline,note,selected,full);
                runOnUiThread(()->{
                    if(destroyed) return;
                    Toast.makeText(this,"修改已保存"+("guest".equals(scope) ? "" : "，联网后同步"),Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK,new Intent().putExtra(ID,id).putExtra(SCOPE,scope));finish();
                });
            } catch(Exception error) {runOnUiThread(()->{
                if(destroyed) return;
                saving=false;setBusy(false);status.setText(CaptureRecordEdits.errorMessage(error));
                Toast.makeText(this,status.getText(),Toast.LENGTH_LONG).show();
            });}
        });
    }
    private boolean dirty() {
        try {return !loading && !baseline.equals(CaptureRecordEdits.fingerprint(comment.getText().toString(),
                quote.getText().toString(),original.getText().toString()));}
        catch(Exception error) {return true;}
    }
    private void requestClose() {
        if(saving) return;
        if(!dirty()) {finish();return;}
        new AlertDialog.Builder(this).setTitle("放弃未保存的修改？").setMessage("原记录不会改变。")
                .setNegativeButton("继续编辑",null).setPositiveButton("放弃修改",(dialog,which)->finish()).show();
    }
    @Override public void onBackPressed() {requestClose();}
    @Override protected void onSaveInstanceState(Bundle state) {
        if(!loading) {state.putString("baseline",baseline);state.putString("comment",comment.getText().toString());
            state.putString("quote",quote.getText().toString());state.putString("original",original.getText().toString());}
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {destroyed=true;executor.shutdown();super.onDestroy();}
    private int dp(int value) {return CaptureReadingLayout.dp(this,value);}
}
