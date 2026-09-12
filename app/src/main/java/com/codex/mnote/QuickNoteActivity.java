package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, opt-in clipboard excerpt. No automatic clipboard or external page reads. */
public final class QuickNoteActivity extends Activity {
    static final String CONTEXT_TITLE = "Mnote explicit quick-note context bridge";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Accessibility Binder queries and image decoding must not stall window/input dispatch.
    private final ExecutorService pageExecutor = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> pageTask;
    private Runnable settleCallback, timeoutCallback;
    private EditText comment, quote, original;
    private CompoundButton clipboard;
    private TextView status, contextLabel;
    private ImageView image;
    private View root, material;
    private SourceLinkField link;
    private CaptureTags.Field tags;
    private File draft;
    private File pendingDraft;
    private Bitmap preview;
    private CaptureSourceContext source = CaptureSourceContext.EMPTY;
    private String ownerScope, contextMode = "none";
    private boolean resumed, focused, acquiring, restoring, destroyed;
    private int generation;
    private WindowManager.LayoutParams normalWindow;
    private int normalStatusColor, normalNavigationColor;
    private SaveTask saveTask;
    private Toast feedback;
    private Button expandOriginal;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ownerScope = state == null ? CaptureAccountSession.scope(this) : state.getString("owner");
        setContentView(R.layout.quick_note);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        root = findViewById(R.id.quick_note_root);
        android.util.TypedValue headerBackground = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, headerBackground, true);
        findViewById(R.id.capture_editor_cancel).setBackgroundResource(headerBackground.resourceId);
        comment = findViewById(R.id.capture_comment_input);
        quote = findViewById(R.id.quick_note_quote);
        original = findViewById(R.id.quick_note_original);
        tags = new CaptureTags.Field(root);
        // Bound on-screen layout without truncating the underlying text.
        comment.setMaxLines(12); quote.setMaxLines(8); original.setMaxLines(8);
        CaptureLongText.attach(this, comment, "我的想法");
        CaptureLongText.attach(this, quote, "剪贴板摘录");
        expandOriginal = CaptureLongText.attach(this, original, "页面原文");
        for (EditText field : new EditText[]{comment, quote, original}) {
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        }
        clipboard = findViewById(R.id.quick_note_clipboard);
        status = findViewById(R.id.quick_note_status);
        contextLabel = findViewById(R.id.quick_note_context_label);
        image = findViewById(R.id.quick_note_image);
        material = findViewById(R.id.quick_note_material);
        link = new SourceLinkField(root);
        link.input.setSaveEnabled(false);
        findViewById(R.id.capture_editor_save).setOnClickListener(v -> save());
        findViewById(R.id.capture_editor_cancel).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.quick_note_read_page).setOnClickListener(v -> requestContext(true));
        findViewById(R.id.quick_note_capture_page).setOnClickListener(v -> requestContext(false));
        findViewById(R.id.quick_note_clear_context).setOnClickListener(v -> clearContext());
        if (state != null) {
            tags.input.setText(state.getString("tags", ""));
            comment.setText(state.getString("comment", ""));
            quote.setText(state.getString("quote", ""));
            original.setText(state.getString("original", ""));
            clipboard.setChecked(state.getBoolean("clipboard"));
            ((RadioGroup)findViewById(R.id.capture_kind_group)).check(state.getInt("kind", R.id.capture_kind_thought));
            link.input.setText(state.getString("url", ""));
            contextMode = state.getString("mode", "none");
            source = new CaptureSourceContext(state.getString("package"), state.getString("page_url"), state.getString("page_origin"));
            draft = CaptureStore.safeDraftFile(this, state.getString("draft"));
            try { if (draft != null) preview = CaptureStore.decodeReviewBitmap(draft); }
            catch (RuntimeException | OutOfMemoryError ignored) { }
            if (contextMode.equals("image") && (draft == null || preview == null)) clearContext();
        }
        clipboard.setOnCheckedChangeListener((button, checked) -> {
            if (restoring) return;
            if (checked) {
                try {
                    if (!resumed || !focused) throw new Exception("请在随手记处于前台时开启摘录。");
                    quote.setText(QuickNoteClipboard.first(this));
                    status.setText("已读取剪贴板第一条。页面上下文独立保留，不受此开关影响。");
                } catch (Exception error) {
                    restoring = true; clipboard.setChecked(false); restoring = false;
                    message(error instanceof SecurityException ? "系统暂不允许读取剪贴板，请先复制文字再试。" : error.getMessage());
                }
            } else { quote.setText(""); }
            refreshContext();
        });
        refreshContext();
        comment.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        saveTask = (SaveTask)getLastNonConfigurationInstance();
        if (saveTask != null) { saveTask.receiver = this; busy(true); handler.post(saveTask::deliver); }
    }

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() {
        resumed = false;
        if (acquiring) abortContext("已取消页面读取：随手记离开了前台。");
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus;
        if (!hasFocus && acquiring) abortContext("已取消页面读取：当前窗口发生变化。");
    }

    private void requestContext(boolean text) {
        if (acquiring || saveTask != null) return;
        if (!resumed || !focused) { message("请返回随手记后再试。"); return; }
        if (!CaptureAccessibilityService.isReady()) {
            message("页面上下文需要启用 Mnote 无障碍服务。随手记和剪贴板摘录不受影响。"); return;
        }
        if (!text && android.os.Build.VERSION.SDK_INT < 30) {
            message("页面截图需要 Android 11 或更新版本。"); return;
        }
        clearContext();
        if (feedback != null) feedback.cancel();
        acquiring = true; int request = ++generation;
        busy(true);
        InputMethodManager ime = getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(root.getWindowToken(), 0);
        if (!valid(request)) return;
        normalWindow = new WindowManager.LayoutParams(); normalWindow.copyFrom(getWindow().getAttributes());
        normalStatusColor = getWindow().getStatusBarColor(); normalNavigationColor = getWindow().getNavigationBarColor();
        WindowManager.LayoutParams bridge = new WindowManager.LayoutParams(); bridge.copyFrom(normalWindow);
        bridge.width = 1; bridge.height = 1; bridge.gravity = Gravity.TOP | Gravity.START;
        bridge.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        bridge.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        bridge.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        bridge.setTitle(CONTEXT_TITLE);
        // INVISIBLE still measures long EditTexts at a one-pixel width. GONE does not.
        root.setVisibility(View.GONE);
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setAttributes(bridge);
        settleCallback = () -> acquire(request, text);
        timeoutCallback = () -> { if (!destroyed && acquiring && generation == request) abortContext("页面读取超时，请重试；未保存上下文。"); };
        handler.postDelayed(settleCallback, 500);
        handler.postDelayed(timeoutCallback, 10_000);
    }

    private int bridgeId() {
        android.view.accessibility.AccessibilityNodeInfo node = null;
        try {
            node = getWindow().getDecorView().createAccessibilityNodeInfo();
            return node == null ? -1 : node.getWindowId();
        }
        catch (RuntimeException ignored) { return -1; }
        finally { if (node != null) node.recycle(); }
    }
    private boolean valid(int request) {
        return !destroyed && !isFinishing() && resumed && focused && acquiring && generation == request
                && ownerScope.equals(CaptureAccountSession.scope(this));
    }
    private void acquire(int request, boolean text) {
        if (!valid(request)) {
            if (!destroyed && acquiring && generation == request) abortContext("页面读取已取消，账号或窗口发生变化。");
            return;
        }
        final int ownWindow = bridgeId();
        pageTask = pageExecutor.submit(() -> {
            QuickNotePageContext page = readPageSafely(ownWindow, text);
            handler.post(() -> acceptPage(request, text, page));
        });
    }
    private static QuickNotePageContext readPageSafely(int window, boolean text) {
        try { return CaptureAccessibilityService.readPageOnce(window, text); }
        catch (RuntimeException | OutOfMemoryError error) { return QuickNotePageContext.failure("页面读取失败，未附加上下文，请重试。"); }
    }
    private void acceptPage(int request, boolean text, QuickNotePageContext page) {
        if (destroyed || !acquiring || generation != request) return;
        if (!valid(request)) { abortContext("页面读取已取消，账号或窗口发生变化。"); return; }
        if (!page.found()) { abortContext(page.error); return; }
        if (text) {
            source = page.source; contextMode = "text";
            // Populate while GONE. Restore normal window bounds before any article layout.
            original.setText(page.text); finishContext();
            message("已附加可访问的页面文字，请检查是否包含所需原文。");
            return;
        }
        try { CaptureAccessibilityService.captureOnce(new CaptureAccessibilityService.CaptureCallback() {
            @Override public void onCaptured(File captured) {
                handler.post(() -> {
                    if (!valid(request)) {
                        CaptureStore.discardDraft(QuickNoteActivity.this, captured);
                        if (!destroyed && acquiring && generation == request) abortContext("页面读取已取消，账号或窗口发生变化。");
                        return;
                    }
                    final int ownWindow = bridgeId();
                    pendingDraft = captured;
                    pageTask = pageExecutor.submit(() -> {
                        QuickNotePageContext after = readPageSafely(ownWindow, false);
                        Bitmap decoded = null;
                        try { if (page.samePageWindow(after)) decoded = CaptureStore.decodeReviewBitmap(captured); }
                        catch (RuntimeException | OutOfMemoryError ignored) { }
                        final Bitmap result = decoded;
                        handler.post(() -> acceptScreenshot(request, page, after, captured, result));
                    });
                });
            }
            @Override public void onFailure(CaptureAccessibilityService.Failure failure) {
                handler.post(() -> { if (valid(request)) abortContext("页面截图失败（" + failure.name() + "），未保存上下文。"); });
            }
        }); } catch (RuntimeException error) { abortContext("无法启动页面截图，请重试。"); }
    }
    private void acceptScreenshot(int request, QuickNotePageContext before, QuickNotePageContext after, File captured, Bitmap result) {
        if (captured.equals(pendingDraft)) pendingDraft = null;
        if (!valid(request) || !before.samePageWindow(after) || result == null) {
            if (result != null) result.recycle();
            CaptureStore.discardDraft(this, captured);
            if (!destroyed && acquiring && generation == request) abortContext("截图未能安全完成或来源已变化，未保留截图，请重试。");
            return;
        }
        draft = captured; preview = result; source = before.source; contextMode = "image";
        finishContext(); message("已附加完整页面截图，请检查预览后保存。");
    }
    private void abortContext(String message) { finishContext(); message(message); }
    private void finishContext() {
        acquiring = false; generation++;
        cancelPageWork();
        if (normalWindow != null) {
            normalWindow.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            getWindow().setAttributes(normalWindow); normalWindow = null;
            getWindow().setStatusBarColor(normalStatusColor); getWindow().setNavigationBarColor(normalNavigationColor);
        }
        root.setVisibility(View.VISIBLE); busy(false); refreshContext();
    }
    private void cancelPageWork() {
        if (settleCallback != null) handler.removeCallbacks(settleCallback);
        if (timeoutCallback != null) handler.removeCallbacks(timeoutCallback);
        settleCallback = null; timeoutCallback = null;
        if (pageTask != null) pageTask.cancel(true);
        pageTask = null;
        CaptureStore.discardDraft(this, pendingDraft); pendingDraft = null;
    }
    private void clearContext() {
        CaptureStore.discardDraft(this, draft); draft = null;
        image.setImageDrawable(null);
        // A previous hardware display list may still reference this drawable for a frame.
        // Drop the UI reference; do not recycle a bitmap that has been bound to a View.
        preview = null;
        original.setText(""); source = CaptureSourceContext.EMPTY; contextMode = "none";
        refreshContext();
    }
    private void refreshContext() {
        material.setVisibility(clipboard.isChecked() ? View.VISIBLE : View.GONE);
        boolean text = contextMode.equals("text"), screenshot = contextMode.equals("image");
        original.setVisibility(text ? View.VISIBLE : View.GONE);
        expandOriginal.setVisibility(text ? View.VISIBLE : View.GONE);
        image.setVisibility(screenshot ? View.VISIBLE : View.GONE); image.setImageBitmap(preview);
        findViewById(R.id.quick_note_clear_context).setVisibility(text || screenshot ? View.VISIBLE : View.GONE);
        contextLabel.setText(text ? "页面文字 · " + source.appLabel(this) + "\n仅可访问内容，可能包含界面文字，不保证文章全文。"
                : screenshot ? "完整页面截图 · " + source.appLabel(this) + "\n作为本条记录的页面背景独立保存。" : "未附加上下文");
    }
    private void busy(boolean value) { setEnabled(root, !value); }
    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }
    private void message(String value) {
        String text = value == null ? "操作失败，请重试。" : value;
        status.setText(text);
        if (feedback != null) feedback.cancel();
        feedback = Toast.makeText(this, text, Toast.LENGTH_LONG); feedback.show();
    }

    private void save() {
        if (saveTask != null || acquiring) return;
        String thought = comment.getText().toString().trim();
        org.json.JSONArray savedTags = tags.validated(); if (savedTags == null) return;
        String excerpt = clipboard.isChecked() ? quote.getText().toString() : "";
        String url = link.validated(); if (url == null) return;
        if (clipboard.isChecked() && excerpt.trim().isEmpty()) { quote.setError("请输入摘录，或关闭剪贴板摘录。"); return; }
        if (excerpt.length() > 100_000) { quote.setError("摘录不能超过 10 万字。"); return; }
        if (thought.isEmpty() && excerpt.isEmpty() && url.isEmpty() && contextMode.equals("none")) { comment.setError(getString(R.string.capture_comment_required)); return; }
        if (thought.length() > 20_000) { comment.setError("想法不能超过 2 万字。"); return; }
        JSONObject textContext = null;
        try {
            if (contextMode.equals("text")) {
                String page = original.getText().toString();
                if (page.trim().isEmpty()) { original.setError("请输入页面原文，或移除上下文。"); return; }
                if (page.length() > CaptureContext.MAX_TEXT) { original.setError("页面原文不能超过 4 万字。"); return; }
                textContext = CaptureContext.text(page, "accessibility_page", excerpt)
                        .put("extent", "visible_accessibility_text").put("relation_to_quote", "unverified")
                        .put("source_package", source.appPackage).put("source_url", source.url);
            }
        } catch (Exception error) { message("页面上下文无法保存，请检查内容。"); return; }
        String kind = ((RadioGroup)findViewById(R.id.capture_kind_group)).getCheckedRadioButtonId() == R.id.capture_kind_todo ? "todo" : "thought";
        // Clipboard provenance and current-page context are intentionally distinct.
        String type = clipboard.isChecked() ? "clipboard" : "quick_note";
        String urlOrigin = link.origin(url);
        if (url.isEmpty() && !source.url.isEmpty()) { url = source.url; urlOrigin = source.origin; }
        saveTask = new SaveTask(this);
        SaveTask task = saveTask;
        Context app = getApplicationContext(); File snapshot = draft;
        String scope = ownerScope, pkg = source.appPackage, savedUrl = url, savedOrigin = urlOrigin;
        JSONObject savedText = textContext;
        busy(true); status.setText("正在保存…");
        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                JSONObject layer = null;
                if (snapshot != null) {
                    bitmap = CaptureStore.decodeEditorBitmap(snapshot);
                    if (bitmap == null) throw new java.io.IOException("Missing screenshot");
                    layer = new JSONObject().put("sourceWidth", bitmap.getWidth()).put("sourceHeight", bitmap.getHeight())
                            .put("selection", new JSONObject().put("left", 0).put("top", 0).put("right", bitmap.getWidth()).put("bottom", bitmap.getHeight()))
                            .put("strokes", new org.json.JSONArray()).put("purpose", "page_context")
                            .put("relation_to_quote", "unverified");
                }
                CaptureStore.CaptureRecord record;
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(app, scope);
                    record = CaptureStore.save(app, snapshot, bitmap, bitmap, layer, kind, thought, type, excerpt,
                            pkg, savedUrl, savedOrigin, snapshot != null, savedText, savedTags);
                }
                if (CaptureStore.SYNC_PENDING.equals(record.syncState)) {
                    try { CaptureSyncWorker.enqueue(app); }
                    catch (RuntimeException ignored) { /* Durable pending record retries on the next sync. */ }
                }
            } catch (Exception | OutOfMemoryError error) { task.error = "保存失败，内容仍保留在编辑器中，请重试。"; }
            finally {
                if (bitmap != null) bitmap.recycle();
                task.complete = true;
                handler.post(() -> {
                    if (task.receiver == null && task.error != null) CaptureStore.discardDraft(app, snapshot);
                    task.deliver();
                });
            }
        });
    }
    private static final class SaveTask {
        QuickNoteActivity receiver;
        volatile boolean complete;
        String error;
        SaveTask(QuickNoteActivity receiver) { this.receiver = receiver; }
        void deliver() {
            if (!complete || receiver == null || receiver.destroyed || receiver.isFinishing()) return;
            QuickNoteActivity activity = receiver;
            if (error == null) {
                activity.draft = null; activity.message(activity.getString(R.string.capture_saved));
                activity.setResult(RESULT_OK); activity.finish();
            } else { activity.saveTask = null; activity.busy(false); activity.message(error); }
        }
    }
    @Override public Object onRetainNonConfigurationInstance() { return saveTask; }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("owner", ownerScope); out.putString("comment", comment.getText().toString());
        out.putString("tags", tags.input.getText().toString());
        out.putString("quote", quote.getText().toString()); out.putString("original", original.getText().toString());
        out.putBoolean("clipboard", clipboard.isChecked()); out.putString("url", link.input.getText().toString());
        out.putInt("kind", ((RadioGroup)findViewById(R.id.capture_kind_group)).getCheckedRadioButtonId());
        out.putString("mode", contextMode); out.putString("package", source.appPackage);
        out.putString("page_url", source.url); out.putString("page_origin", source.origin);
        if (draft != null) out.putString("draft", draft.getAbsolutePath());
    }
    @Override public void onBackPressed() {
        if (saveTask != null) return;
        if (acquiring) { abortContext("已取消页面读取。"); return; }
        if (comment.getText().toString().trim().isEmpty() && quote.getText().toString().trim().isEmpty()
                && tags.input.getText().toString().trim().isEmpty() && !link.hasInput() && contextMode.equals("none")) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("放弃这条记录？").setMessage("尚未保存的内容将被丢弃。")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃", (dialog, which) -> finish()).show();
    }
    @Override protected void onDestroy() {
        destroyed = true; generation++;
        cancelPageWork(); pageExecutor.shutdownNow();
        if (saveTask != null) saveTask.receiver = null;
        if (!isChangingConfigurations() && saveTask == null) CaptureStore.discardDraft(this, draft);
        image.setImageDrawable(null); preview = null;
        executor.shutdown(); super.onDestroy();
    }
}
