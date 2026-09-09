package com.codex.mnote;

import android.content.*;

/** Called only from the foreground editor after an explicit opt-in. Never reads later clip items. */
final class QuickNoteClipboard {
    static String first(Context context) throws Exception {
        ClipboardManager clipboard=context.getSystemService(ClipboardManager.class);
        ClipData data=clipboard==null ? null : clipboard.getPrimaryClip();
        if(data==null || data.getItemCount()==0) throw new Exception("剪贴板为空或系统暂不允许读取，请先复制文字再试。");
        ClipData.Item first=data.getItemAt(0);
        CharSequence text=first.getText();
        if(text==null && first.getUri()!=null && !CaptureSourceUrl.clean(first.getUri().toString()).isEmpty()) text=first.getUri().toString();
        if(text==null || text.toString().trim().isEmpty()) throw new Exception("剪贴板第一条不是可用文字或网页链接；不会读取后面的条目。");
        if(text.length()>100_000) throw new Exception("剪贴板第一条超过 10 万字，本次未截断或摘录。");
        return text.toString();
    }
}
