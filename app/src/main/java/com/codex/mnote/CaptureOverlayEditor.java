package com.codex.mnote;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
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
    private final WindowManager windows;
    private final File draft;
    private final Runnable onClosed;
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
    private Bitmap sourceBitmap;
    private AlertDialog dialog;
    private boolean attached;
    private boolean loading = true;
    private boolean saving;
    private boolean closed;
    private boolean ownsDraft;
    private Runnable unregisterBack;

    CaptureOverlayEditor(Context service, File draft, Runnable onClosed) {
        this.context = new ContextThemeWrapper(service, R.style.Theme_Mnote);
        this.windows = service.getSystemService(WindowManager.class);
        this.draft = draft;
        this.onClosed = onClosed;
        root = new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (composerScroll != null) {
                    composerScroll.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
                }
                super.onMeasure(widthSpec, heightSpec);
                if (composerScroll == null || column == null) return;
                int available = column.getMeasuredHeight() - column.getPaddingTop() - column.getPaddingBottom();
                for (int index = 0; index < column.getChildCount(); index++) {
                    View child = column.getChildAt(index);
                    if (child == composerScroll || child.getVisibility() == View.GONE
                            || child.getId() == R.id.capture_evidence_container) continue;
                    LinearLayout.LayoutParams childParams = (LinearLayout.LayoutParams) child.getLayoutParams();
                    available -= child.getMeasuredHeight() + childParams.topMargin + childParams.bottomMargin;
                }
                // A weighted image above a wrap-content ScrollView otherwise
                // lets the sheet measure against the whole window, overflow it,
                // and report no scroll range. Cap it to the actual remainder.
                if (composerScroll.getMeasuredHeight() > Math.max(0, available)) {
                    composerScroll.getLayoutParams().height = Math.max(0, available);
                    super.onMeasure(widthSpec, heightSpec);
                }
            }

            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) requestClose();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setFocusableInTouchMode(true);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(0x33000000);
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
        markup = root.findViewById(R.id.capture_markup_view);
        root.findViewById(R.id.capture_markup_container).setClipToOutline(true);
        comment = root.findViewById(R.id.capture_comment_input);
        // Keep the lower panel compact when the keyboard or link field opens.
        comment.setMaxLines(2);
        comment.setMinHeight(dp(60));
        kind = root.findViewById(R.id.capture_kind_group);
        status = root.findViewById(R.id.capture_editor_status);
        save = root.findViewById(R.id.capture_editor_save);
        sourceLink = new SourceLinkField(root);
        ((TextView) root.findViewById(R.id.capture_editor_title)).setText(R.string.capture_overlay_title);
        root.findViewById(R.id.capture_editor_cancel).setOnClickListener(view -> requestClose());
        save.setOnClickListener(view -> save());
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
                    unregisterBack = Api33Back.register(root, CaptureOverlayEditor.this::requestClose);
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
                    return;
                }
                sourceBitmap = decoded;
                markup.setSourceBitmap(decoded);
                root.findViewById(R.id.capture_markup_container).setVisibility(View.VISIBLE);
                root.findViewById(R.id.capture_tool_row).setVisibility(View.VISIBLE);
                status.setText(R.string.capture_overlay_help);
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
        if (closed || loading || saving || sourceBitmap == null) return;
        String url = sourceLink.validated();
        if (url == null) return;
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
            return;
        }
        saving = true;
        save.setEnabled(false);
        status.setText(R.string.capture_saving);
        Bitmap originalCopy = original;
        Bitmap annotatedCopy = annotated;
        writer.execute(() -> {
            boolean success = false;
            try {
                CaptureStore.CaptureRecord record = CaptureStore.save(context, draft,
                        originalCopy, annotatedCopy, annotations, recordKind, note, "screen", "", "", url, urlOrigin);
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
                    return;
                }
                if (saved) {
                    Toast.makeText(context, R.string.capture_saved, Toast.LENGTH_SHORT).show();
                    close();
                } else {
                    status.setText(R.string.capture_error_save_failed);
                    save.setEnabled(true);
                }
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
