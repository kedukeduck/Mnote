package com.codex.mnote;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Deliberately generic notifications: no source link, title, declaration or transcript on the lock screen. */
final class VideoTranscriptNotifications {
    static final String CHANNEL="video_transcript_results";
    private static final String TAG="mnote-video-result-";
    static boolean show(Context context,VideoTranscriptTasks.Task task){
        if(!task.scope.equals(CaptureAccountSession.scope(context)))return false;
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return false;
        NotificationChannel channel=new NotificationChannel(CHANNEL,"视频文字稿结果",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("视频文字稿解析成功或失败时提醒");channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(channel);
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        Intent open=new Intent().setClassName(context.getPackageName(),"com.codex.mnote.VideoTranscriptTasksActivity")
                .putExtra("task_id",task.id).putExtra("account_scope",task.scope).setData(android.net.Uri.parse("mnote://transcript-task/"+task.scope+"/"+task.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        boolean success=task.completed()&&!"empty_transcript".equals(task.problemCode);
        NotificationCompat.Builder builder=new NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(success?"视频文字稿已完成":"视频文字稿解析未完成")
                .setContentText(success?"点此查看结果；已有记录不会被自动修改。":"点此查看原因和后续操作；已有记录保持不变。")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_STATUS).setOnlyAlertOnce(true);
        try{manager.notify(TAG+task.scope+"-"+task.id,0,builder.build());return true;}catch(SecurityException ignored){return false;}
    }
    static void cancelTask(Context context,String scope,String id){NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(TAG+scope+"-"+id,0);}
    static void cancelScope(Context context,String scope){
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)return;
        for(android.service.notification.StatusBarNotification notification:manager.getActiveNotifications())
            if(notification.getTag()!=null&&notification.getTag().startsWith(TAG+scope+"-"))manager.cancel(notification.getTag(),notification.getId());
    }
}
