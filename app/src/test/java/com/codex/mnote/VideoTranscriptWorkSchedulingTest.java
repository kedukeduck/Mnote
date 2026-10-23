package com.codex.mnote;

import android.content.Context;
import androidx.work.Configuration;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise the real WorkManager database and dependency policies, not a mocked enqueue call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,instrumentedPackages="com.codex.mnote")
public class VideoTranscriptWorkSchedulingTest {
    @Test public void newTriggersAppendMigrateAndFollowUpWithoutGrowingDuplicateTimers()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String scope="a".repeat(64);
        CaptureAccountSession.preferences(context).edit().clear().putString("scope",scope).putString("account_id","a".repeat(32)).commit();
        ExecutorService database=Executors.newSingleThreadExecutor();
        // Keep worker execution held so this test measures scheduling, not HTTP.
        java.util.concurrent.Executor held=task->{};
        WorkManager.initialize(context,new Configuration.Builder().setTaskExecutor(database).setExecutor(held).build());
        WorkManager work=WorkManager.getInstance(context);
        try {
            OneTimeWorkRequest legacy=new OneTimeWorkRequest.Builder(VideoTranscriptWorker.class)
                    .setInitialDelay(6,TimeUnit.HOURS).build();
            work.enqueueUniqueWork(VideoTranscriptScheduler.LEGACY_PREFIX+scope,ExistingWorkPolicy.KEEP,legacy).getResult().get(10,TimeUnit.SECONDS);
            VideoTranscriptScheduler.WorkBackend backend=new VideoTranscriptScheduler.WorkBackend();
            backend.schedule(context,scope);backend.schedule(context,scope);
            List<WorkInfo> queued=work.getWorkInfosForUniqueWork(VideoTranscriptScheduler.PREFIX+scope).get(10,TimeUnit.SECONDS);
            assertEquals("A second trigger must not be discarded by KEEP",2,queued.size());
            assertEquals(WorkInfo.State.CANCELLED,work.getWorkInfoById(legacy.getId()).get(10,TimeUnit.SECONDS).getState());
            assertEquals(1,work.getWorkInfosForUniqueWork(VideoTranscriptScheduler.PREFIX+scope+"-periodic").get(10,TimeUnit.SECONDS).size());
            backend.followUp(context,scope);
            assertEquals(2,work.getWorkInfosForUniqueWork(VideoTranscriptScheduler.PREFIX+scope).get(10,TimeUnit.SECONDS).size());

            work.cancelUniqueWork(VideoTranscriptScheduler.PREFIX+scope).getResult().get(10,TimeUnit.SECONDS);
            backend.followUp(context,scope);
            List<WorkInfo> resumed=work.getWorkInfosForUniqueWork(VideoTranscriptScheduler.PREFIX+scope).get(10,TimeUnit.SECONDS);
            WorkInfo timer=resumed.stream().filter(info->!info.getState().isFinished()).findFirst().orElseThrow();
            assertEquals(1,resumed.stream().filter(info->!info.getState().isFinished()).count());
            assertEquals(15_000,timer.getInitialDelayMillis());assertEquals(0,timer.getRunAttemptCount());
            backend.followUp(context,scope);
            assertEquals(1,work.getWorkInfosForUniqueWork(VideoTranscriptScheduler.PREFIX+scope).get(10,TimeUnit.SECONDS).stream().filter(info->!info.getState().isFinished()).count());
            backend.cancel(context,scope);
            assertTrue(work.getWorkInfosForUniqueWork(VideoTranscriptScheduler.PREFIX+scope).get(10,TimeUnit.SECONDS).stream().allMatch(info->info.getState().isFinished()));
        } finally {
            CaptureAccountSession.preferences(context).edit().clear().commit();
            work.cancelAllWork().getResult().get(10,TimeUnit.SECONDS);
            database.shutdown();database.awaitTermination(10,TimeUnit.SECONDS);
        }
    }
}
