package com.codex.mnote;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** WorkManager owns offline/reboot/process-death recovery; the server owns the actual ASR job. */
public final class VideoTranscriptWorker extends Worker {
    public VideoTranscriptWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork(){
        Context context=getApplicationContext();String scope=getInputData().getString(VideoTranscriptTasks.WORK_SCOPE);
        if(scope==null||!scope.equals(CaptureAccountSession.scope(context)))return Result.success();
        try{return VideoTranscriptTasks.reconcile(context,scope,VideoTranscriptClient::request)?Result.retry():Result.success();}
        catch(Exception error){return VideoTranscriptTasks.isAuthentication(VideoTranscriptTasks.safeCode(error))?Result.failure():Result.retry();}
    }
}
