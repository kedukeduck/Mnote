package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.SystemClock;
import android.view.accessibility.*;
import java.util.*;

/** Bounded page read, only while our identified transparent bridge exposes the external app. */
final class QuickNotePageContext {
    final CaptureSourceContext source;
    final int windowId;
    final String text,error;
    QuickNotePageContext(CaptureSourceContext source,int windowId,String text,String error) {
        this.source=source;this.windowId=windowId;this.text=text;this.error=error;
    }
    static QuickNotePageContext failure(String error) {return new QuickNotePageContext(CaptureSourceContext.EMPTY,-1,"",error);}
    boolean found() {return error.isEmpty();}
    boolean samePageWindow(QuickNotePageContext other) {
        return found() && other.found() && windowId==other.windowId && source.appPackage.equals(other.source.appPackage)
                && source.url.equals(other.source.url);
    }
    static QuickNotePageContext read(AccessibilityService service,int bridgeId,boolean withText) {
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);
        if(keyguard!=null && keyguard.isKeyguardLocked()) return failure("锁屏时不读取页面。");
        List<AccessibilityWindowInfo> windows=new ArrayList<>();
        try {
            if(android.os.Build.VERSION.SDK_INT>=33) {
                try {service.clearCache();} catch(RuntimeException ignored) { }
            }
            windows.addAll(service.getWindows());windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
            boolean skipped=false;
            for(AccessibilityWindowInfo window:windows) {
                if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD) return failure("键盘尚未收起，请稍后重试。");
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root=window.getRoot();
                boolean bridge=(bridgeId>=0 && window.getId()==bridgeId) || QuickNoteActivity.CONTEXT_TITLE.contentEquals(window.getTitle()==null ? "" : window.getTitle());
                if(root==null) {
                    if(bridge && !skipped) {skipped=true;continue;}
                    return failure("当前来源窗口不可读取，未向其他应用继续查找。");
                }
                try {
                    String pkg=root.getPackageName()==null ? "" : root.getPackageName().toString();
                    if(bridge && pkg.equals(service.getPackageName()) && !skipped) {skipped=true;continue;}
                    if(pkg.equals("com.android.systemui")) continue;
                    if(pkg.isEmpty() || pkg.equals(service.getPackageName())) return failure("未能确认外部来源页面，未读取或截取 Mnote 界面。");
                    CaptureSourceContext source=CaptureSourceContext.fromRoot(root);
                    return new QuickNotePageContext(source,window.getId(),withText ? visibleText(root,pkg) : "","");
                } finally {root.recycle();}
            }
            return failure("没有找到可确认的外部来源页面。");
        } catch(Exception error) {return failure(error.getMessage()==null ? "系统暂不允许读取页面。" : error.getMessage());}
        finally {for(AccessibilityWindowInfo window:windows) window.recycle();}
    }
    static String visibleText(AccessibilityNodeInfo root,String pkg) throws Exception {
        ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);
        List<String> parts=new ArrayList<>();int checked=0,queries=0,length=0;
        long deadline=SystemClock.uptimeMillis()+750;
        try {
            while(!nodes.isEmpty()) {
                AccessibilityNodeInfo node=nodes.removeFirst();
                try {
                    if(++checked>512 || SystemClock.uptimeMillis()>deadline) throw new Exception("页面过于复杂，本次未保留不完整的文字；你可以选择页面截图。");
                    if(!pkg.contentEquals(node.getPackageName()==null ? "" : node.getPackageName())
                            || node.isPassword() || node.isEditable() || !node.isVisibleToUser()
                            || (android.os.Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive())) continue;
                    CharSequence value=node.getText();
                    // Some apps expose their visible paragraph only as an accessibility label.
                    // Leaf-only fallback avoids repeating a container's aggregate description.
                    if((value==null || value.toString().trim().isEmpty()) && node.getChildCount()==0)
                        value=node.getContentDescription();
                    if(value!=null && !value.toString().trim().isEmpty()) {
                        length+=value.length()+(parts.isEmpty() ? 0 : 1);
                        if(length>CaptureContext.MAX_TEXT) throw new Exception("可读页面文字超过 4 万字，本次未截断保存；可以选择截图。");
                        parts.add(value.toString());
                    }
                    // Depth-first document order keeps nested paragraphs before following siblings.
                    for(int i=node.getChildCount()-1;i>=0;i--) {
                        if(++queries>1024 || SystemClock.uptimeMillis()>deadline) throw new Exception("页面读取达到上限，本次未保存原文。");
                        AccessibilityNodeInfo child=node.getChild(i);if(child!=null) nodes.addFirst(child);
                    }
                } finally {if(node!=root) node.recycle();}
            }
        } finally {for(AccessibilityNodeInfo node:nodes) if(node!=root) node.recycle();}
        if(parts.isEmpty()) throw new Exception("此页面未提供可读文字。你可以主动选择页面截图。");
        return String.join("\n",parts);
    }
}
