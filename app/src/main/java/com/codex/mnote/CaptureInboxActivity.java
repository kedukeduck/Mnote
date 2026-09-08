package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Local Inbox and setup page for universal captures. */
public final class CaptureInboxActivity extends Activity {
    private static final int RECORD_LIMIT = 50;

    private final ExecutorService thumbnailExecutor =
            Executors.newSingleThreadExecutor(new ThumbnailThreadFactory());
    private final List<Bitmap> thumbnails = new ArrayList<>();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
    private Button refreshButton;
    private TextView refreshStatus;
    private boolean refreshing;
    private final BroadcastReceiver syncChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CaptureSyncWorker.ACTION_SYNC_CHANGED.equals(intent.getAction())
                    || CaptureStore.ACTION_RECORDS_CHANGED.equals(intent.getAction())) {
                renderRecords();
            }
        }
    };

    private TextView accessStatus;
    private Button captureButton;
    private Button addTileButton;
    private Button addNoteTileButton;
    private TextView recordCount;
    private TextView syncStatus;
    private Button syncAllButton;
    private LinearLayout recordsContainer;
    private View emptyState;
    private int renderGeneration;
    private volatile boolean destroyed;
    private boolean syncReceiverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_inbox);
        CaptureStore.cleanupStaleDrafts(this);
        bindViews();
        bindActions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAccessStatus();
        renderRecords();
        if (CaptureAccountSession.hasAccount(this)) CaptureAccountSync.enqueue(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!syncReceiverRegistered) {
            IntentFilter changes = new IntentFilter(CaptureSyncWorker.ACTION_SYNC_CHANGED);
            changes.addAction(CaptureStore.ACTION_RECORDS_CHANGED);
            ContextCompat.registerReceiver(
                    this,
                    syncChangedReceiver,
                    changes,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            syncReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (syncReceiverRegistered) {
            unregisterReceiver(syncChangedReceiver);
            syncReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        renderGeneration++;
        thumbnailExecutor.shutdownNow();
        refreshExecutor.shutdownNow();
        clearThumbnails();
        super.onDestroy();
    }

    private void bindViews() {
        accessStatus = findViewById(R.id.capture_access_status);
        captureButton = findViewById(R.id.capture_start_button);
        addTileButton = findViewById(R.id.capture_add_tile_button);
        addNoteTileButton = findViewById(R.id.capture_add_note_tile_button);
        recordCount = findViewById(R.id.capture_record_count);
        syncStatus = findViewById(R.id.capture_sync_status);
        syncAllButton = findViewById(R.id.capture_sync_all_button);
        refreshButton = findViewById(R.id.capture_refresh_button);
        refreshStatus = findViewById(R.id.capture_refresh_status);
        recordsContainer = findViewById(R.id.capture_records);
        emptyState = findViewById(R.id.capture_empty);
    }

    private void bindActions() {
        captureButton.setOnClickListener(view -> startCaptureOrSetup());
        addTileButton.setOnClickListener(view -> requestTile(false));
        addNoteTileButton.setOnClickListener(view -> requestTile(true));
        findViewById(R.id.capture_setup_toggle).setOnClickListener(view -> {
            View panel = findViewById(R.id.capture_setup_panel);
            boolean expanded = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            ((TextView) view).setText(expanded
                    ? R.string.capture_setup_collapse : R.string.capture_setup_expand);
        });
        findViewById(R.id.capture_quick_note_button).setOnClickListener(
                view -> startActivity(
                        new Intent(this, CaptureEditorActivity.class)
                )
        );
        findViewById(R.id.capture_sync_settings_button).setOnClickListener(
                view -> openSyncSettings()
        );
        syncAllButton.setOnClickListener(view -> syncAll());
        findViewById(R.id.capture_source_settings_button).setOnClickListener(view ->
                new AlertDialog.Builder(this).setTitle(R.string.capture_source_settings)
                        .setMessage(CaptureAccessibilitySettings.diagnostic(this))
                        .setPositiveButton(R.string.capture_open_accessibility_settings,
                                (dialog, which) -> openAccessibilitySettings())
                        .setNegativeButton(R.string.capture_cancel, null).show());
        refreshButton.setOnClickListener(view -> refreshRecords());
    }

    private void refreshRecords() {
        // Local refresh must work even offline, without a token, or during a remote pull.
        renderRecords();
        if (refreshing) return;
        if (!CaptureAccountSession.hasAccount(this)) {
            refreshStatus.setText("本机记录已刷新；登录后自动同步云端记录。");
            openSyncSettings(); return;
        }
        final CaptureSyncPreferences.Config config;
        try { config = CaptureSyncPreferences.load(this); }
        catch (Exception error) {
            refreshStatus.setText(R.string.capture_refresh_setup);
            openSyncSettings();
            return;
        }
        refreshing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText(R.string.capture_refresh_busy);
        refreshStatus.setText(R.string.capture_refresh_downloading);
        refreshExecutor.execute(() -> {
            String message;
            try {
                int count = CaptureAccountSync.run(getApplicationContext());
                message = count == 0 ? getString(R.string.capture_refresh_current)
                        : getString(R.string.capture_refresh_success, count);
            } catch (Exception error) {
                String reason = error.getMessage();
                message = getString("http_401".equals(reason) || "http_403".equals(reason)
                        ? R.string.capture_refresh_auth_error : "more_records_pending".equals(reason)
                        ? R.string.capture_refresh_more : "configuration_changed".equals(reason)
                        ? R.string.capture_refresh_changed : R.string.capture_refresh_failed);
            }
            String result = message;
            runOnUiThread(() -> {
                if (destroyed) return;
                refreshing = false;
                refreshButton.setEnabled(true);
                refreshButton.setText(R.string.capture_refresh);
                refreshStatus.setText(result);
                renderRecords();
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void renderAccessStatus() {
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        boolean configured = supported
                && CaptureAccessibilityService.isConfigured(this);
        boolean ready = configured && CaptureAccessibilityService.isReady();
        if (!supported) {
            accessStatus.setText(R.string.capture_access_unsupported);
            accessStatus.setTextColor(getColor(R.color.danger));
            captureButton.setText(R.string.capture_setup_button);
            captureButton.setEnabled(false);
            return;
        }
        captureButton.setEnabled(true);
        if (ready) {
            accessStatus.setText(R.string.capture_access_ready_detail);
            accessStatus.setTextColor(getColor(R.color.success));
            captureButton.setText(CaptureAccessibilityService.hasOverlay()
                    ? R.string.capture_tile_resume : R.string.capture_test_capture_button);
        } else if (configured) {
            accessStatus.setText(R.string.capture_access_connecting_detail);
            accessStatus.setTextColor(getColor(R.color.ink_muted));
            captureButton.setText(R.string.capture_retry_connection_button);
        } else {
            accessStatus.setText(R.string.capture_access_disabled_detail);
            accessStatus.setTextColor(getColor(R.color.ink_muted));
            captureButton.setText(R.string.capture_setup_button);
        }
    }

    private void startCaptureOrSetup() {
        if (CaptureAccessibilityService.isReady()) {
            startActivity(new Intent(this, CaptureTriggerActivity.class));
            return;
        }
        if (CaptureAccessibilityService.isConfigured(this)) {
            Toast.makeText(
                    this,
                    R.string.capture_service_connecting_toast,
                    Toast.LENGTH_SHORT
            ).show();
            renderAccessStatus();
            return;
        }
        showAccessibilityDisclosure();
    }

    private void showAccessibilityDisclosure() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.capture_accessibility_dialog_title)
                .setMessage(R.string.capture_accessibility_dialog_detail)
                .setPositiveButton(
                        R.string.capture_open_accessibility_settings,
                        (dialog, which) -> openAccessibilitySettings()
                )
                .setNegativeButton(R.string.capture_cancel, null)
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            CaptureAccessibilitySettings.open(this);
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    R.string.capture_error_open_accessibility_settings,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void requestTile(boolean note) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(
                    this,
                    R.string.capture_add_tile_manual,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        StatusBarManager manager = getSystemService(StatusBarManager.class);
        if (manager == null) {
            Toast.makeText(
                    this,
                    R.string.capture_add_tile_manual,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        addTileButton.setEnabled(false);
        addNoteTileButton.setEnabled(false);
        try {
            manager.requestAddTileService(
                    new ComponentName(this, note
                            ? QuickNoteTileService.class : CaptureQuickSettingsTileService.class),
                    getString(note ? R.string.quick_note_tile_label : R.string.capture_tile_label),
                    Icon.createWithResource(this, note
                            ? R.drawable.ic_quick_note : R.drawable.ic_capture_tile),
                    getMainExecutor(),
                    result -> {
                        addTileButton.setEnabled(true);
                        addNoteTileButton.setEnabled(true);
                        int message;
                        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                            message = note ? R.string.quick_note_tile_added : R.string.capture_tile_added;
                        } else if (result
                                == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            message = note ? R.string.quick_note_tile_already_added
                                    : R.string.capture_tile_already_added;
                        } else {
                            message = R.string.capture_tile_not_added;
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    }
            );
        } catch (RuntimeException error) {
            // OEMs may not implement the tile prompt. Manual editing still works.
            addTileButton.setEnabled(true);
            addNoteTileButton.setEnabled(true);
            Toast.makeText(this, R.string.capture_add_tile_manual, Toast.LENGTH_LONG).show();
        }
    }

    private void renderRecords() {
        int generation = ++renderGeneration;
        clearThumbnails();
        recordsContainer.removeAllViews();
        List<CaptureStore.CaptureRecord> allRecords = CaptureStore.list(
                this,
                Integer.MAX_VALUE
        );
        renderSyncStatus(allRecords);
        allRecords = CaptureRemoteCache.merged(this, allRecords);
        List<CaptureStore.CaptureRecord> records = allRecords.size() <= RECORD_LIMIT
                ? allRecords
                : new ArrayList<>(allRecords.subList(0, RECORD_LIMIT));
        recordCount.setText(getString(
                R.string.capture_inbox_count,
                allRecords.size()
        ));
        emptyState.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        recordsContainer.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CaptureStore.CaptureRecord record : records) {
            View card = inflater.inflate(
                    R.layout.item_capture_record,
                    recordsContainer,
                    false
            );
            bindRecord(card, record, generation);
            recordsContainer.addView(card);
        }
    }

    private void bindRecord(
            View card,
            CaptureStore.CaptureRecord record,
            int generation
    ) {
        String ownerScope = CaptureAccountSession.scope(this);
        TextView kind = card.findViewById(R.id.capture_item_kind);
        TextView time = card.findViewById(R.id.capture_item_time);
        TextView comment = card.findViewById(R.id.capture_item_comment);
        TextView source = card.findViewById(R.id.capture_item_source);
        TextView exactText = card.findViewById(R.id.capture_item_exact_text);
        TextView sync = card.findViewById(R.id.capture_item_sync_status);
        ImageView image = card.findViewById(R.id.capture_item_image);
        image.setClipToOutline(true);

        kind.setText(kindLabel(record.kind));
        time.setText(DateFormat.format(
                "yyyy-MM-dd HH:mm",
                new Date(record.createdAt)
        ));
        setOptionalText(comment, record.comment);
        source.setText(sourceTypeLabel(record.sourceType));
        if (!record.sourceUrl.isEmpty()) {
            source.append(" · " + getString(R.string.capture_url_saved_badge));
        }
        sync.setText(syncStateLabel(record.syncState));
        if (CaptureStore.SYNC_SYNCED.equals(record.syncState)) {
            sync.setTextColor(getColor(R.color.success));
        } else if (CaptureStore.SYNC_FAILED.equals(record.syncState)) {
            sync.setTextColor(getColor(R.color.danger));
        } else {
            sync.setTextColor(getColor(R.color.ink_muted));
        }
        if (record.sourceText.isEmpty()) {
            exactText.setVisibility(View.GONE);
        } else {
            exactText.setVisibility(View.VISIBLE);
            exactText.setText(ellipsize(record.sourceText, 420));
        }
        image.setVisibility(record.hasImage ? View.VISIBLE : View.GONE);
        if (record.hasImage) {
            thumbnailExecutor.execute(() -> {
                Bitmap thumbnail = CaptureStore.decodeThumbnail(
                        record.annotatedFile
                );
                runOnUiThread(() -> {
                    if (thumbnail == null) {
                        image.setVisibility(View.GONE);
                        return;
                    }
                    if (destroyed || generation != renderGeneration) {
                        thumbnail.recycle();
                        return;
                    }
                    thumbnails.add(thumbnail);
                    image.setImageBitmap(thumbnail);
                });
            });
        }
        card.setContentDescription(
                kind.getText() + "，" + time.getText() + "，" + source.getText()
                        + "，" + sync.getText() + "，"
                        + getString(R.string.capture_detail_open_hint)
        );
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showRecordDetail(record, ownerScope));
        card.setOnLongClickListener(view -> { confirmDelete(record, ownerScope); return true; });
    }

    private void showRecordDetail(CaptureStore.CaptureRecord record, String ownerScope) {
        if (!ownerScope.equals(CaptureAccountSession.scope(this))) return;
        if (!record.hasImage) {
            presentRecordDetail(record, null, ownerScope);
            return;
        }
        Toast.makeText(
                this,
                R.string.capture_detail_loading,
                Toast.LENGTH_SHORT
        ).show();
        thumbnailExecutor.execute(() -> {
            Bitmap image = CaptureStore.decodeReviewBitmap(record.annotatedFile);
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || !ownerScope.equals(CaptureAccountSession.scope(this))) {
                    if (image != null) {
                        image.recycle();
                    }
                    return;
                }
                presentRecordDetail(record, image, ownerScope);
            });
        });
    }

    private void presentRecordDetail(
            CaptureStore.CaptureRecord record,
            Bitmap image,
            String ownerScope
    ) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(22));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        if (image != null) {
            ImageView preview = new ImageView(this);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setImageBitmap(image);
            preview.setContentDescription(
                    getString(R.string.capture_item_image_description)
            );
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            imageParams.bottomMargin = dp(16);
            content.addView(preview, imageParams);
        }
        addDetailBlock(
                content,
                R.string.capture_detail_comment_label,
                record.comment,
                true
        );
        addDetailBlock(
                content,
                R.string.capture_detail_source_text_label,
                record.sourceText,
                true
        );
        String metadata = getString(
                R.string.capture_detail_metadata_format,
                getString(sourceTypeLabel(record.sourceType)),
                record.sourcePackage.isEmpty()
                        ? getString(R.string.capture_source_unknown)
                        : CaptureSourceContext.appLabel(this, record.sourcePackage) + " · " + record.sourcePackage,
                record.fidelityLevel,
                record.aiAccess,
                getString(syncStateLabel(record.syncState))
        );
        org.json.JSONObject textContext = record.captureContext.optJSONObject("text");
        if (textContext != null) addDetailBlock(content,R.string.capture_context_text_title,
                textContext.optString("full_text",""),true);
        if (record.contextFile != null) {
            Button contextButton=new Button(this); contextButton.setText(R.string.capture_context_image_open);
            contextButton.setOnClickListener(view->showContextImage(record,ownerScope));
            content.addView(contextButton);
        }
        if (!record.sourceUrl.isEmpty()) {
            addDetailBlock(content, R.string.capture_url_detail, record.sourceUrl, true);
            Button openSource = new Button(this);
            openSource.setText(R.string.capture_url_open);
            openSource.setOnClickListener(view -> openSourceUrl(record.sourceUrl));
            content.addView(openSource, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        addDetailBlock(
                content,
                R.string.capture_detail_metadata_label,
                metadata,
                false
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(
                        R.string.capture_detail_title_format,
                        getString(kindLabel(record.kind)),
                        DateFormat.format("yyyy-MM-dd HH:mm", new Date(record.createdAt))
                ))
                .setView(scroll)
                .setPositiveButton(R.string.capture_confirm, null)
                .setNegativeButton("删除记录", (ignored, which) -> confirmDelete(record, ownerScope))
                .create();
        if (image != null) {
            dialog.setOnDismissListener(ignored -> {
                if (!image.isRecycled()) {
                    image.recycle();
                }
            });
        }
        dialog.show();
    }

    private void showContextImage(CaptureStore.CaptureRecord record, String ownerScope) {
        thumbnailExecutor.execute(()-> {
            Bitmap bitmap=CaptureStore.decodeReviewBitmap(record.contextFile);
            runOnUiThread(()-> {
                if (destroyed || isFinishing() || !ownerScope.equals(CaptureAccountSession.scope(this))) {
                    if(bitmap!=null) bitmap.recycle(); return;
                }
                if(bitmap==null) { Toast.makeText(this,R.string.capture_error_unreadable_image,Toast.LENGTH_SHORT).show(); return; }
                ScrollView scroll=new ScrollView(this);
                CaptureContextPreview preview=new CaptureContextPreview(this,bitmap,record.captureContext.optJSONObject("image"));
                scroll.addView(preview,new ScrollView.LayoutParams(-1,-2));
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.capture_context_image_title)
                        .setView(scroll).setPositiveButton(R.string.capture_confirm,null).create();
                dialog.setOnDismissListener(ignored->bitmap.recycle()); dialog.show();
            });
        });
    }

    private void confirmDelete(CaptureStore.CaptureRecord record, String scope) {
        if (!scope.equals(CaptureAccountSession.scope(this))) return;
        new AlertDialog.Builder(this).setTitle("删除这条记录？")
                .setMessage(CaptureAccountSession.hasAccount(this)
                        ? "将从当前列表移除，联网后同步移到账号回收站。其他设备同步后也会移除。"
                        : "将从本机列表移除。未登录时不会删除服务器上的副本。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    refreshExecutor.execute(() -> {
                        boolean ok = false;
                        try { synchronized (CaptureAccountSession.LOCK) {
                            CaptureAccountSession.requireScope(this, scope);
                            CaptureDeletionStore.delete(this, record.id); ok = true;
                        }} catch (Exception ignored) { }
                        boolean success = ok;
                        runOnUiThread(() -> { if (!destroyed) {
                            renderRecords(); Toast.makeText(this, success ? ("guest".equals(scope) ? "本机记录已删除" : "已删除，联网后同步") : "删除失败，记录已保留", Toast.LENGTH_SHORT).show();
                        }});
                    });
                }).show();
    }

    private void addDetailBlock(
            LinearLayout content,
            int labelResource,
            String value,
            boolean selectable
    ) {
        if (value == null || value.isEmpty()) {
            return;
        }
        TextView label = new TextView(this);
        label.setText(labelResource);
        label.setTextColor(getColor(R.color.ink_muted));
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.topMargin = dp(8);
        content.addView(label, labelParams);

        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(getColor(R.color.ink));
        text.setTextSize(15);
        text.setLineSpacing(0f, 1.2f);
        text.setTextIsSelectable(selectable);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.topMargin = dp(4);
        textParams.bottomMargin = dp(8);
        content.addView(text, textParams);
    }

    private void openSourceUrl(String supplied) {
        String url = CaptureSourceUrl.clean(supplied);
        if (url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.capture_url_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openSyncSettings() {
        startActivity(new Intent(this, CaptureAccountActivity.class));
    }

    private void syncAll() {
        refreshRecords();
    }

    private void renderSyncStatus(List<CaptureStore.CaptureRecord> records) {
        if (CaptureAccountSession.hasAccount(this)) {
            syncAllButton.setEnabled(true);
            String error = CaptureAccountSession.preferences(this).getString("sync_error", "");
            syncStatus.setText("账号：" + CaptureAccountSession.username(this) + (error.isEmpty() ? " · 自动同步已开启"
                    : "login_required".equals(error) ? " · 登录已过期，请重新登录" : " · 同步待重试，可点击刷新"));
            return;
        }
        syncAllButton.setEnabled(true);
        syncStatus.setText("未登录 · 新记录仅保存在本机");
        syncStatus.setTextColor(getColor(R.color.ink_muted));
    }

    private int syncStateLabel(String state) {
        if (CaptureStore.SYNC_PENDING.equals(state)) {
            return R.string.capture_sync_item_pending;
        }
        if (CaptureStore.SYNC_FAILED.equals(state)) {
            return R.string.capture_sync_item_failed;
        }
        if (CaptureStore.SYNC_SYNCED.equals(state)) {
            return R.string.capture_sync_item_synced;
        }
        return R.string.capture_sync_item_local;
    }

    private void clearThumbnails() {
        for (Bitmap thumbnail : thumbnails) {
            if (thumbnail != null && !thumbnail.isRecycled()) {
                thumbnail.recycle();
            }
        }
        thumbnails.clear();
    }

    private static void setOptionalText(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(value);
        }
    }

    private int kindLabel(String kind) {
        if ("thought".equals(kind)) {
            return R.string.capture_kind_thought;
        }
        if ("todo".equals(kind)) {
            return R.string.capture_kind_todo;
        }
        return R.string.capture_kind_comment;
    }

    private int sourceTypeLabel(String type) {
        if ("process_text".equals(type)) {
            return R.string.capture_source_process_text;
        }
        if ("share_text".equals(type)) {
            return R.string.capture_source_share_text;
        }
        if ("share_image".equals(type)) {
            return R.string.capture_source_share_image;
        }
        if ("quick_note".equals(type)) {
            return R.string.capture_source_quick_note;
        }
        return R.string.capture_source_screen;
    }

    private static String ellipsize(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum) + "…";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ThumbnailThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "capture-thumbnails");
            thread.setDaemon(true);
            return thread;
        }
    }
}
