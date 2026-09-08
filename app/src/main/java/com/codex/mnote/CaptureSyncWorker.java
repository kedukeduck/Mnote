package com.codex.mnote;

import android.content.Context;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Compatibility bridge for jobs scheduled before account-based sync. */
public final class CaptureSyncWorker extends Worker {
    static final String UNIQUE_WORK = "capture-server-upload-v1";
    static final String ACTION_SYNC_CHANGED = "com.codex.mnote.action.CAPTURE_SYNC_CHANGED";
    public CaptureSyncWorker(Context context, WorkerParameters params) { super(context, params); }
    static void enqueue(Context context) { CaptureAccountSync.enqueue(context); }
    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK);
        CaptureAccountSync.cancel(context);
    }
    @Override public Result doWork() {
        // Old token-bound jobs must never run under a subsequently selected account.
        enqueue(getApplicationContext());
        return Result.success();
    }
}
