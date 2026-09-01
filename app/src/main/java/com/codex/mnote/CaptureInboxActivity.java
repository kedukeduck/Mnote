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
    private final BroadcastReceiver syncChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CaptureSyncWorker.ACTION_SYNC_CHANGED.equals(intent.getAction())) {
                renderRecords();
            }
        }
    };

    private TextView accessStatus;
    private Button captureButton;
    private Button addTileButton;
    private TextView recordCount;
    private TextView syncStatus;
    private Button syncAllButton;
    private LinearLayout recordsContainer;
    private View emptyState;
    private int renderGeneration;
    private boolean destroyed;
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!syncReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    syncChangedReceiver,
                    new IntentFilter(CaptureSyncWorker.ACTION_SYNC_CHANGED),
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
        clearThumbnails();
        super.onDestroy();
    }

    private void bindViews() {
        accessStatus = findViewById(R.id.capture_access_status);
        captureButton = findViewById(R.id.capture_start_button);
        addTileButton = findViewById(R.id.capture_add_tile_button);
        recordCount = findViewById(R.id.capture_record_count);
        syncStatus = findViewById(R.id.capture_sync_status);
        syncAllButton = findViewById(R.id.capture_sync_all_button);
        recordsContainer = findViewById(R.id.capture_records);
        emptyState = findViewById(R.id.capture_empty);
    }

    private void bindActions() {
        captureButton.setOnClickListener(view -> startCaptureOrSetup());
        addTileButton.setOnClickListener(view -> requestTile());
        findViewById(R.id.capture_quick_note_button).setOnClickListener(
                view -> startActivity(
                        new Intent(this, CaptureEditorActivity.class)
                )
        );
        findViewById(R.id.capture_sync_settings_button).setOnClickListener(
                view -> openSyncSettings()
        );
        syncAllButton.setOnClickListener(view -> syncAll());
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
            captureButton.setText(R.string.capture_test_capture_button);
        } else if (configured) {
            accessStatus.setText(R.string.capture_access_connecting_detail);
            accessStatus.setTextColor(getColor(R.color.ink_muted));
            captureButton.setText(R.string.capture_retry_connection_button);
        } else {
            accessStatus.setText(R.string.capture_access_disabled_detail);
            accessStatus.setTextColor(getColor(R.color.danger));
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
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    R.string.capture_error_open_accessibility_settings,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void requestTile() {
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
        manager.requestAddTileService(
                new ComponentName(this, CaptureQuickSettingsTileService.class),
                getString(R.string.capture_tile_label),
                Icon.createWithResource(this, R.drawable.ic_capture_tile),
                getMainExecutor(),
                result -> {
                    addTileButton.setEnabled(true);
                    int message;
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                        message = R.string.capture_tile_added;
                    } else if (result
                            == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        message = R.string.capture_tile_already_added;
                    } else {
                        message = R.string.capture_tile_not_added;
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
        );
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
        TextView kind = card.findViewById(R.id.capture_item_kind);
        TextView time = card.findViewById(R.id.capture_item_time);
        TextView comment = card.findViewById(R.id.capture_item_comment);
        TextView source = card.findViewById(R.id.capture_item_source);
        TextView exactText = card.findViewById(R.id.capture_item_exact_text);
        TextView sync = card.findViewById(R.id.capture_item_sync_status);
        ImageView image = card.findViewById(R.id.capture_item_image);

        kind.setText(kindLabel(record.kind));
        time.setText(DateFormat.format(
                "yyyy-MM-dd HH:mm",
                new Date(record.createdAt)
        ));
        setOptionalText(comment, record.comment);
        String sourceLine = getString(
                R.string.capture_item_source_format,
                record.fidelityLevel,
                getString(sourceTypeLabel(record.sourceType)),
                record.sourcePackage.isEmpty()
                        ? getString(R.string.capture_source_unknown)
                        : record.sourcePackage
        );
        source.setText(sourceLine);
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
                kind.getText() + "，" + time.getText() + "，" + sourceLine
                        + "，" + sync.getText() + "，"
                        + getString(R.string.capture_detail_open_hint)
        );
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> showRecordDetail(record));
    }

    private void showRecordDetail(CaptureStore.CaptureRecord record) {
        if (!record.hasImage) {
            presentRecordDetail(record, null);
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
                if (destroyed || isFinishing()) {
                    if (image != null) {
                        image.recycle();
                    }
                    return;
                }
                presentRecordDetail(record, image);
            });
        });
    }

    private void presentRecordDetail(
            CaptureStore.CaptureRecord record,
            Bitmap image
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
                        : record.sourcePackage,
                record.fidelityLevel,
                record.aiAccess,
                getString(syncStateLabel(record.syncState))
        );
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

    private void openSyncSettings() {
        startActivity(new Intent(this, CaptureSyncSettingsActivity.class));
    }

    private void syncAll() {
        if (!CaptureSyncPreferences.isConfigured(this)) {
            Toast.makeText(
                    this,
                    R.string.capture_sync_open_settings_first,
                    Toast.LENGTH_LONG
            ).show();
            openSyncSettings();
            return;
        }
        CaptureStore.markAllForSync(this);
        CaptureSyncWorker.enqueue(this);
        Toast.makeText(
                this,
                R.string.capture_sync_queued,
                Toast.LENGTH_LONG
        ).show();
        renderRecords();
    }

    private void renderSyncStatus(List<CaptureStore.CaptureRecord> records) {
        boolean configured = CaptureSyncPreferences.isConfigured(this);
        syncAllButton.setEnabled(configured);
        if (!configured) {
            syncStatus.setText(R.string.capture_sync_status_disabled);
            syncStatus.setTextColor(getColor(R.color.ink_muted));
            return;
        }
        int pending = 0;
        int failed = 0;
        int synced = 0;
        for (CaptureStore.CaptureRecord record : records) {
            if (CaptureStore.SYNC_PENDING.equals(record.syncState)) {
                pending++;
            } else if (CaptureStore.SYNC_FAILED.equals(record.syncState)) {
                failed++;
            } else if (CaptureStore.SYNC_SYNCED.equals(record.syncState)) {
                synced++;
            }
        }
        syncStatus.setText(getString(
                R.string.capture_sync_status_format,
                pending,
                failed,
                synced
        ));
        syncStatus.setTextColor(
                failed > 0 ? getColor(R.color.danger) : getColor(R.color.success)
        );
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
