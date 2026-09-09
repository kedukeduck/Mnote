package com.codex.mnote;

import android.content.*;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk={30,35})
public class QuickNoteClipboardTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private void set(ClipData data) {context.getSystemService(ClipboardManager.class).setPrimaryClip(data);}
    @Test public void keepsFirstItemExactlyWithoutReadingLaterItems() throws Exception {
        ClipData data=ClipData.newPlainText("", "  第一条\n原样保留  ");
        data.addItem(new ClipData.Item("不要读取第二条"));set(data);
        assertEquals("  第一条\n原样保留  ",QuickNoteClipboard.first(context));
    }
    @Test public void firstImageDoesNotFallThroughToSecondTextOrResolveContentProvider() {
        ClipData data=ClipData.newRawUri("",Uri.parse("content://private.provider/image"));
        data.addItem(new ClipData.Item("不应读取"));set(data);
        assertThrows(Exception.class,()->QuickNoteClipboard.first(context));
    }
    @Test public void acceptsWebUriButNotIntentOrFileUris() throws Exception {
        set(ClipData.newRawUri("",Uri.parse("https://example.com/article?q=a#b")));
        assertEquals("https://example.com/article?q=a#b",QuickNoteClipboard.first(context));
        set(ClipData.newRawUri("",Uri.parse("file:///private/password.txt")));
        assertThrows(Exception.class,()->QuickNoteClipboard.first(context));
        set(ClipData.newIntent("",new Intent(Intent.ACTION_VIEW)));
        assertThrows(Exception.class,()->QuickNoteClipboard.first(context));
    }
    @Test public void emptyAndOversizedAreRejectedWithoutTruncating() {
        assertThrows(Exception.class,()->QuickNoteClipboard.first(context));
        set(ClipData.newPlainText(""," "));
        assertThrows(Exception.class,()->QuickNoteClipboard.first(context));
        set(ClipData.newPlainText("","a".repeat(100001)));
        assertThrows(Exception.class,()->QuickNoteClipboard.first(context));
    }
}
