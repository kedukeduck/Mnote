package com.codex.mnote;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.util.AtomicFile;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local-only crash evidence. No messages, user text, URLs, credentials or automatic uploads. */
public final class MnoteApplication extends Application {
    static final String REPORT="last-crash.txt";
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        if(previous instanceof CrashHandler) previous=((CrashHandler)previous).previous;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(getApplicationContext(),previous));
    }
    static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        final Context context;
        final Thread.UncaughtExceptionHandler previous;
        CrashHandler(Context context,Thread.UncaughtExceptionHandler previous) {this.context=context;this.previous=previous;}
        @Override public void uncaughtException(Thread thread,Throwable error) {
            try {write(context,summary(error));} catch(Throwable ignored) { /* Never obstruct Android's crash handler. */ }
            finally {
                if(previous!=null) previous.uncaughtException(thread,error);
                else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
            }
        }
    }
    static String summary(Throwable error) {
        StringBuilder text=new StringBuilder("Mnote local crash\ntime_ms=").append(System.currentTimeMillis())
                .append("\nAndroid=").append(Build.VERSION.SDK_INT).append('\n');
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        int causes=0;
        while(error!=null && causes++<4 && seen.add(error)) {
            text.append(error.getClass().getName()).append('\n');
            StackTraceElement[] frames=error.getStackTrace();
            for(int i=0;i<Math.min(frames.length,18);i++) {
                StackTraceElement frame=frames[i];
                text.append(" at ").append(identifier(frame.getClassName())).append('.')
                        .append(identifier(frame.getMethodName())).append(':').append(frame.getLineNumber()).append('\n');
            }
            error=error.getCause();
        }
        return text.substring(0,Math.min(text.length(),12_000));
    }
    private static String identifier(String value) {
        String clean=value.replaceAll("[^a-zA-Z0-9_.$<>]","?");
        return clean.substring(0,Math.min(clean.length(),100));
    }
    static void write(Context context,String summary) throws IOException {
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),REPORT));
        FileOutputStream output=null;
        try {
            output=file.startWrite();
            String version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;
            output.write(("version="+version+"\n"+summary).getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
        } catch(Exception error) {if(output!=null) file.failWrite(output);throw new IOException("diagnostic_write_failed");}
    }
    static String diagnostic(Context context) {
        StringBuilder text=new StringBuilder("仅保存在本机，不含记录内容或账号信息；复制后可发送给开发者。\n\n");
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),REPORT));
        try(InputStream input=file.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024];int size;
            while(bytes.size()<24_000 && (size=input.read(buffer,0,Math.min(buffer.length,24_000-bytes.size())))>0)
                bytes.write(buffer,0,size);
            text.append(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
        catch(IOException ignored) {text.append("尚未记录到 Java 异常堆栈。这不代表没有发生闪退。\n");}
        if(Build.VERSION.SDK_INT>=30) {
            try {
                ActivityManager manager=context.getSystemService(ActivityManager.class);
                if(manager!=null) for(ApplicationExitInfo info:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,3))
                    text.append("\n系统退出记录：time_ms=").append(info.getTimestamp()).append(" reason=").append(info.getReason())
                            .append(" status=").append(info.getStatus());
            } catch(RuntimeException ignored) {text.append("\n系统退出记录暂不可用。");}
        }
        return text.toString();
    }
    static void showDiagnostic(Activity activity) {
        String report=diagnostic(activity);
        new AlertDialog.Builder(activity).setTitle("本机异常诊断").setMessage(report)
                .setPositiveButton("复制诊断",(dialog,which)->{
                    ClipboardManager clipboard=activity.getSystemService(ClipboardManager.class);
                    if(clipboard!=null) {clipboard.setPrimaryClip(ClipData.newPlainText("Mnote 异常诊断",report));
                        Toast.makeText(activity,"已复制诊断，不含笔记内容",Toast.LENGTH_SHORT).show();}
                }).setNegativeButton("关闭",null).show();
    }
}
