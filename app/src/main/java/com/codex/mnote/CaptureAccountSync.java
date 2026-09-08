package com.codex.mnote;

import android.content.Context;
import android.content.Intent;
import androidx.work.*;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** One serialized push/delete/pull cycle, always bound to one stable account. */
public final class CaptureAccountSync extends Worker {
    private static final String ONCE = "mnote-account-sync";
    private static final String PERIODIC = "mnote-account-periodic";
    public CaptureAccountSync(Context context, WorkerParameters params) { super(context, params); }
    static void enqueue(Context context) {
        if (!CaptureAccountSession.hasAccount(context)) return;
        Constraints network = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE,
                new OneTimeWorkRequest.Builder(CaptureAccountSync.class).setConstraints(network)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build());
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(CaptureAccountSync.class, 15, TimeUnit.MINUTES).setConstraints(network).build());
    }
    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONCE);
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC);
    }
    @Override public Result doWork() {
        try { run(getApplicationContext()); return Result.success(); }
        catch (Exception error) {
            String code = error.getMessage();
            if ("http_401".equals(code) || "http_403".equals(code) || "login_required".equals(code)) return Result.failure();
            return Result.retry();
        }
    }
    static int run(Context context) throws Exception {
        synchronized (CaptureAccountSession.LOCK) {
            try {
                CaptureSyncPreferences.Config config = CaptureAccountSession.config(context);
                String scope = config.accountKey;
                // Pull first: remote tombstones must suppress any queued stale upload.
                int changes = CaptureRemoteCache.pull(context, scope, CaptureSyncReader.forConfig(config),
                        () -> scope.equals(CaptureAccountSession.scope(context)));
                for (String id : CaptureDeletionStore.pending(context)) {
                    try {
                        JSONObject current = CaptureAccountHttp.request(config.baseUrl, "GET", "/v1/captures/" + id, config.writeToken, null, null);
                        CaptureAccountHttp.request(config.baseUrl, "DELETE", "/v1/captures/" + id,
                                config.writeToken, null, current.getInt("revision"));
                    } catch (IOException error) {
                        if (!"http_404".equals(error.getMessage())) throw error;
                    }
                    CaptureDeletionStore.acknowledged(context, id);
                }
                Set<String> deleted = CaptureDeletionStore.hidden(context);
                deleted.addAll(CaptureRemoteCache.deletedIds(context, scope));
                for (CaptureStore.CaptureRecord record : CaptureStore.list(context, Integer.MAX_VALUE)) {
                    if (deleted.contains(record.id) || CaptureStore.SYNC_SYNCED.equals(record.syncState)
                            || CaptureStore.SYNC_LOCAL_ONLY.equals(record.syncState)) continue;
                    try {
                        CaptureSyncUploader.UploadResult result = CaptureSyncUploader.upload(context, config, record);
                        CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_SYNCED, "", result.revision);
                    } catch (CaptureSyncUploader.UploadFailure error) {
                        CaptureStore.updateSyncState(context, record.id, CaptureStore.SYNC_FAILED, error.code, record.serverRevision);
                        throw error;
                    }
                }
                changes += CaptureRemoteCache.pull(context, scope, CaptureSyncReader.forConfig(config),
                        () -> scope.equals(CaptureAccountSession.scope(context)));
                CaptureAccountSession.preferences(context).edit().putString("sync_error", "")
                        .putLong("last_sync", System.currentTimeMillis()).apply();
                return changes;
            } catch (Exception error) {
                String code = error.getMessage();
                CaptureAccountSession.preferences(context).edit().putString("sync_error",
                        "http_401".equals(code) || "http_403".equals(code) || "login_required".equals(code)
                                ? "login_required" : "sync_failed").apply();
                throw error;
            } finally {
                context.sendBroadcast(new Intent(CaptureSyncWorker.ACTION_SYNC_CHANGED).setPackage(context.getPackageName()));
            }
        }
    }
}
