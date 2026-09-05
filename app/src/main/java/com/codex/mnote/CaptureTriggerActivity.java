package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;

import java.io.File;

/** Transparent, user-visible bridge between a tile click and one screenshot. */
public final class CaptureTriggerActivity extends Activity {
    private static final long SHADE_SETTLE_MILLIS = 350L;
    private static final long SERVICE_CONNECT_TIMEOUT_MILLIS = 3_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable beginCaptureTask = this::beginCapture;
    private boolean captureRequested;
    private boolean destroyed;
    private boolean resumed;
    private boolean windowFocused;
    private boolean dialogShowing;
    private long resumeTime;

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
        resumed = true;
        resumeTime = SystemClock.uptimeMillis();
        scheduleCapture();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        scheduleCapture();
    }

    private void scheduleCapture() {
        handler.removeCallbacks(beginCaptureTask);
        // onResume can arrive while Quick Settings still covers the source.
        // Wait for focus, then give the panel's closing animation time to end.
        if (canCapture()) {
            handler.postDelayed(beginCaptureTask, SHADE_SETTLE_MILLIS);
        }
    }

    private boolean canCapture() {
        return resumed && windowFocused && !destroyed && !isFinishing()
                && !captureRequested && !dialogShowing;
    }

    @Override
    protected void onPause() {
        resumed = false;
        windowFocused = false;
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
        if (!canCapture()) {
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
            if (CaptureAccessibilityService.isConfigured(this)
                    && SystemClock.uptimeMillis() - resumeTime
                    < SERVICE_CONNECT_TIMEOUT_MILLIS) {
                // First tile use may race the enabled service's connection.
                // Continue the same request rather than requiring a second tap.
                handler.postDelayed(beginCaptureTask, 250L);
                return;
            }
            showAccessibilitySetup();
            return;
        }
        captureRequested = true;
        CaptureAccessibilityService.captureOnce(
                new CaptureAccessibilityService.CaptureCallback() {
                    @Override
                    public void onCaptured(File draft) {
                        if (destroyed || isFinishing() || !resumed || !windowFocused) {
                            CaptureStore.discardDraft(
                                    CaptureTriggerActivity.this,
                                    draft
                            );
                            if (!destroyed) {
                                finish();
                            }
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
                        if (destroyed || isFinishing() || !resumed) {
                            if (!destroyed) {
                                finish();
                            }
                            return;
                        }
                        captureRequested = false;
                        showBlockingMessage(messageFor(failure), false);
                    }
                }
        );
    }

    private void showAccessibilitySetup() {
        dialogShowing = true;
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
                                dialogShowing = false;
                                resumeTime = SystemClock.uptimeMillis();
                                scheduleCapture();
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
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            // Setup changes the foreground source. Require a fresh user click
            // in the intended app instead of capturing Settings on return.
            finish();
        } catch (RuntimeException error) {
            showBlockingMessage(
                    R.string.capture_error_open_accessibility_settings,
                    false
            );
        }
    }

    private void showBlockingMessage(int message, boolean offerSettings) {
        dialogShowing = true;
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
