package com.codex.mnote;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,30,35})
public class MnoteApplicationTest {
    @Test public void diagnosticsExcludeMessagesAndOnlyDelegateAfterRecording() {
        Context context=RuntimeEnvironment.getApplication();
        Throwable error=new IllegalStateException("secret-token https://private.example 用户笔记",
                new RuntimeException("clipboard text"));
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.codex.mnote.QuickNoteActivity","save","private-note",123)});
        int[] calls={0};
        MnoteApplication.CrashHandler handler=new MnoteApplication.CrashHandler(context,(thread,actual)->{
            calls[0]++;assertSame(error,actual);
            assertTrue(MnoteApplication.diagnostic(context).contains("QuickNoteActivity.save:123"));
        });
        handler.uncaughtException(Thread.currentThread(),error);
        assertEquals(1,calls[0]);String report=MnoteApplication.diagnostic(context);
        for(String secret:new String[]{"secret-token","private.example","用户笔记","clipboard text","private-note"})
            assertFalse(report,report.contains(secret));
    }
    @Test public void reportIsBoundedAndCyclicCausesTerminate() {
        Throwable a=new RuntimeException(), b=new RuntimeException();a.initCause(b);b.initCause(a);
        StackTraceElement[] frames=new StackTraceElement[1000];
        java.util.Arrays.fill(frames,new StackTraceElement("a".repeat(1000),"method","file",1));a.setStackTrace(frames);
        String report=MnoteApplication.summary(a);assertTrue(report.length()<=12000);
        assertTrue(report.contains("RuntimeException"));
    }
    @Test public void emptyDiagnosticDoesNotClaimNoCrashOccurred() {
        assertTrue(MnoteApplication.diagnostic(RuntimeEnvironment.getApplication()).contains("不代表没有发生闪退"));
    }
}
