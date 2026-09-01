package com.codex.mnote;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A single unique, serial WorkManager chain that uploads local records one by one. */
public final class CaptureSyncWorker extends Worker {
    static final String UNIQUE_WORK = "capture-server-upload-v1";
    static final String ACTION_SYNC_CHANGED =
            "com.codex.mnote.action.CAPTURE_SYNC_CHANGED";

    public CaptureSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(appContext, workerParams);
    }

    static void enqueue(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                CaptureSyncWorker.class
        )
                .setConstraints(constraints)
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        15,
                        TimeUnit.SECONDS
                )
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_WORK,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request
                );
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(UNIQUE_WORK);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        final CaptureSyncPreferences.Config configuration;
        try {
            configuration = CaptureSyncPreferences.load(context);
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            markWaitingRecordsFailed(context, "configuration_unavailable");
            notifyChanged(context);
            return Result.failure();
        }

        List<CaptureStore.CaptureRecord> records = CaptureStore.list(
                context,
                Integer.MAX_VALUE
        );
        boolean retry = false;
        for (CaptureStore.CaptureRecord record : records) {
            if (isStopped()) {
                return Result.retry();
            }
            if (CaptureStore.SYNC_SYNCED.equals(record.syncState)
                    || CaptureStore.SYNC_LOCAL_ONLY.equals(record.syncState)) {
                continue;
            }
            CaptureStore.updateSyncState(
                    context,
                    record.id,
                    CaptureStore.SYNC_PENDING,
                    "",
                    record.serverRevision
            );
            try {
                CaptureSyncUploader.UploadResult uploaded =
                        CaptureSyncUploader.upload(context, configuration, record);
                CaptureStore.updateSyncState(
                        context,
                        record.id,
                        CaptureStore.SYNC_SYNCED,
                        "",
                        uploaded.revision
                );
            } catch (CaptureSyncUploader.UploadFailure error) {
                CaptureStore.updateSyncState(
                        context,
                        record.id,
                        CaptureStore.SYNC_FAILED,
                        error.code,
                        record.serverRevision
                );
                retry |= error.retriable;
            }
        }
        notifyChanged(context);
        return retry ? Result.retry() : Result.success();
    }

    private static void markWaitingRecordsFailed(Context context, String code) {
        for (CaptureStore.CaptureRecord record : CaptureStore.list(
                context,
                Integer.MAX_VALUE
        )) {
            if (CaptureStore.SYNC_PENDING.equals(record.syncState)
                    || CaptureStore.SYNC_FAILED.equals(record.syncState)) {
                CaptureStore.updateSyncState(
                        context,
                        record.id,
                        CaptureStore.SYNC_FAILED,
                        code,
                        record.serverRevision
                );
            }
        }
    }

    private static void notifyChanged(Context context) {
        context.sendBroadcast(
                new Intent(ACTION_SYNC_CHANGED).setPackage(context.getPackageName())
        );
    }
}
