package com.codex.mnote;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** WorkManager owns offline/reboot/process-death recovery; the server owns the actual ASR job. */
public final class VideoTranscriptWorker extends Worker {
    public VideoTranscriptWorker(Context context,WorkerParameters parameters){super(context,parameters);}
    @Override public Result doWork(){
        Context context=getApplicationContext();String scope=getInputData().getString(VideoTranscriptTasks.WORK_SCOPE);
        if(isStopped())return Result.success();
        VideoTranscriptScheduler.Outcome outcome=VideoTranscriptScheduler.run(context,scope);
        if(!isStopped()&&outcome==VideoTranscriptScheduler.Outcome.PENDING)VideoTranscriptScheduler.followUp(context,scope);
        // Pending ASR is normal, not a failed worker. Result.retry() would apply
        // exponential backoff and make saving seem to stop background parsing.
        // Authentication waits for login/app scheduling rather than a hot loop.
        return Result.success();
    }
}
