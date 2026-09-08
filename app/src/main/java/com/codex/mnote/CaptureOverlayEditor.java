package com.codex.mnote;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A service-owned editing window. No Activity is started to edit the screenshot. */
final class CaptureOverlayEditor {
    private final Context context;
    private final String ownerScope;
    private final WindowManager windows;
    private final File draft;
    private final Runnable onClosed;
    private final CaptureSourceContext source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "capture-overlay");
        thread.setDaemon(true);
        return thread;
    });
    private final FrameLayout root;
    private final CaptureMarkupView markup;
    private final EditText comment;
    private final RadioGroup kind;
    private final TextView status;
    private final Button save;
    private final SourceLinkField sourceLink;
    private final WindowManager.LayoutParams params;
    private ScrollView composerScroll;
    private LinearLayout column;
    private ImageView preview;
    private Bitmap previewBitmap;
    private int imeInset;
    private int visibleBottom;
    private boolean composing;
    private boolean toolsAtTop;
    private int topInset;
    private int bottomInset;
    private Bitmap sourceBitmap;
    private AlertDialog dialog;
    private boolean attached;
    private boolean loading = true;
    private boolean saving;
    private boolean closed;
    private boolean ownsDraft;
    private Runnable unregisterBack;

    CaptureOverlayEditor(Context service, File draft, Runnable onClosed) {
        this(service, draft, CaptureSourceContext.EMPTY, onClosed);
    }

    CaptureOverlayEditor(Context service, File draft, CaptureSourceContext source, Runnable onClosed) {
        this.context = new ContextThemeWrapper(service, R.style.Theme_Mnote);
        this.ownerScope = CaptureAccountSession.scope(context);
        this.windows = service.getSystemService(WindowManager.class);
        this.draft = draft;
        this.onClosed = onClosed;
        this.source = source;
        root = new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = View.MeasureSpec.getSize(heightSpec);
                int bottom = composing ? usableBottom(height) : height - bottomInset;
                if (column != null) {
                    FrameLayout.LayoutParams dock = (FrameLayout.LayoutParams) column.getLayoutParams();
                    dock.gravity = composing || toolsAtTop ? Gravity.TOP : Gravity.BOTTOM;
                    dock.topMargin = topInset + dp(8);
                    dock.bottomMargin = bottomInset + dp(8);
                    dock.height = FrameLayout.LayoutParams.WRAP_CONTENT;
                }
                if (composerScroll != null) {
                    composerScroll.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
                }
                if (preview != null && composing) {
                    preview.getLayoutParams().height = Math.max(dp(48), Math.min(dp(180),
                            Math.round((bottom - topInset) * .23f)));
                }
                super.onMeasure(widthSpec, heightSpec);
                if (composerScroll == null || column == null) return;
                int available = bottom - topInset - dp(16) - column.getPaddingTop() - column.getPaddingBottom();
                for (int index = 0; index < column.getChildCount(); index++) {
                    View child = column.getChildAt(index);
                    if (child == composerScroll || child.getVisibility() == View.GONE) continue;
                    LinearLayout.LayoutParams childParams = (LinearLayout.LayoutParams) child.getLayoutParams();
                    available -= child.getMeasuredHeight() + childParams.topMargin + childParams.bottomMargin;
                }
                // Cap the scrollable form to the dock budget, leaving the
                // frozen screenshot visible above it on a normal-size screen.
                if (composing && composerScroll.getMeasuredHeight() > Math.max(0, available)) {
                    composerScroll.getLayoutParams().height = Math.max(0, available);
                    super.onMeasure(widthSpec, heightSpec);
                }
            }

            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(0xFF101114);
        View editor = LayoutInflater.from(context).inflate(R.layout.activity_capture_editor, root, false);
        editor.setBackgroundResource(R.drawable.bg_overlay_panel);
        editor.setClipToOutline(true);
        root.addView(editor);
        // Constrain the lower sheet on small/landscape displays and with an
        // IME open. Its fields can scroll instead of falling below the window.
        LinearLayout composer = root.findViewById(R.id.capture_composer);
        column = (LinearLayout) composer.getParent();
        int composerIndex = column.indexOfChild(composer);
        column.removeView(composer);
        composerScroll = new ScrollView(context);
        composerScroll.setFillViewport(false);
        composerScroll.addView(composer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        column.addView(composerScroll, composerIndex, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // The frozen screenshot is the full-screen canvas, not a thumbnail in
        // a form. Only the compact dock floats over it; the form is step two.
        View evidence = root.findViewById(R.id.capture_evidence_container);
        column.removeView(evidence);
        root.addView(evidence, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        column.setFitsSystemWindows(false);
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        dockParams.setMargins(dp(12), dp(12), dp(12), dp(24));
        column.setLayoutParams(dockParams);
        column.setElevation(dp(12));
        preview = new ImageView(context);
        preview.setId(R.id.capture_selection_preview);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(context.getString(R.string.capture_selection_preview));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        previewParams.setMargins(dp(16), 0, dp(16), dp(8));
        column.addView(preview, 1, previewParams);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                topInset = bars.top;
                bottomInset = bars.bottom;
                imeInset = insets.isVisible(android.view.WindowInsets.Type.ime())
                        ? insets.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0;
            } else {
                topInset = insets.getStableInsetTop();
                bottomInset = insets.getStableInsetBottom();
            }
            positionDock();
            keepFocusedInputVisible();
            return insets;
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visible = new Rect();
            root.getWindowVisibleDisplayFrame(visible);
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            int bottom = visible.bottom - location[1];
            // Some overlay windows receive only a visible-frame update, not IME insets.
            int nextBottom = bottom > 0 && root.getHeight() - bottom > dp(100) ? bottom : 0;
            if (visibleBottom != nextBottom) {
                visibleBottom = nextBottom;
                root.requestLayout();
                keepFocusedInputVisible();
            }
        });
        markup = root.findViewById(R.id.capture_markup_view);
        root.findViewById(R.id.capture_markup_container).setBackground(null);
        comment = root.findViewById(R.id.capture_comment_input);
        // Keep the lower panel compact when the keyboard or link field opens.
        comment.setMaxLines(2);
        comment.setMinHeight(dp(60));
        // Keep the primary writing field at the top of the scrollable form.
        composer.removeView(comment);
        composer.addView(comment, 0);
        comment.setOnFocusChangeListener((view, focused) -> { if (focused) keepFocusedInputVisible(); });
        kind = root.findViewById(R.id.capture_kind_group);
        status = root.findViewById(R.id.capture_editor_status);
        save = root.findViewById(R.id.capture_editor_save);
        sourceLink = new SourceLinkField(root);
        root.findViewById(R.id.capture_retain_image_context).setVisibility(View.VISIBLE);
        sourceLink.acceptDetected(source.url, source.origin);
        root.findViewById(R.id.capture_editor_cancel).setOnClickListener(view -> back());
        save.setOnClickListener(view -> {
            if (loading || saving) return;
            if (composing) save(); else setComposing(true);
        });
        save.setEnabled(false);
        LinearLayout header = (LinearLayout) save.getParent();
        Button minimize = new Button(context);
        minimize.setId(R.id.capture_overlay_minimize);
        minimize.setText(R.string.capture_overlay_minimize);
        minimize.setTextSize(13);
        minimize.setMinWidth(dp(48));
        minimize.setOnClickListener(view -> minimize());
        header.addView(minimize, header.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        bindTool(R.id.capture_tool_select, CaptureMarkupView.Tool.SELECT);
        bindTool(R.id.capture_tool_pen, CaptureMarkupView.Tool.PEN);
        bindTool(R.id.capture_tool_highlighter, CaptureMarkupView.Tool.HIGHLIGHTER);
        root.findViewById(R.id.capture_tool_undo).setOnClickListener(view -> markup.undo());
        root.findViewById(R.id.capture_tool_whole).setOnClickListener(view -> markup.selectWholeImage());
        LinearLayout tools = (LinearLayout) root.findViewById(R.id.capture_tool_whole).getParent();
        TextView move = new TextView(context, null, 0, R.style.CaptureToolChip);
        move.setId(R.id.capture_tool_move);
        move.setText(R.string.capture_dock_move);
        move.setOnClickListener(view -> { toolsAtTop = !toolsAtTop; positionDock(); });
        tools.addView(move);
        tools.setPadding(dp(4), 0, dp(4), 0);
        int[] toolIds = {R.id.capture_tool_select, R.id.capture_tool_pen, R.id.capture_tool_highlighter,
                R.id.capture_tool_undo, R.id.capture_tool_whole, R.id.capture_tool_move};
        String[] toolLabels = {"圈选", "画笔", "标记", "撤销", "全图", "移位"};
        for (int i = 0; i < toolIds.length; i++) {
            TextView tool = root.findViewById(toolIds[i]);
            tool.setContentDescription(tool.getText());
            tool.setText(toolLabels[i]);
            tool.setMinWidth(dp(48));
            tool.setPadding(dp(4), 0, dp(4), 0);
            tool.setBackground(null);
        }
        markup.setChangeListener(this::renderTools);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        params.setTitle(context.getString(R.string.capture_overlay_title));
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (Build.VERSION.SDK_INT >= 33) {
                    unregisterBack = Api33Back.register(root, CaptureOverlayEditor.this::back);
                }
            }
            @Override public void onViewDetachedFromWindow(View view) {
                attached = false;
                if (unregisterBack != null) {
                    unregisterBack.run();
                    unregisterBack = null;
                }
            }
        });
        setComposing(false);
    }

    private void positionDock() {
        FrameLayout.LayoutParams dock = (FrameLayout.LayoutParams) column.getLayoutParams();
        dock.gravity = composing || toolsAtTop ? Gravity.TOP : Gravity.BOTTOM;
        dock.topMargin = topInset + dp(12);
        dock.bottomMargin = bottomInset + dp(12);
        column.setLayoutParams(dock);
        TextView move = root.findViewById(R.id.capture_tool_move);
        if (move != null) move.setContentDescription(context.getString(toolsAtTop
                ? R.string.capture_dock_bottom : R.string.capture_dock_move));
    }

    private int usableBottom(int height) {
        int bottom = height - bottomInset;
        if (imeInset > bottomInset && Build.VERSION.SDK_INT >= 30) {
            int screenBottom = windows.getCurrentWindowMetrics().getBounds().bottom;
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            // Absolute keyboard top avoids subtracting IME height twice when
            // the system has already resized the overlay root.
            bottom = Math.min(bottom, screenBottom - location[1] - imeInset);
        }
        if (visibleBottom > 0) bottom = Math.min(bottom, visibleBottom);
        return Math.max(0, bottom);
    }

    private void keepFocusedInputVisible() {
        root.post(() -> {
            if (closed || !composing) return;
            View focused = root.findFocus();
            if (focused instanceof EditText) focused.requestRectangleOnScreen(
                    new Rect(0, 0, focused.getWidth(), focused.getHeight()), false);
        });
    }

    private void styleControls() {
        column.setBackgroundResource(composing ? R.drawable.bg_overlay_panel : android.R.color.transparent);
        column.setElevation(composing ? dp(12) : 0);
        TextView cancel = root.findViewById(R.id.capture_editor_cancel);
        cancel.setContentDescription(context.getString(composing ? R.string.capture_reselect : R.string.capture_cancel));
        CaptureToolStyle.action(cancel, composing ? 8 : 6, false, composing);
        TextView minimize = root.findViewById(R.id.capture_overlay_minimize);
        minimize.setContentDescription(context.getString(R.string.capture_overlay_minimize));
        CaptureToolStyle.action(minimize, 7, false, composing);
        CaptureToolStyle.action(save, -1, true, composing);
        LinearLayout header = (LinearLayout) save.getParent();
        header.setPadding(dp(4), 0, dp(4), 0);
        TextView title = root.findViewById(R.id.capture_editor_title);
        title.setVisibility(composing ? View.VISIBLE : View.INVISIBLE);
        title.setTextSize(13);
        title.setTextColor(context.getColor(R.color.ink));
        title.setShadowLayer(0, 0, 0, 0);
        int[] ids = {R.id.capture_tool_select, R.id.capture_tool_pen, R.id.capture_tool_highlighter,
                R.id.capture_tool_undo, R.id.capture_tool_whole, R.id.capture_tool_move};
        LinearLayout tools = (LinearLayout) root.findViewById(R.id.capture_tool_whole).getParent();
        tools.setGravity(Gravity.CENTER);
        ((android.widget.HorizontalScrollView) tools.getParent()).setFillViewport(true);
        for (int i = 0; i < ids.length; i++) CaptureToolStyle.tool(root.findViewById(ids[i]), i);
    }

    private void back() {
        if (composing && !saving) setComposing(false); else requestClose();
    }

    private void setComposing(boolean value) {
        composing = value;
        if (value && sourceBitmap != null) {
            Bitmap nextPreview = null;
            try { nextPreview = markup.renderAnnotatedSelection(); }
            catch (RuntimeException | OutOfMemoryError ignored) { }
            preview.setImageBitmap(nextPreview);
            recycle(previewBitmap);
            previewBitmap = nextPreview;
        }
        preview.setVisibility(value ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.capture_evidence_container).setAlpha(value ? .18f : 1f);
        status.setVisibility(value ? View.VISIBLE : View.GONE);
        status.setMaxLines(2);
        composerScroll.setVisibility(value ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.capture_tool_row).setVisibility(!value && !loading ? View.VISIBLE : View.GONE);
        markup.setEnabled(!value);
        ((TextView) root.findViewById(R.id.capture_editor_title)).setText(
                value ? R.string.capture_compose_title : R.string.capture_select_title);
        ((TextView) root.findViewById(R.id.capture_editor_cancel)).setText(
                value ? R.string.capture_reselect : R.string.capture_cancel);
        save.setText(value ? R.string.capture_save : R.string.capture_next);
        if (!loading) status.setText(value ? sourceSummary() : context.getString(R.string.capture_select_help));
        if (!value) {
            comment.clearFocus();
            InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        } else {
            comment.requestFocus();
            comment.post(() -> {
                if (!attached || !composing || closed) return;
                InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(comment, InputMethodManager.SHOW_IMPLICIT);
            });
        }
        positionDock();
        styleControls();
    }

    private String sourceSummary() {
        if (source.appPackage.isEmpty()) return context.getString(R.string.capture_source_unavailable_help);
        String message = source.url.isEmpty() ? context.getString(R.string.capture_detected_app_only)
                : context.getString("browser_address_bar_https".equals(source.origin)
                        ? R.string.capture_detected_url_https : R.string.capture_detected_url);
        return source.appLabel(context) + " · " + message;
    }

    boolean open() {
        if (!restore()) return false;
        ownsDraft = true;
        writer.execute(() -> {
            Bitmap decoded = CaptureStore.decodeEditorBitmap(draft);
            main.post(() -> {
                if (closed) {
                    recycle(decoded);
                    return;
                }
                loading = false;
                root.findViewById(R.id.capture_editor_progress).setVisibility(View.GONE);
                if (decoded == null) {
                    status.setText(R.string.capture_overlay_error);
                    status.setVisibility(View.VISIBLE);
                    Toast.makeText(context, R.string.capture_overlay_error, Toast.LENGTH_LONG).show();
                    return;
                }
                sourceBitmap = decoded;
                markup.setSourceBitmap(decoded);
                root.findViewById(R.id.capture_markup_container).setVisibility(View.VISIBLE);
                setComposing(false);
                save.setEnabled(true);
                renderTools();
            });
        });
        return true;
    }

    boolean restore() {
        if (closed) return false;
        if (attached) return true;
        try {
            windows.addView(root, params);
            attached = true;
            root.requestFocus();
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    void minimize() {
        if (closed || saving) return;
        suspend();
        Toast.makeText(context, R.string.capture_overlay_minimized, Toast.LENGTH_LONG).show();
    }

    void suspend() {
        // Hide immediately on screen-off/interruption, even during a save.
        // The writer can still finish atomically without a visible window.
        if (!closed) detach();
    }

    private void detach() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        if (!attached) return;
        InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        try {
            windows.removeViewImmediate(root);
        } catch (IllegalArgumentException ignored) {
            // WindowManager may already have removed a disconnected service's window.
        }
        attached = false;
    }

    void close() {
        if (closed) return;
        closed = true;
        detach();
        recycle(sourceBitmap);
        preview.setImageDrawable(null);
        recycle(previewBitmap);
        previewBitmap = null;
        sourceBitmap = null;
        // Let an in-flight atomic save finish; it still owns its image copies and draft.
        if (!saving && ownsDraft) CaptureStore.discardDraft(context, draft);
        writer.shutdown();
        onClosed.run();
    }

    private void bindTool(int id, CaptureMarkupView.Tool tool) {
        root.findViewById(id).setOnClickListener(view -> {
            markup.setTool(tool);
            renderTools();
        });
    }

    private void renderTools() {
        root.findViewById(R.id.capture_tool_select).setSelected(markup.getTool() == CaptureMarkupView.Tool.SELECT);
        root.findViewById(R.id.capture_tool_pen).setSelected(markup.getTool() == CaptureMarkupView.Tool.PEN);
        root.findViewById(R.id.capture_tool_highlighter).setSelected(markup.getTool() == CaptureMarkupView.Tool.HIGHLIGHTER);
        View undo = root.findViewById(R.id.capture_tool_undo);
        undo.setEnabled(markup.canUndo());
        undo.setAlpha(markup.canUndo() ? 1f : 0.4f);
        styleControls();
    }

    private void requestClose() {
        if (closed) return;
        if (saving) {
            Toast.makeText(context, R.string.capture_wait_for_save, Toast.LENGTH_SHORT).show();
            return;
        }
        if (dialog != null && dialog.isShowing()) return;
        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.capture_discard_title)
                .setMessage(R.string.capture_overlay_discard)
                .setPositiveButton(R.string.capture_discard, (ignored, which) -> close())
                .setNegativeButton(R.string.capture_continue_editing, null).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        }
        try {
            dialog.show();
        } catch (RuntimeException error) {
            // Never silently discard because an OEM refused the confirmation window.
            status.setText(R.string.capture_overlay_error);
            dialog = null;
        }
    }

    private void save() {
        if (closed || loading || saving || !composing || sourceBitmap == null) return;
        String url = sourceLink.validated();
        if (url == null) {
            CaptureAccessibilityService.showFeedback(context, R.string.capture_url_invalid);
            return;
        }
        String note = comment.getText().toString().trim();
        String urlOrigin = sourceLink.origin(url);
        int selected = kind.getCheckedRadioButtonId();
        String recordKind = selected == R.id.capture_kind_thought ? "thought"
                : selected == R.id.capture_kind_todo ? "todo" : "comment";
        Bitmap original = null;
        Bitmap annotated = null;
        JSONObject annotations;
        try {
            original = markup.renderOriginalSelection();
            annotated = markup.renderAnnotatedSelection();
            annotations = markup.annotationLayer();
            if (original == null || annotated == null) throw new IllegalStateException();
        } catch (Exception | OutOfMemoryError error) {
            recycle(original);
            recycle(annotated);
            status.setText(R.string.capture_error_prepare_save);
            CaptureAccessibilityService.showFeedback(context, R.string.capture_error_prepare_save);
            return;
        }
        saving = true;
        save.setEnabled(false);
        status.setText(R.string.capture_saving);
        Bitmap originalCopy = original;
        Bitmap annotatedCopy = annotated;
        boolean retainImage = ((android.widget.CheckBox)root.findViewById(R.id.capture_retain_image_context)).isChecked();
        writer.execute(() -> {
            boolean success = false;
            try {
                CaptureStore.CaptureRecord record;
                synchronized (CaptureAccountSession.LOCK) {
                    CaptureAccountSession.requireScope(context, ownerScope);
                    record = CaptureStore.save(context, draft,
                            originalCopy, annotatedCopy, annotations, recordKind, note, "screen", "", source.appPackage, url, urlOrigin,
                            retainImage, null);
                }
                success = true;
                if (CaptureStore.SYNC_PENDING.equals(record.syncState)) CaptureSyncWorker.enqueue(context);
            } catch (Exception error) {
                // Keep the session editable when the atomic local write fails.
            } finally {
                recycle(originalCopy);
                recycle(annotatedCopy);
            }
            boolean saved = success;
            main.post(() -> {
                saving = false;
                if (closed) {
                    if (ownsDraft) CaptureStore.discardDraft(context, draft);
                } else if (saved) {
                    close();
                } else {
                    status.setText(R.string.capture_error_save_failed);
                    save.setEnabled(true);
                }
                // Service-owned feedback survives a successful editor teardown.
                CaptureAccessibilityService.showFeedback(context,
                        saved ? R.string.capture_saved : R.string.capture_error_save_failed);
            });
        });
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @androidx.annotation.RequiresApi(33)
    private static final class Api33Back {
        static Runnable register(View root, Runnable action) {
            OnBackInvokedDispatcher dispatcher = root.findOnBackInvokedDispatcher();
            if (dispatcher == null) return null;
            OnBackInvokedCallback callback = action::run;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
