package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A single opt-in editor. Page, clipboard, crop and thought are independent materials. */
public final class UnifiedCaptureActivity extends Activity {
    static final String EXTRA_DRAFT_PATH="com.codex.mnote.extra.CAPTURE_DRAFT_PATH";
    private static WeakReference<UnifiedCaptureActivity> active=new WeakReference<>(null);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ExecutorService pageExecutor=Executors.newSingleThreadExecutor();
    private final Map<String,Module> modules=new LinkedHashMap<>();
    private Session session;
    private UnifiedCaptureDraft draft;
    private FrameLayout root;
    private LinearLayout compose,canvas;
    private ScrollView scroll;
    private EditText thought,excerpt,original,url;
    private CaptureTags.Field tags;
    private RadioGroup kind;
    private TextView status,sourceLabel;
    private ImageView contextImage,cropImage;
    private Button editImage;
    private CaptureMarkupView markup;
    private Bitmap sourceBitmap,cropPreview;
    private boolean destroyed,resumed,focused,acquiring,loadingImages;
    private int pageGeneration,imageGeneration;
    private Future<?> pageTask;
    private Runnable settleCallback,timeoutCallback;
    private WindowManager.LayoutParams normalWindow;
    private int normalStatusColor,normalNavigationColor;

    static Intent forScreenshot(Context context,File screenshot,CaptureSourceContext source) {
        CaptureSourceContext value=source==null?CaptureSourceContext.EMPTY:source;
        Intent intent=forText(context).setAction("com.codex.mnote.action.UNIFIED_SCREENSHOT")
                .putExtra("capture_source_package",value.appPackage).putExtra("capture_source_url",value.url)
                .putExtra("capture_source_origin",value.origin);
        if(screenshot!=null)intent.putExtra(EXTRA_DRAFT_PATH,screenshot.getAbsolutePath());
        return intent;
    }
    static Intent forText(Context context) {
        return new Intent(context,UnifiedCaptureActivity.class).setAction("com.codex.mnote.action.UNIFIED_TEXT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        |Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }
    /** Repeated capture clicks resume the same private draft, never capture our own editor. */
    static boolean resumeExisting(Context context) {
        UnifiedCaptureActivity activity=active.get();
        if(activity==null||activity.destroyed||activity.isFinishing()||activity.draft==null
                ||!activity.draft.scope.equals(CaptureAccountSession.scope(context)))return false;
        try {
            android.app.ActivityManager manager=context.getSystemService(android.app.ActivityManager.class);
            if(manager!=null)for(android.app.ActivityManager.AppTask task:manager.getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo info=task.getTaskInfo();
                int taskId=android.os.Build.VERSION.SDK_INT>=29?info.taskId:info.id;
                if(taskId==activity.getTaskId()){task.moveToFront();return true;}
            }
            // Starting from the retained Activity uses its task, even when the trigger is in another task.
            activity.startActivity(new Intent(activity,UnifiedCaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch(RuntimeException error) {
            Toast.makeText(context,"已有一条记录正在编辑，请返回原记录页继续。",Toast.LENGTH_LONG).show();
        }
        return true;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                |WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            session=(Session)getLastNonConfigurationInstance();
            if(session==null) {
                if(state!=null)draft=UnifiedCaptureDraft.restore(this,state.getString("unified_draft"));
                else {
                    Intent intent=getIntent();
                    File screenshot=CaptureStore.safeDraftFile(this,intent.getStringExtra(EXTRA_DRAFT_PATH));
                    draft=UnifiedCaptureDraft.create(this,screenshot,new CaptureSourceContext(intent.getStringExtra("capture_source_package"),
                            intent.getStringExtra("capture_source_url"),intent.getStringExtra("capture_source_origin")));
                }
                session=new Session(draft);
            } else draft=session.draft;
            CaptureAccountSession.requireScope(this,draft.scope);
        } catch(Exception error) {
            Toast.makeText(this,"无法恢复这条草稿，可能已清理或账号已变化。请重新发起记录。",Toast.LENGTH_LONG).show();
            finish();return;
        }
        build();restoreFields();active=new WeakReference<>(this);
        if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::onBackPressed);
        if(!draft.recordId.isEmpty()) {finishSaved(session.pending!=null&&session.pending.chat);return;}
        if(session.pending!=null) {session.pending.receiver=new WeakReference<>(this);setBusy(true);handler.post(session.pending::deliver);}
        loadImages();
    }

    private void build() {
        root=new FrameLayout(this) {
            private int previousHeight;
            @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
                int height=bottom-top;
                boolean viewportShrank=previousHeight>0&&height<previousHeight;
                previousHeight=height;
                super.onLayout(changed,left,top,right,bottom);
                // Reveal the caret after the IME shrinks the viewport, not on every
                // content layout. Expanding/selecting tags must not pull the user
                // back to an off-screen input that still owns touch-mode focus.
                if(viewportShrank&&compose!=null&&compose.getVisibility()==View.VISIBLE
                        &&findFocus() instanceof EditText&&findFocus().isShown()) {
                    EditText input=(EditText)findFocus();
                    input.bringPointIntoView(Math.max(0,input.getSelectionEnd()));
                    CaptureReadingLayout.revealCursor(input);
                }
            }
        };root.setId(R.id.unified_root);root.setFitsSystemWindows(true);
        root.setBackgroundColor(getColor(R.color.cream));setContentView(root);
        compose=JournalUi.column(this);root.addView(compose,new FrameLayout.LayoutParams(-1,-1));
        JournalUi.header(this,compose,"记录这一刻",this::onBackPressed);
        scroll=new ScrollView(this);scroll.setId(R.id.unified_scroll);scroll.setFillViewport(true);
        scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=JournalUi.column(this);body.setPadding(dp(20),dp(18),dp(20),dp(24));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));compose.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView headline=JournalUi.text(this,"把触动，留在这里。",26,R.color.ink);
        headline.setTypeface(Typeface.create("serif",Typeface.NORMAL));body.addView(headline);
        TextView intro=JournalUi.text(this,"自由组合想法与资料。取消勾选只是不保存，编辑中的内容仍会保留。",13,R.color.ink_muted);
        intro.setPadding(0,dp(8),0,dp(14));body.addView(intro);
        status=JournalUi.text(this,"",13,R.color.copper);status.setId(R.id.unified_status);status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);

        Module context=module(body,"context",R.id.unified_context_toggle,"完整页面截图","保留最初的场景，不随圈选和批注改变。");
        contextImage=image(context.body,R.id.unified_context_preview,"最初完整页面截图",180);
        contextImage.setOnClickListener(v->showImage(sourceBitmap,"完整页面截图"));
        editImage=button(context.card,"圈选与批注  ↗",R.id.unified_edit_image,false,this::openCanvas);
        Module idea=module(body,"thought",R.id.unified_thought_toggle,"我的想法","这是你的声音，与摘录和原文分开保存。");
        thought=input(idea.body,R.id.unified_thought,"此刻想到什么？",4);
        Module quote=module(body,"excerpt",R.id.unified_excerpt_toggle,"摘录","手动输入，或由你主动读取剪贴板第一条文字。");
        excerpt=input(quote.body,R.id.unified_excerpt,"想保留的那一段…",4);CaptureLongText.attach(this,excerpt,"摘录");
        button(quote.body,"读取剪贴板第一条",R.id.unified_clipboard,false,this::readClipboard);
        Module page=module(body,"original",R.id.unified_original_toggle,"页面原文","与截图同时保留。读取仅尝试当前来源页，不保证文章全文。");
        original=input(page.body,R.id.unified_original,"粘贴或编辑完整原文…",5);CaptureLongText.attach(this,original,"页面原文");
        original.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){draft.pageRead=false;}
            public void afterTextChanged(android.text.Editable value){}
        });
        button(page.body,"尝试读取当前来源页",R.id.unified_read_page,false,()->confirmReplace(original,"替换页面原文？",this::requestPage));
        Module source=module(body,"source",R.id.unified_source_toggle,"来源应用与链接","可编辑链接或不保存来源；未勾选的来源不会附在其他模块中。");
        sourceLabel=JournalUi.text(this,"",13,R.color.ink_muted);source.body.addView(sourceLabel);
        url=input(source.body,R.id.unified_url,"https://…",1);url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        Module crop=module(body,"crop",R.id.unified_crop_toggle,"圈选与批注","单独保留你关注的区域；取消编辑不会替换已确认的结果。");
        cropImage=image(crop.body,R.id.unified_crop_preview,"已确认的圈选与批注",180);
        cropImage.setOnClickListener(v->showImage(cropPreview,"圈选与批注"));
        Module tag=module(body,"tags",R.id.unified_tags_toggle,"标签","可选择已有标签，也可以为这条记录新建标签。");
        getLayoutInflater().inflate(R.layout.capture_tags,tag.body,true);tags=new CaptureTags.Field(tag.body);
        JournalUi.section(body,"记录类型");kind=new RadioGroup(this);kind.setId(R.id.unified_kind);
        // Vertical keeps both full labels usable with enlarged system fonts.
        kind.setOrientation(RadioGroup.VERTICAL);body.addView(kind);
        for(int i=0;i<2;i++) {
            RadioButton option=new RadioButton(this);option.setId(i==0?R.id.unified_kind_thought:R.id.unified_kind_todo);
            option.setText(i==0?"想法 · 值得继续思考":"待办 · 想要采取行动");option.setTextSize(14);option.setMinHeight(dp(48));kind.addView(option);
        }
        button(body,"保存并与 AI 聊聊",R.id.unified_save_chat,false,()->save(true));
        TextView privacy=JournalUi.text(this,"先保存记录，再进入对话。这里不会自动读取剪贴板、页面文字或调用 AI。",12,R.color.ink_muted);
        body.addView(privacy);JournalUi.rule(compose);
        LinearLayout footer=JournalUi.column(this);footer.setPadding(dp(20),dp(8),dp(20),dp(10));compose.addView(footer);
        button(footer,"保存记录",R.id.unified_save,true,()->save(false));
        canvas=JournalUi.column(this);canvas.setId(R.id.unified_canvas);canvas.setBackgroundColor(getColor(R.color.cream));
        canvas.setVisibility(View.GONE);root.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
    }
    private int dp(int value) {return JournalUi.dp(this,value);}
    private Module module(LinearLayout parent,String key,int id,String title,String hint) {
        LinearLayout card=JournalUi.column(this);card.setBackgroundResource(R.drawable.bg_card);card.setPadding(dp(14),dp(10),dp(14),dp(14));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(14);parent.addView(card,layout);
        CheckBox toggle=new CheckBox(this);toggle.setId(id);toggle.setText(title);toggle.setTextSize(16);
        toggle.setTextColor(getColor(R.color.ink));toggle.setMinHeight(dp(48));card.addView(toggle,new LinearLayout.LayoutParams(-1,-2));
        TextView help=JournalUi.text(this,hint,12,R.color.ink_muted);help.setPadding(0,0,0,dp(10));card.addView(help);
        LinearLayout content=JournalUi.column(this);card.addView(content,new LinearLayout.LayoutParams(-1,-2));
        Module result=new Module(card,content,toggle);modules.put(key,result);
        toggle.setOnCheckedChangeListener((v,checked)->{
            if(checked)draft.selected.add(key);else draft.selected.remove(key);
            content.setVisibility(checked?View.VISIBLE:View.GONE);
        });return result;
    }
    private EditText input(LinearLayout parent,int id,String hint,int lines) {
        EditText field=new EditText(this);field.setId(id);field.setHint(hint);field.setTextSize(16);field.setTextColor(getColor(R.color.ink));
        field.setBackgroundResource(R.drawable.bg_input);field.setPadding(dp(12),dp(12),dp(12),dp(12));field.setGravity(Gravity.TOP|Gravity.START);
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(Math.min(2,lines));field.setMaxLines(lines);field.setMinimumHeight(dp(48));
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);CaptureLongText.enableScrolling(field);
        field.setOnFocusChangeListener((v,focused)->{if(focused)field.post(()->{
            if(field.isFocused()&&field.isShown())CaptureReadingLayout.revealCursor(field);
        });});
        parent.addView(field,new LinearLayout.LayoutParams(-1,-2));return field;
    }
    private Button button(LinearLayout parent,String text,int id,boolean primary,Runnable action) {
        Button button=AiUi.button(parent,text,primary,action);button.setId(id);button.setTextSize(14);button.setMinHeight(dp(48));return button;
    }
    private ImageView image(LinearLayout parent,int id,String description,int height) {
        ImageView view=new ImageView(this);view.setId(id);view.setContentDescription(description+"，点击放大查看");view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundColor(getColor(R.color.cream));parent.addView(view,new LinearLayout.LayoutParams(-1,dp(height)));return view;
    }
    private void showImage(Bitmap bitmap,String title) {
        if(bitmap==null||session.pending!=null||acquiring)return;
        LinearLayout page=JournalUi.column(this);page.setBackgroundColor(getColor(R.color.cream));
        AlertDialog dialog=new AlertDialog.Builder(this).create();
        button(page,"关闭 · "+title,View.NO_ID,false,dialog::dismiss);
        ScrollView viewer=new ScrollView(this);ImageView image=new ImageView(this);
        image.setAdjustViewBounds(true);image.setImageBitmap(bitmap);image.setContentDescription(title);
        viewer.addView(image,new ScrollView.LayoutParams(-1,-2));page.addView(viewer,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setView(page,0,0,0,0);dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    private void restoreFields() {
        boolean readPage=draft.pageRead;
        thought.setText(draft.thought);excerpt.setText(draft.excerpt);original.setText(draft.original);url.setText(draft.url);tags.input.setText(draft.tags);
        draft.pageRead=readPage;
        kind.check("todo".equals(draft.kind)?R.id.unified_kind_todo:R.id.unified_kind_thought);
        refreshModules();
    }
    private void refreshModules() {
        for(Map.Entry<String,Module> entry:modules.entrySet()) {
            boolean checked=draft.selected.contains(entry.getKey());entry.getValue().toggle.setChecked(checked);
            entry.getValue().body.setVisibility(checked?View.VISIBLE:View.GONE);
        }
        sourceLabel.setText(draft.source.appPackage.isEmpty()?"未识别来源应用，可直接填写链接。":"来源应用 · "+draft.source.appLabel(this));
        if(!loadingImages&&draft.screenshot==null)contextImage.setContentDescription("没有页面截图，仍可保存文字记录");
        editImage.setEnabled(!loadingImages&&session.pending==null&&!acquiring&&sourceBitmap!=null);
    }
    private void collect() {
        if(draft==null||thought==null)return;
        synchronized(draft) {
            draft.thought=thought.getText().toString();draft.excerpt=excerpt.getText().toString();draft.original=original.getText().toString();
            draft.url=url.getText().toString();draft.tags=tags.input.getText().toString();
            draft.kind=kind.getCheckedRadioButtonId()==R.id.unified_kind_todo?"todo":"thought";
            if(draft.editing&&markup!=null)try{draft.editingLayer=markup.annotationLayer();}catch(Exception ignored){}
        }
    }
    private void persist() {
        if(draft==null||!draft.recordId.isEmpty())return;
        collect();try{draft.persist();}catch(IOException|RuntimeException error){message("草稿暂未能写入磁盘，请保持此页面并重试保存。");}
    }
    private void loadImages() {
        int ticket=++imageGeneration;loadingImages=true;refreshModules();
        File screenshot=draft.screenshot,confirmed=draft.cropAnnotated;
        executor.execute(()->{
            Bitmap full=null,crop=null;
            try {if(screenshot!=null)full=CaptureStore.decodeEditorBitmap(screenshot);if(confirmed!=null)crop=CaptureStore.decodeReviewBitmap(confirmed);}
            catch(RuntimeException|OutOfMemoryError ignored){}
            Bitmap loaded=full,preview=crop;
            handler.post(()->{
                if(destroyed||isFinishing()||ticket!=imageGeneration){recycle(loaded);recycle(preview);return;}
                sourceBitmap=loaded;cropPreview=preview;loadingImages=false;
                contextImage.setImageBitmap(loaded);cropImage.setImageBitmap(preview);refreshModules();
                if(screenshot!=null&&loaded==null)message("截图暂时无法解码，可取消截图模块后保存文字，或退出重新截取。");
                if(draft.editing&&loaded!=null&&session.pending==null)showCanvas();
            });
        });
    }
    private void readClipboard() {
        confirmReplace(excerpt,"替换摘录？",()->{
            if(!foreground()){message("请返回记录页后再读取剪贴板。");return;}
            try {String value=QuickNoteClipboard.first(this);excerpt.setText(value);draft.clipboardRead=true;message("已读取剪贴板第一条，其他模块没有变化。");}
            catch(Exception error){message("无法读取剪贴板文字，请先复制文字后再试；已有摘录仍保留。");}
        });
    }
    private void confirmReplace(EditText field,String title,Runnable action) {
        if(session.pending!=null||acquiring)return;
        if(field.length()==0){action.run();return;}
        new AlertDialog.Builder(this).setTitle(title).setMessage("仅在读取成功后替换此模块。其他内容不会改变。")
                .setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->handler.postDelayed(action,120)).show();
    }
    private boolean foreground(){return !destroyed&&!isFinishing()&&resumed&&focused&&draft!=null&&draft.scope.equals(CaptureAccountSession.scope(this));}

    private void requestPage() {
        if(session.pending!=null||acquiring)return;
        if(!foreground()){message("请返回记录页后再读取页面。");return;}
        if(!CaptureAccessibilityService.isReady()){message("读取来源页需要启用 Mnote 无障碍服务；仍可手动填写原文。");return;}
        persist();acquiring=true;int ticket=++pageGeneration;setBusy(true);hideKeyboard();
        normalWindow=new WindowManager.LayoutParams();normalWindow.copyFrom(getWindow().getAttributes());
        normalStatusColor=getWindow().getStatusBarColor();normalNavigationColor=getWindow().getNavigationBarColor();
        WindowManager.LayoutParams bridge=new WindowManager.LayoutParams();bridge.copyFrom(normalWindow);
        bridge.width=1;bridge.height=1;bridge.gravity=Gravity.TOP|Gravity.START;
        bridge.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        bridge.flags&=~WindowManager.LayoutParams.FLAG_DIM_BEHIND;bridge.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        bridge.setTitle(QuickNoteActivity.CONTEXT_TITLE);root.setVisibility(View.GONE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);getWindow().setAttributes(bridge);
        settleCallback=()->{
            if(!validPage(ticket)){if(acquiring&&pageGeneration==ticket)finishPage("已取消读取，页面或账号发生变化。");return;}
            int window=bridgeId();
            pageTask=pageExecutor.submit(()->{
                QuickNotePageContext page;
                try{page=CaptureAccessibilityService.readPageOnce(window,true);}catch(RuntimeException|OutOfMemoryError error){page=QuickNotePageContext.failure("当前页面无法读取，已有原文仍保留。");}
                QuickNotePageContext result=page;handler.post(()->acceptPage(ticket,result));
            });
        };
        timeoutCallback=()->{if(acquiring&&pageGeneration==ticket)finishPage("读取超时，已有内容仍保留；可以手动填写原文。");};
        handler.postDelayed(settleCallback,500);handler.postDelayed(timeoutCallback,10000);
    }
    private int bridgeId() {
        android.view.accessibility.AccessibilityNodeInfo node=null;
        try{node=getWindow().getDecorView().createAccessibilityNodeInfo();return node==null?-1:node.getWindowId();}
        catch(RuntimeException error){return -1;}finally{if(node!=null)node.recycle();}
    }
    private boolean validPage(int ticket){return acquiring&&pageGeneration==ticket&&foreground();}
    private void acceptPage(int ticket,QuickNotePageContext page) {
        if(destroyed||!acquiring||pageGeneration!=ticket)return;
        if(!validPage(ticket)){finishPage("已取消读取，页面或账号发生变化。");return;}
        if(!page.found()){finishPage(page.error);return;}
        if(!draft.source.appPackage.isEmpty()&&!draft.source.appPackage.equals(page.source.appPackage)
                ||!draft.source.url.isEmpty()&&!page.source.url.isEmpty()&&!draft.source.url.equals(page.source.url)) {
            finishPage("当前页面已不是最初来源，未替换原文。你可以手动粘贴所需内容。");return;
        }
        original.setText(page.text);draft.pageRead=true;draft.source=page.source;
        if(url.length()==0)url.setText(page.source.url);
        finishPage("已读取可访问的页面文字，请检查是否完整；截图和摘录均保持不变。");persist();
    }
    private void finishPage(String text) {
        acquiring=false;pageGeneration++;cancelPageWork();
        if(normalWindow!=null){normalWindow.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            getWindow().setAttributes(normalWindow);normalWindow=null;getWindow().setStatusBarColor(normalStatusColor);getWindow().setNavigationBarColor(normalNavigationColor);}
        if(root!=null){root.setVisibility(View.VISIBLE);setBusy(session.pending!=null);refreshModules();}
        if(text!=null)message(text);
    }
    private void cancelPageWork() {
        if(settleCallback!=null)handler.removeCallbacks(settleCallback);if(timeoutCallback!=null)handler.removeCallbacks(timeoutCallback);
        settleCallback=null;timeoutCallback=null;if(pageTask!=null)pageTask.cancel(true);pageTask=null;
    }

    private void openCanvas() {
        if(sourceBitmap==null||loadingImages||acquiring||session.pending!=null)return;
        collect();draft.editingLayer=draft.layer;draft.editing=true;hideKeyboard();showCanvas();persist();
    }
    private void showCanvas() {
        if(sourceBitmap==null)return;
        canvas.removeAllViews();compose.setVisibility(View.GONE);canvas.setVisibility(View.VISIBLE);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);canvas.addView(actions);
        Button cancel=button(actions,"取消",R.id.unified_crop_cancel,false,this::cancelCanvas);cancel.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button done=button(actions,"确认圈选",R.id.unified_crop_confirm,true,this::confirmCanvas);done.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        TextView help=JournalUi.text(this,"在最初截图上圈选或批注 · 完整页面始终不变",12,R.color.ink_muted);
        help.setPadding(dp(16),dp(8),dp(16),dp(8));canvas.addView(help);
        markup=new CaptureMarkupView(this);markup.setId(R.id.unified_markup);markup.setSourceBitmap(sourceBitmap);
        if(draft.editingLayer!=null)try{markup.restoreAnnotationLayer(draft.editingLayer);}catch(Exception error){message("暂时无法恢复画布，已确认的结果仍保留；取消可返回原结果。");}
        canvas.addView(markup,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView tools=new HorizontalScrollView(this);tools.setHorizontalScrollBarEnabled(false);canvas.addView(tools,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout bar=new LinearLayout(this);bar.setPadding(dp(8),dp(8),dp(8),dp(8));tools.addView(bar);
        tool(bar,"圈选",R.id.unified_tool_select,()->markup.setTool(CaptureMarkupView.Tool.SELECT));
        tool(bar,"画笔",R.id.unified_tool_pen,()->markup.setTool(CaptureMarkupView.Tool.PEN));
        tool(bar,"荧光笔",R.id.unified_tool_highlighter,()->markup.setTool(CaptureMarkupView.Tool.HIGHLIGHTER));
        tool(bar,"撤销",R.id.unified_tool_undo,()->markup.undo());
        tool(bar,"整张",R.id.unified_tool_whole,()->markup.selectWholeImage());
        markup.setChangeListener(this::refreshTools);refreshTools();
    }
    private Button tool(LinearLayout parent,String text,int id,Runnable action) {
        Button button=button(parent,text,id,false,()->{action.run();refreshTools();});button.setBackgroundResource(R.drawable.bg_unified_tool);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));return button;
    }
    private void refreshTools() {
        if(markup==null)return;
        int selected=markup.getTool()==CaptureMarkupView.Tool.SELECT?R.id.unified_tool_select
                :markup.getTool()==CaptureMarkupView.Tool.PEN?R.id.unified_tool_pen:R.id.unified_tool_highlighter;
        for(int id:new int[]{R.id.unified_tool_select,R.id.unified_tool_pen,R.id.unified_tool_highlighter}) {
            Button action=canvas.findViewById(id);if(action==null)continue;
            action.setSelected(id==selected);action.setContentDescription(action.getText()+(id==selected?"，已选择":""));
        }
        View undo=canvas.findViewById(R.id.unified_tool_undo);if(undo!=null)undo.setEnabled(session.pending==null&&markup.canUndo());
    }
    private void cancelCanvas() {
        if(session.pending!=null)return;
        draft.editing=false;draft.editingLayer=null;canvas.setVisibility(View.GONE);compose.setVisibility(View.VISIBLE);markup=null;persist();
    }
    private void confirmCanvas() {
        if(session.pending!=null||markup==null)return;
        Bitmap originalCrop=null,annotated=null;
        try {
            originalCrop=markup.renderOriginalSelection();annotated=markup.renderAnnotatedSelection();JSONObject layer=markup.annotationLayer();
            if(originalCrop==null||annotated==null)throw new IOException("Missing crop");
            Bitmap first=originalCrop,second=annotated;Context app=getApplicationContext();UnifiedCaptureDraft target=draft;
            PendingWork work=new PendingWork(session,false,false,this);session.pending=work;setBusy(true);
            executor.execute(()->{
                try{synchronized(CaptureAccountSession.LOCK){CaptureAccountSession.requireScope(app,target.scope);target.confirmCrop(app,first,second,layer);}}
                catch(Exception|OutOfMemoryError error){work.error="圈选未能确认，之前的结果仍保留，请重试。";}
                finally{recycle(first);recycle(second);work.complete=true;handler.post(work::deliver);}
            });
        } catch(Exception|OutOfMemoryError error){recycle(originalCrop);recycle(annotated);message("无法准备圈选结果，请缩小区域后重试。");}
    }

    private void save(boolean chat) {
        if(session.pending!=null||acquiring||draft.editing)return;
        if(!draft.recordId.isEmpty()){finishSaved(chat);return;}
        if(!draft.scope.equals(CaptureAccountSession.scope(this))){message("账号已变化，请返回后重新发起记录。");return;}
        collect();
        String comment=draft.selected.contains("thought")?draft.thought.trim():"";
        String quote=draft.selected.contains("excerpt")?draft.excerpt:"";
        String page=draft.selected.contains("original")?draft.original:"";
        String link=draft.selected.contains("source")?CaptureSourceUrl.clean(draft.url.trim()):"";
        boolean context=draft.selected.contains("context"),crop=draft.selected.contains("crop"),source=draft.selected.contains("source");
        if(comment.length()>20000){invalid(thought,"想法不能超过 2 万字。");return;}
        if(quote.length()>100000){invalid(excerpt,"摘录不能超过 10 万字。");return;}
        if(page.length()>CaptureContext.MAX_TEXT){invalid(original,"页面原文不能超过 4 万字。");return;}
        if(source&&!draft.url.trim().isEmpty()&&link.isEmpty()){invalid(url,"请输入有效的来源链接。");return;}
        JSONArray savedTags;
        try{savedTags=draft.selected.contains("tags")?CaptureTags.parse(draft.tags):new JSONArray();}
        catch(IllegalArgumentException error){invalid(tags.input,error.getMessage());return;}
        if(context&&(draft.screenshot==null||!draft.screenshot.isFile())){message("完整截图不可用，请取消勾选此模块后保存文字。");return;}
        if(crop&&(draft.cropOriginal==null||draft.cropAnnotated==null||draft.layer==null)){message("请先确认圈选，或取消勾选圈选与批注。");return;}
        if(comment.isEmpty()&&quote.trim().isEmpty()&&page.trim().isEmpty()&&link.isEmpty()&&!context&&!crop){message("请至少保留一项内容，再保存记录。");return;}
        try {
            JSONObject textContext=null;
            if(!page.trim().isEmpty())textContext=CaptureContext.text(page,draft.pageRead?"accessibility_page":"user_entered",quote)
                    .put("extent",draft.pageRead?"visible_accessibility_text":"provided_text").put("relation_to_quote","unverified")
                    .put("source_package",source?draft.source.appPackage:"").put("source_url",source?link:"");
            JSONObject layer=crop?new JSONObject(draft.layer.toString()):null;
            if(context&&!crop) {
                if(sourceBitmap==null){message("截图仍在准备或无法解码，请稍候，或取消完整截图模块。");return;}
                layer=new JSONObject().put("sourceWidth",sourceBitmap.getWidth()).put("sourceHeight",sourceBitmap.getHeight())
                        .put("coordinateSpace","source_bitmap_pixels").put("purpose","page_context").put("relation_to_quote","unverified")
                        .put("selection",new JSONObject().put("left",0).put("top",0).put("right",sourceBitmap.getWidth()).put("bottom",sourceBitmap.getHeight()))
                        .put("strokes",new JSONArray());
            }
            final JSONObject material=textContext,annotation=layer;
            String type=!quote.isEmpty()&&draft.clipboardRead?"clipboard":context||crop?"screen":"quick_note";
            String pkg=source?draft.source.appPackage:"",origin=link.isEmpty()?"":link.equals(draft.source.url)?draft.source.origin:"user_entered";
            String category=draft.kind,scope=draft.scope;File screenshot=context?draft.screenshot:null,first=draft.cropOriginal,second=draft.cropAnnotated;
            UnifiedCaptureDraft target=draft;Context app=getApplicationContext();persist();
            PendingWork work=new PendingWork(session,true,chat,this);session.pending=work;setBusy(true);message("正在保存…",false);
            executor.execute(()->{
                Bitmap originalCrop=null,annotatedCrop=null;
                try {
                    if(crop){originalCrop=CaptureStore.decodeEditorBitmap(first);annotatedCrop=CaptureStore.decodeEditorBitmap(second);
                        if(originalCrop==null||annotatedCrop==null)throw new IOException("Missing crop");}
                    CaptureStore.CaptureRecord record;
                    synchronized(CaptureAccountSession.LOCK) {
                        CaptureAccountSession.requireScope(app,scope);
                        record=CaptureStore.save(app,screenshot,originalCrop,annotatedCrop,annotation,category,comment,type,quote,pkg,link,origin,context,material,savedTags);
                        synchronized(target){target.recordId=record.id;try{target.persist();}catch(IOException ignored){/* The record already committed; never offer a duplicate retry. */}}
                    }
                    if(CaptureStore.SYNC_PENDING.equals(record.syncState))try{CaptureSyncWorker.enqueue(app);}catch(RuntimeException ignored){}
                } catch(Exception|OutOfMemoryError error){if(target.recordId.isEmpty())work.error="保存失败，内容仍保留在编辑器中。请检查账号和存储后重试。";}
                finally{recycle(originalCrop);recycle(annotatedCrop);work.complete=true;handler.post(work::deliver);}
            });
        } catch(Exception error){message("内容暂时无法保存，请检查输入后重试。");}
    }
    private void invalid(EditText field,String error){field.setError(error);field.requestFocus();field.post(()->CaptureReadingLayout.revealCursor(field));message(error);}
    private void setBusy(boolean busy){if(root!=null)setEnabled(root,!busy);if(!busy&&editImage!=null){refreshModules();refreshTools();}}
    private static void setEnabled(View view,boolean value){view.setEnabled(value);if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++)setEnabled(parent.getChildAt(i),value);}}
    private void hideKeyboard(){InputMethodManager keyboard=getSystemService(InputMethodManager.class);if(keyboard!=null&&root!=null)keyboard.hideSoftInputFromWindow(root.getWindowToken(),0);}
    private void message(String text){message(text,true);}
    private void message(String text,boolean toast){if(status!=null){status.setText(text);status.setVisibility(View.VISIBLE);}if(toast&&!destroyed)Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    private void finishSaved(boolean chat) {
        if(destroyed||isFinishing())return;
        message("记录已保存");
        if(chat&&draft.scope.equals(CaptureAccountSession.scope(this)))AiUi.open(this,draft.scope,draft.recordId);
        setResult(RESULT_OK);draft.discard(this);finish();
    }
    @Override public void onBackPressed() {
        if(session==null){finish();return;}
        if(session.pending!=null)return;
        if(acquiring){finishPage("已取消页面读取，已有内容仍保留。");return;}
        if(draft.editing){cancelCanvas();return;}
        new AlertDialog.Builder(this).setTitle("放弃这条记录？").setMessage("尚未保存的文字、截图和圈选草稿将被移除。")
                .setNegativeButton("继续编辑",null).setPositiveButton("放弃",(d,w)->finish()).show();
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(draft!=null&&!draft.scope.equals(CaptureAccountSession.scope(this))){message("账号已变化，已关闭当前记录页。");finish();}}
    @Override protected void onPause(){resumed=false;if(acquiring)finishPage("已取消读取，记录页离开了前台。");if(session!=null&&session.pending==null)persist();super.onPause();}
    @Override public void onWindowFocusChanged(boolean value){super.onWindowFocusChanged(value);focused=value;if(!value&&acquiring)finishPage("窗口发生变化，已取消读取；已有原文仍保留。");}
    @Override public Object onRetainNonConfigurationInstance(){return session;}
    @Override protected void onSaveInstanceState(Bundle state){if(draft!=null){if(session.pending==null)persist();state.putString("unified_draft",draft.stateFile.getAbsolutePath());}super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){
        destroyed=true;imageGeneration++;pageGeneration++;cancelPageWork();pageExecutor.shutdownNow();
        if(active.get()==this)active=new WeakReference<>(null);
        if(session!=null&&session.pending!=null)session.pending.receiver=new WeakReference<>(null);
        if(isFinishing()&&draft!=null&&session.pending==null)draft.discard(this);
        // Bitmaps bound to a View can remain in a render-thread display list. Never recycle them.
        sourceBitmap=null;cropPreview=null;executor.shutdown();super.onDestroy();
    }
    private static final class Module {
        final LinearLayout card,body;final CheckBox toggle;
        Module(LinearLayout card,LinearLayout body,CheckBox toggle){this.card=card;this.body=body;this.toggle=toggle;}
    }
    private static final class Session {
        final UnifiedCaptureDraft draft;PendingWork pending;
        Session(UnifiedCaptureDraft draft){this.draft=draft;}
    }
    private static final class PendingWork {
        final Session session;final boolean save,chat;volatile boolean complete;String error;
        WeakReference<UnifiedCaptureActivity> receiver;
        PendingWork(Session session,boolean save,boolean chat,UnifiedCaptureActivity activity){this.session=session;this.save=save;this.chat=chat;receiver=new WeakReference<>(activity);}
        void deliver(){
            UnifiedCaptureActivity activity=receiver.get();if(!complete||activity==null||activity.destroyed||activity.isFinishing()||session.pending!=this)return;
            session.pending=null;activity.setBusy(false);
            if(error!=null){activity.message(error);return;}
            if(save){activity.finishSaved(chat);return;}
            activity.draft.editing=false;activity.canvas.setVisibility(View.GONE);activity.compose.setVisibility(View.VISIBLE);activity.markup=null;
            activity.refreshModules();activity.loadImages();activity.message("圈选与批注已确认，完整页面截图保持不变。");
        }
    }
}
