package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;

import java.io.File;

/** Transparent, user-visible bridge between a tile click and one screenshot. */
public final class CaptureTriggerActivity extends Activity {
    private static final long SHADE_SETTLE_MILLIS = 240L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean captureRequested;
    private boolean waitingForSettings;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setDimAmount(0f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        CaptureStore.cleanupStaleDrafts(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForSettings) {
            waitingForSettings = false;
            captureRequested = false;
        }
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::beginCapture, SHADE_SETTLE_MILLIS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void beginCapture() {
        if (destroyed || isFinishing() || captureRequested) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showBlockingMessage(
                    R.string.capture_error_unsupported,
                    false
            );
            return;
        }
        if (!CaptureAccessibilityService.isReady()) {
            showAccessibilitySetup();
            return;
        }
        captureRequested = true;
        CaptureAccessibilityService.captureOnce(
                new CaptureAccessibilityService.CaptureCallback() {
                    @Override
                    public void onCaptured(File draft) {
                        if (destroyed || isFinishing()) {
                            CaptureStore.discardDraft(
                                    CaptureTriggerActivity.this,
                                    draft
                            );
                            return;
                        }
                        Intent editor = CaptureEditorActivity.forScreenshot(
                                CaptureTriggerActivity.this,
                                draft
                        );
                        startActivity(editor);
                        finish();
                        overridePendingTransition(0, 0);
                    }

                    @Override
                    public void onFailure(
                            CaptureAccessibilityService.Failure failure
                    ) {
                        if (destroyed || isFinishing()) {
                            return;
                        }
                        captureRequested = false;
                        showBlockingMessage(messageFor(failure), false);
                    }
                }
        );
    }

    private void showAccessibilitySetup() {
        boolean configured = CaptureAccessibilityService.isConfigured(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.capture_accessibility_dialog_title)
                .setMessage(
                        configured
                                ? R.string.capture_accessibility_connecting_detail
                                : R.string.capture_accessibility_dialog_detail
                )
                .setPositiveButton(
                        configured
                                ? R.string.capture_retry
                                : R.string.capture_open_accessibility_settings,
                        (dialog, which) -> {
                            if (configured) {
                                captureRequested = false;
                                handler.postDelayed(this::beginCapture, 500L);
                            } else {
                                openAccessibilitySettings();
                            }
                        }
                )
                .setNegativeButton(R.string.capture_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            waitingForSettings = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException error) {
            waitingForSettings = false;
            showBlockingMessage(
                    R.string.capture_error_open_accessibility_settings,
                    false
            );
        }
    }

    private void showBlockingMessage(int message, boolean offerSettings) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.capture_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.capture_confirm, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish());
        if (offerSettings) {
            builder.setNeutralButton(
                    R.string.capture_open_accessibility_settings,
                    (dialog, which) -> openAccessibilitySettings()
            );
        }
        builder.show();
    }

    private static int messageFor(CaptureAccessibilityService.Failure failure) {
        switch (failure) {
            case TOO_SOON:
            case IN_PROGRESS:
                return R.string.capture_error_too_soon;
            case SECURE_WINDOW:
                return R.string.capture_error_secure_window;
            case INVALID_DISPLAY:
                return R.string.capture_error_invalid_display;
            case ACCESS_REVOKED:
            case NOT_CONNECTED:
                return R.string.capture_error_access_revoked;
            case STORAGE:
                return R.string.capture_error_storage;
            case UNSUPPORTED:
                return R.string.capture_error_unsupported;
            case INTERNAL:
            default:
                return R.string.capture_error_internal;
        }
    }
}
