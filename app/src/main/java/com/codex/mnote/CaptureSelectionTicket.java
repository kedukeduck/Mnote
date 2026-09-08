package com.codex.mnote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.UUID;

/** A single-use, five-second in-memory handoff. PendingIntents carry no captured text. */
final class CaptureSelectionTicket {
    static final String EXTRA="mnote.selection.ticket";
    private static final Handler handler=new Handler(Looper.getMainLooper());
    private static String token="", scope="";
    private static long created;
    private static CaptureSelectedText value=CaptureSelectedText.EMPTY;
    static synchronized String put(Context context,CaptureSelectedText selected) {
        clear(); if(!selected.found()) return "";
        token=UUID.randomUUID().toString(); scope=CaptureAccountSession.scope(context);
        created=SystemClock.uptimeMillis(); value=selected;
        String scheduled=token;
        handler.postDelayed(()-> { synchronized(CaptureSelectionTicket.class) { if(scheduled.equals(token)) clear(); } },5_000);
        return token;
    }
    static synchronized CaptureSelectedText take(Context context,String supplied) {
        if(supplied==null || !supplied.equals(token)) return CaptureSelectedText.EMPTY;
        CaptureSelectedText result=SystemClock.uptimeMillis()-created<=5_000 && scope.equals(CaptureAccountSession.scope(context))
                ? value : CaptureSelectedText.EMPTY;
        clear(); return result;
    }
    static synchronized void clear() { token=""; scope=""; value=CaptureSelectedText.EMPTY; handler.removeCallbacksAndMessages(null); }
}
