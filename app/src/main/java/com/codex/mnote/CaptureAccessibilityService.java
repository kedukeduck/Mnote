package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Provides one screenshot only after an explicit button or Quick Settings
 * action. The service neither requests nor reads the active UI tree.
 */
public final class CaptureAccessibilityService extends AccessibilityService {
    enum Failure {
        UNSUPPORTED,
        NOT_CONNECTED,
        IN_PROGRESS,
        SECURE_WINDOW,
        TOO_SOON,
        INVALID_DISPLAY,
        ACCESS_REVOKED,
        INTERNAL,
        STORAGE
    }

    interface CaptureCallback {
        void onCaptured(File draft);

        void onFailure(Failure failure);
    }

    private static final Object INSTANCE_LOCK = new Object();
    private static WeakReference<CaptureAccessibilityService> activeService =
            new WeakReference<>(null);

    private final ExecutorService storageExecutor =
            Executors.newSingleThreadExecutor(new CaptureThreadFactory());
    private boolean captureInProgress;

    static boolean isReady() {
        synchronized (INSTANCE_LOCK) {
            return activeService.get() != null;
        }
    }

    static boolean isConfigured(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(
                context,
                CaptureAccessibilityService.class
        );
        List<AccessibilityServiceInfo> services = manager
                .getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                );
        for (AccessibilityServiceInfo info : services) {
            if (info.getResolveInfo() == null
                    || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            android.content.pm.ServiceInfo resolved =
                    info.getResolveInfo().serviceInfo;
            ComponentName actual = new ComponentName(
                    resolved.packageName,
                    resolved.name
            );
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    static void captureOnce(CaptureCallback callback) {
        if (callback == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onFailure(Failure.UNSUPPORTED);
            return;
        }
        CaptureAccessibilityService service;
        synchronized (INSTANCE_LOCK) {
            service = activeService.get();
        }
        if (service == null) {
            callback.onFailure(Failure.NOT_CONNECTED);
            return;
        }
        service.capture(callback);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // We need no accessibility events and never retrieve window content.
            info.eventTypes = 0;
            info.flags &= ~AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags &= ~AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        synchronized (INSTANCE_LOCK) {
            activeService = new WeakReference<>(this);
        }
        CaptureQuickSettingsTileService.requestRefresh(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately empty: this service is a user-triggered screenshot gate.
    }

    @Override
    public void onInterrupt() {
        // No ongoing accessibility interaction to interrupt.
    }

    @Override
    public void onDestroy() {
        synchronized (INSTANCE_LOCK) {
            if (activeService.get() == this) {
                activeService.clear();
            }
        }
        storageExecutor.shutdown();
        CaptureQuickSettingsTileService.requestRefresh(this);
        super.onDestroy();
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private void capture(CaptureCallback callback) {
        synchronized (this) {
            if (captureInProgress) {
                callback.onFailure(Failure.IN_PROGRESS);
                return;
            }
            captureInProgress = true;
        }
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            handleScreenshot(result, callback);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            finishFailure(mapFailure(errorCode), callback);
                        }
                    }
            );
        } catch (SecurityException | IllegalStateException error) {
            finishFailure(Failure.ACCESS_REVOKED, callback);
        } catch (RuntimeException error) {
            finishFailure(Failure.INTERNAL, callback);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private void handleScreenshot(
            ScreenshotResult result,
            CaptureCallback callback
    ) {
        Bitmap software = null;
        HardwareBuffer buffer = result == null ? null : result.getHardwareBuffer();
        try {
            if (buffer != null) {
                Bitmap wrapped = Bitmap.wrapHardwareBuffer(
                        buffer,
                        result.getColorSpace()
                );
                if (wrapped != null) {
                    software = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                    wrapped.recycle();
                }
            }
        } catch (OutOfMemoryError | RuntimeException error) {
            software = null;
        } finally {
            if (buffer != null) {
                buffer.close();
            }
        }
        if (software == null) {
            finishFailure(Failure.INTERNAL, callback);
            return;
        }
        Bitmap captured = software;
        storageExecutor.execute(() -> {
            try {
                File draft = CaptureStore.writeDraftBitmap(this, captured);
                getMainExecutor().execute(() -> finishSuccess(draft, callback));
            } catch (IOException | RuntimeException error) {
                getMainExecutor().execute(
                        () -> finishFailure(Failure.STORAGE, callback)
                );
            } finally {
                captured.recycle();
            }
        });
    }

    private void finishSuccess(File draft, CaptureCallback callback) {
        synchronized (this) {
            captureInProgress = false;
        }
        callback.onCaptured(draft);
    }

    private void finishFailure(Failure failure, CaptureCallback callback) {
        synchronized (this) {
            captureInProgress = false;
        }
        callback.onFailure(failure);
    }

    private static Failure mapFailure(int errorCode) {
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            return Failure.TOO_SOON;
        }
        if (errorCode == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY
                || errorCode == ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) {
            return Failure.INVALID_DISPLAY;
        }
        if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
            return Failure.ACCESS_REVOKED;
        }
        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
            return Failure.SECURE_WINDOW;
        }
        return Failure.INTERNAL;
    }

    private static final class CaptureThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "capture-storage");
            thread.setDaemon(true);
            return thread;
        }
    }
}
