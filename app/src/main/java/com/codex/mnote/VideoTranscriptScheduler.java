package com.codex.mnote;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Application-owned fast dispatch plus durable, fixed-cadence WorkManager recovery. */
final class VideoTranscriptScheduler {
    static final String PREFIX="mnote-video-tasks-v2-",LEGACY_PREFIX="mnote-video-tasks-";
    static final long FOLLOW_UP_SECONDS=15;
    private static final Constraints NETWORK=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    interface Runner {boolean reconcile(Context context,String scope)throws Exception;}
    interface Backend {
        void schedule(Context context,String scope);
        void followUp(Context context,String scope);
        void cancel(Context context,String scope);
    }
    enum Outcome {DONE,PENDING,BLOCKED}
    private static Dispatcher dispatcher=new Dispatcher(Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"mnote-transcript-dispatch");thread.setDaemon(true);return thread;
    }), (context,scope)->VideoTranscriptTasks.reconcile(context,scope,VideoTranscriptClient::request),new WorkBackend());

    private VideoTranscriptScheduler(){}
    static void schedule(Context context){dispatcher.schedule(context);}
    static void cancel(Context context,String scope){dispatcher.cancel(context,scope);}
    /** Called off the main thread after a bounded pass, not a WorkManager retry. */
    static void followUp(Context context,String scope){dispatcher.followUp(context,scope);}
    static Outcome run(Context context,String scope){return dispatcher.run(context,scope);}
    static synchronized Dispatcher installForTests(Dispatcher replacement){Dispatcher old=dispatcher;dispatcher=replacement;return old;}
    private static boolean valid(String scope){return scope!=null&&scope.matches("[a-f0-9]{64}");}
    private static boolean current(Context context,String scope){
        try{return valid(scope)&&CaptureAccountSession.hasAccount(context)&&scope.equals(CaptureAccountSession.scope(context));}
        catch(RuntimeException ignored){return false;}
    }
    static OneTimeWorkRequest request(String scope,long delaySeconds){
        return new OneTimeWorkRequest.Builder(VideoTranscriptWorker.class)
                .setInputData(new Data.Builder().putString(VideoTranscriptTasks.WORK_SCOPE,scope).build())
                .setConstraints(NETWORK).setInitialDelay(delaySeconds,TimeUnit.SECONDS).addTag(PREFIX+scope).build();
    }
    static PeriodicWorkRequest periodic(String scope){
        return new PeriodicWorkRequest.Builder(VideoTranscriptWorker.class,15,TimeUnit.MINUTES)
                .setInputData(new Data.Builder().putString(VideoTranscriptTasks.WORK_SCOPE,scope).build())
                .setConstraints(NETWORK).addTag(PREFIX+scope).build();
    }
    static final class WorkBackend implements Backend {
        @Override public void schedule(Context context,String scope){
            WorkManager work=WorkManager.getInstance(context);
            // A v1 KEEP worker may already be in hours of exponential backoff.
            // Do not append this repair behind that old delayed chain.
            work.cancelUniqueWork(LEGACY_PREFIX+scope);work.cancelUniqueWork(LEGACY_PREFIX+scope+"-periodic");
            work.enqueueUniqueWork(PREFIX+scope,ExistingWorkPolicy.APPEND_OR_REPLACE,request(scope,0));
            work.enqueueUniquePeriodicWork(PREFIX+scope+"-periodic",ExistingPeriodicWorkPolicy.KEEP,periodic(scope));
        }
        @Override public void followUp(Context context,String scope){
            WorkManager work=WorkManager.getInstance(context);
            try{
                // Reuse a queued successor instead of growing one polling chain
                // for every tap. A RUNNING worker itself is not a successor.
                for(WorkInfo info:work.getWorkInfosForUniqueWork(PREFIX+scope).get(3,TimeUnit.SECONDS))
                    if(info.getState()==WorkInfo.State.ENQUEUED||info.getState()==WorkInfo.State.BLOCKED)return;
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            catch(Exception ignored){/* Appending is safe even if inspection was unavailable. */}
            if(current(context,scope))work.enqueueUniqueWork(PREFIX+scope,ExistingWorkPolicy.APPEND_OR_REPLACE,request(scope,FOLLOW_UP_SECONDS));
        }
        @Override public void cancel(Context context,String scope){
            WorkManager work=WorkManager.getInstance(context);
            work.cancelUniqueWork(LEGACY_PREFIX+scope);work.cancelUniqueWork(LEGACY_PREFIX+scope+"-periodic");
            work.cancelUniqueWork(PREFIX+scope);work.cancelUniqueWork(PREFIX+scope+"-periodic");
        }
    }

    /** One queued kick per account; a kick arriving during a pass cannot be lost. */
    static final class Dispatcher {
        private final Executor executor;
        private final Runner runner;
        private final Backend backend;
        private final Object lock=new Object();
        private final LinkedHashMap<String,Pending> queued=new LinkedHashMap<>();
        private final Map<String,Long> generations=new HashMap<>();
        private boolean dispatching;
        private static final class Pending {
            final Context context;final String scope;final long generation;
            Pending(Context context,String scope,long generation){this.context=context;this.scope=scope;this.generation=generation;}
        }
        Dispatcher(Executor executor,Runner runner,Backend backend){this.executor=executor;this.runner=runner;this.backend=backend;}
        void schedule(Context context){
            Context app=context.getApplicationContext();String scope;
            try{scope=CaptureAccountSession.scope(app);}catch(RuntimeException ignored){return;}
            if(!current(app,scope))return;
            // Persisted recovery does not depend on this process remaining alive.
            try{backend.schedule(app,scope);}catch(RuntimeException ignored){}
            synchronized(lock){
                queued.put(scope,new Pending(app,scope,generations.getOrDefault(scope,0L)));
                dispatchLocked();
            }
        }
        private void dispatchLocked(){
            if(dispatching||queued.isEmpty())return;
            dispatching=true;
            try{executor.execute(this::next);}
            catch(RuntimeException ignored){dispatching=false;/* Durable recovery remains scheduled. */}
        }
        private void next(){
            Pending item;
            synchronized(lock){
                if(queued.isEmpty()){dispatching=false;return;}
                String key=queued.keySet().iterator().next();item=queued.remove(key);
            }
            try{
                if(alive(item)){
                    Outcome outcome=run(item.context,item.scope);
                    if(outcome==Outcome.PENDING&&alive(item))followUp(item.context,item.scope);
                }
            }finally{
                synchronized(lock){dispatching=false;dispatchLocked();}
            }
        }
        private boolean alive(Pending item){
            synchronized(lock){return generations.getOrDefault(item.scope,0L)==item.generation&&current(item.context,item.scope);}
        }
        Outcome run(Context context,String scope){
            if(!current(context,scope))return Outcome.BLOCKED;
            try{return runner.reconcile(context.getApplicationContext(),scope)?Outcome.PENDING:Outcome.DONE;}
            catch(Exception error){return VideoTranscriptTasks.isAuthentication(VideoTranscriptTasks.safeCode(error))?Outcome.BLOCKED:Outcome.PENDING;}
        }
        void followUp(Context context,String scope){
            Context app=context.getApplicationContext();
            if(current(app,scope))try{backend.followUp(app,scope);}catch(RuntimeException ignored){}
        }
        void cancel(Context context,String scope){
            if(!valid(scope))return;
            synchronized(lock){generations.put(scope,generations.getOrDefault(scope,0L)+1);queued.remove(scope);}
            try{backend.cancel(context.getApplicationContext(),scope);}catch(RuntimeException ignored){}
            VideoTranscriptNotifications.cancelScope(context,scope);
        }
    }
}
