package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Crop, annotate, classify and atomically save a capture. */
public final class CaptureEditorActivity extends Activity {
    private static final String ACTION_EDIT_SCREENSHOT =
            "com.codex.mnote.action.EDIT_SCREENSHOT";
    private static final String EXTRA_DRAFT_PATH =
            "com.codex.mnote.extra.CAPTURE_DRAFT_PATH";

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new EditorThreadFactory());

    private CaptureMarkupView markupView;
    private View markupContainer;
    private View textContainer;
    private View toolRow;
    private View progress;
    private TextView title;
    private TextView status;
    private TextView sourceTextView;
    private TextView selectTool;
    private TextView penTool;
    private TextView highlighterTool;
    private TextView undoTool;
    private TextView wholeImageTool;
    private Button saveButton;
    private EditText commentInput;
    private RadioGroup kindGroup;

    private File draft;
    private Bitmap sourceBitmap;
    private String sourceType = "quick_note";
    private String sourceText = "";
    private String sourcePackage = "";
    private boolean processTextRequest;
    private boolean loading;
    private boolean saving;
    private boolean saved;
    private boolean destroyed;
    private boolean requestNoteKeyboard;
    private SourceLinkField sourceLink;

    static Intent forScreenshot(Activity activity, File draft) {
        return new Intent(activity, CaptureEditorActivity.class)
                .setAction(ACTION_EDIT_SCREENSHOT)
                .putExtra(EXTRA_DRAFT_PATH, draft.getAbsolutePath());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_editor);
        CaptureStore.cleanupStaleDrafts(this);
        bindViews();
        bindActions();
        sourcePackage = resolveSourcePackage();
        handleIntent(getIntent());
        if (ACTION_EDIT_SCREENSHOT.equals(getIntent().getAction())) {
            String detectedPackage = getIntent().getStringExtra("capture_source_package");
            if (detectedPackage != null) sourcePackage = detectedPackage;
            sourceLink.acceptDetected(getIntent().getStringExtra("capture_source_url"),
                    getIntent().getStringExtra("capture_source_origin"));
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        if (isFinishing() && !saved && draft != null) {
            CaptureStore.discardDraft(this, draft);
        }
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        requestCancel();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && requestNoteKeyboard && !isFinishing()) {
            commentInput.post(() -> {
                if (destroyed || !hasWindowFocus() || !requestNoteKeyboard) {
                    return;
                }
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null) {
                    keyboard.showSoftInput(commentInput, InputMethodManager.SHOW_IMPLICIT);
                }
                requestNoteKeyboard = false;
            });
        }
    }

    private void bindViews() {
        markupView = findViewById(R.id.capture_markup_view);
        markupContainer = findViewById(R.id.capture_markup_container);
        markupContainer.setClipToOutline(true);
        textContainer = findViewById(R.id.capture_source_text_container);
        toolRow = findViewById(R.id.capture_tool_row);
        progress = findViewById(R.id.capture_editor_progress);
        title = findViewById(R.id.capture_editor_title);
        status = findViewById(R.id.capture_editor_status);
        sourceTextView = findViewById(R.id.capture_source_text);
        selectTool = findViewById(R.id.capture_tool_select);
        penTool = findViewById(R.id.capture_tool_pen);
        highlighterTool = findViewById(R.id.capture_tool_highlighter);
        undoTool = findViewById(R.id.capture_tool_undo);
        wholeImageTool = findViewById(R.id.capture_tool_whole);
        saveButton = findViewById(R.id.capture_editor_save);
        commentInput = findViewById(R.id.capture_comment_input);
        kindGroup = findViewById(R.id.capture_kind_group);
        sourceLink = new SourceLinkField(findViewById(android.R.id.content));
    }

    private void bindActions() {
        findViewById(R.id.capture_editor_cancel).setOnClickListener(
                view -> requestCancel()
        );
        saveButton.setOnClickListener(view -> save());
        selectTool.setOnClickListener(
                view -> selectTool(CaptureMarkupView.Tool.SELECT)
        );
        penTool.setOnClickListener(
                view -> selectTool(CaptureMarkupView.Tool.PEN)
        );
        highlighterTool.setOnClickListener(
                view -> selectTool(CaptureMarkupView.Tool.HIGHLIGHTER)
        );
        undoTool.setOnClickListener(view -> markupView.undo());
        wholeImageTool.setOnClickListener(view -> markupView.selectWholeImage());
        markupView.setChangeListener(this::renderToolState);
        selectTool(CaptureMarkupView.Tool.SELECT);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            showQuickNote();
            return;
        }
        String action = intent.getAction();
        if (ACTION_EDIT_SCREENSHOT.equals(action)) {
            sourceType = "screen";
            title.setText(R.string.capture_editor_screen_title);
            File candidate = CaptureStore.safeDraftFile(
                    this,
                    intent.getStringExtra(EXTRA_DRAFT_PATH)
            );
            if (candidate == null) {
                showBlockingError(R.string.capture_error_missing_draft);
                return;
            }
            draft = candidate;
            loadDraft(candidate);
            return;
        }
        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            processTextRequest = true;
            sourceType = "process_text";
            title.setText(R.string.capture_editor_text_title);
            CharSequence supplied = intent.getCharSequenceExtra(
                    Intent.EXTRA_PROCESS_TEXT
            );
            sourceText = cleanText(supplied, 100_000);
            sourceLink.acceptSharedText(supplied);
            if (sourceText.isEmpty()) {
                showBlockingError(R.string.capture_error_empty_shared_text);
                return;
            }
            showTextOnly();
            return;
        }
        if (Intent.ACTION_SEND.equals(action)) {
            title.setText(R.string.capture_editor_share_title);
            sourceText = cleanText(
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                    100_000
            );
            Uri image = sharedImageUri(intent);
            sourceLink.acceptSharedText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
            if (image != null) {
                sourceType = sourceText.isEmpty() ? "share_image" : "share_text";
                importSharedImage(image);
            } else if (!sourceText.isEmpty()) {
                sourceType = "share_text";
                showTextOnly();
            } else {
                showBlockingError(R.string.capture_error_empty_share);
            }
            return;
        }
        showQuickNote();
    }

    private void showQuickNote() {
        sourceType = "quick_note";
        title.setText(R.string.capture_editor_quick_title);
        kindGroup.check(R.id.capture_kind_thought);
        markupContainer.setVisibility(View.GONE);
        textContainer.setVisibility(View.GONE);
        toolRow.setVisibility(View.GONE);
        status.setText(R.string.capture_quick_note_detail);
        setLoading(false);
        // No invisible screenshot frame above the input: the entire remaining
        // page becomes a writing surface, including when the keyboard opens.
        findViewById(R.id.capture_evidence_container).setVisibility(View.GONE);
        LinearLayout composer = findViewById(R.id.capture_composer);
        LinearLayout.LayoutParams composerParams =
                (LinearLayout.LayoutParams) composer.getLayoutParams();
        composerParams.height = 0;
        composerParams.weight = 1;
        composer.setLayoutParams(composerParams);
        LinearLayout.LayoutParams inputParams =
                (LinearLayout.LayoutParams) commentInput.getLayoutParams();
        inputParams.height = 0;
        inputParams.weight = 1;
        commentInput.setLayoutParams(inputParams);
        commentInput.setMaxLines(Integer.MAX_VALUE);
        commentInput.setMinHeight(0);
        commentInput.setTextSize(19);
        commentInput.setGravity(Gravity.TOP | Gravity.START);
        commentInput.setHint(R.string.quick_note_input_hint);
        requestNoteKeyboard = true;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        commentInput.requestFocus();
    }

    private void loadDraft(File source) {
        setLoading(true);
        status.setText(R.string.capture_loading_image);
        executor.execute(() -> {
            Bitmap bitmap = CaptureStore.decodeEditorBitmap(source);
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                if (bitmap == null) {
                    showBlockingError(R.string.capture_error_unreadable_image);
                    return;
                }
                showImage(bitmap);
            });
        });
    }

    private void importSharedImage(Uri image) {
        setLoading(true);
        status.setText(R.string.capture_importing_image);
        executor.execute(() -> {
            File imported = null;
            Bitmap bitmap = null;
            try {
                imported = CaptureStore.importImageDraft(this, image);
                bitmap = CaptureStore.decodeEditorBitmap(imported);
                if (bitmap == null) {
                    throw new IOException("Imported image cannot be decoded");
                }
            } catch (IOException | RuntimeException error) {
                if (imported != null) {
                    CaptureStore.discardDraft(this, imported);
                }
            }
            File finalImported = imported;
            Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) {
                    if (finalBitmap != null) {
                        finalBitmap.recycle();
                    }
                    if (finalImported != null) {
                        CaptureStore.discardDraft(this, finalImported);
                    }
                    return;
                }
                if (finalBitmap == null || finalImported == null) {
                    if (!sourceText.isEmpty()) {
                        sourceType = "share_text";
                        showTextOnly();
                        status.setText(R.string.capture_image_degraded_to_text);
                    } else {
                        showBlockingError(R.string.capture_error_unreadable_image);
                    }
                    return;
                }
                draft = finalImported;
                showImage(finalBitmap);
            });
        });
    }

    private void showImage(Bitmap bitmap) {
        sourceBitmap = bitmap;
        markupView.setSourceBitmap(bitmap);
        markupContainer.setVisibility(View.VISIBLE);
        textContainer.setVisibility(View.GONE);
        toolRow.setVisibility(View.VISIBLE);
        status.setText(
                sourceText.isEmpty()
                        ? R.string.capture_image_help
                        : R.string.capture_image_with_text_help
        );
        setLoading(false);
        renderToolState();
    }

    private void showTextOnly() {
        markupContainer.setVisibility(View.GONE);
        toolRow.setVisibility(View.GONE);
        textContainer.setVisibility(View.VISIBLE);
        sourceTextView.setText(sourceText);
        status.setText(R.string.capture_exact_text_help);
        setLoading(false);
    }

    private void selectTool(CaptureMarkupView.Tool tool) {
        markupView.setTool(tool);
        renderToolState();
    }

    private void renderToolState() {
        CaptureMarkupView.Tool selected = markupView.getTool();
        selectTool.setSelected(selected == CaptureMarkupView.Tool.SELECT);
        penTool.setSelected(selected == CaptureMarkupView.Tool.PEN);
        highlighterTool.setSelected(
                selected == CaptureMarkupView.Tool.HIGHLIGHTER
        );
        boolean canUndo = markupView.canUndo();
        undoTool.setEnabled(canUndo);
        undoTool.setAlpha(canUndo ? 1f : 0.42f);
    }

    private void setLoading(boolean value) {
        loading = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!value && !saving);
    }

    private void save() {
        if (loading || saving) {
            return;
        }
        String comment = commentInput.getText().toString().trim();
        String url = sourceLink.validated();
        if (url == null) {
            Toast.makeText(this, R.string.capture_url_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        String urlOrigin = sourceLink.origin(url);
        if (sourceBitmap == null && sourceText.isEmpty() && comment.isEmpty() && url.isEmpty()) {
            commentInput.setError(getString(R.string.capture_comment_required));
            commentInput.requestFocus();
            return;
        }

        Bitmap original = null;
        Bitmap annotated = null;
        JSONObject annotation = null;
        if (sourceBitmap != null) {
            try {
                original = markupView.renderOriginalSelection();
                annotated = markupView.renderAnnotatedSelection();
                annotation = markupView.annotationLayer();
            } catch (OutOfMemoryError | JSONException | RuntimeException error) {
                recycle(original);
                recycle(annotated);
                showStatusError(R.string.capture_error_prepare_save);
                return;
            }
            if (original == null || annotated == null) {
                recycle(original);
                recycle(annotated);
                showStatusError(R.string.capture_error_prepare_save);
                return;
            }
        }

        saving = true;
        saveButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.capture_saving);
        Bitmap finalOriginal = original;
        Bitmap finalAnnotated = annotated;
        JSONObject finalAnnotation = annotation;
        String kind = selectedKind();
        executor.execute(() -> {
            try {
                CaptureStore.CaptureRecord record = CaptureStore.save(
                        this,
                        draft,
                        finalOriginal,
                        finalAnnotated,
                        finalAnnotation,
                        kind,
                        comment,
                        sourceType,
                        sourceText,
                        sourcePackage,
                        url,
                        urlOrigin
                );
                if (CaptureStore.SYNC_PENDING.equals(record.syncState)) {
                    CaptureSyncWorker.enqueue(this);
                }
                runOnUiThread(this::finishSaved);
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    saving = false;
                    progress.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    showStatusError(R.string.capture_error_save_failed);
                });
            } finally {
                recycle(finalOriginal);
                recycle(finalAnnotated);
            }
        });
    }

    private void finishSaved() {
        if (destroyed || isFinishing()) {
            return;
        }
        saved = true;
        draft = null;
        if (processTextRequest) {
            Intent result = new Intent().putExtra(
                    Intent.EXTRA_PROCESS_TEXT,
                    sourceText
            );
            setResult(RESULT_OK, result);
        } else {
            setResult(RESULT_OK);
        }
        Toast.makeText(
                this,
                R.string.capture_saved,
                Toast.LENGTH_SHORT
        ).show();
        finish();
    }

    private void requestCancel() {
        if (saving) {
            Toast.makeText(
                    this,
                    R.string.capture_wait_for_save,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        if (!loading && sourceBitmap == null && sourceText.isEmpty()
                && commentInput.getText().toString().trim().isEmpty() && !sourceLink.hasInput()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.capture_discard_title)
                .setMessage(R.string.capture_discard_detail)
                .setPositiveButton(R.string.capture_discard, (dialog, which) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .setNegativeButton(R.string.capture_continue_editing, null)
                .show();
    }

    private void showBlockingError(int message) {
        setLoading(false);
        status.setTextColor(getColor(R.color.danger));
        status.setText(message);
        saveButton.setEnabled(false);
        new AlertDialog.Builder(this)
                .setTitle(R.string.capture_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.capture_confirm, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void showStatusError(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        status.setTextColor(getColor(R.color.danger));
        status.setText(message);
    }

    private String selectedKind() {
        int selected = kindGroup.getCheckedRadioButtonId();
        if (selected == R.id.capture_kind_thought) {
            return "thought";
        }
        if (selected == R.id.capture_kind_todo) {
            return "todo";
        }
        return "comment";
    }

    private Uri sharedImageUri(Intent intent) {
        Uri stream;
        if (Build.VERSION.SDK_INT >= 33) {
            stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else {
            //noinspection deprecation
            stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (stream != null) {
            return stream;
        }
        ClipData clip = intent.getClipData();
        if (clip == null) {
            return null;
        }
        for (int index = 0; index < clip.getItemCount(); index++) {
            Uri uri = clip.getItemAt(index).getUri();
            if (uri != null) {
                return uri;
            }
        }
        return null;
    }

    private String resolveSourcePackage() {
        String caller = getCallingPackage();
        if (caller != null && !caller.equals(getPackageName())) {
            return caller;
        }
        Uri referrer = getReferrer();
        if (referrer != null && "android-app".equals(referrer.getScheme())) {
            String host = referrer.getHost();
            return host == null ? "" : host;
        }
        return "";
    }

    private static String cleanText(CharSequence text, int maximum) {
        if (text == null) {
            return "";
        }
        String clean = text.toString().replace('\u0000', ' ').trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static final class EditorThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "capture-editor");
            thread.setDaemon(true);
            return thread;
        }
    }
}
