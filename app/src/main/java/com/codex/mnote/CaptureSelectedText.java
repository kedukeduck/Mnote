package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.*;

/** Bounded, read-only scan made only in response to a user's capture click. */
final class CaptureSelectedText {
    static final CaptureSelectedText EMPTY = new CaptureSelectedText("",-1,"","",-1);
    final String appPackage, quote, original;
    final int windowId, start;
    CaptureSelectedText(String appPackage,int windowId,String quote,String original,int start) {
        this.appPackage=appPackage; this.windowId=windowId; this.quote=quote; this.original=original; this.start=start;
    }
    boolean found() { return !quote.isEmpty(); }
    static CaptureSelectedText read(AccessibilityService service) {
        KeyguardManager lock=service.getSystemService(KeyguardManager.class);
        if(lock!=null && lock.isKeyguardLocked()) return EMPTY;
        List<AccessibilityWindowInfo> windows=new ArrayList<>();
        long deadline=SystemClock.uptimeMillis()+180;
        try {
            windows.addAll(service.getWindows()); windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
            String source=""; CaptureSelectedText result=EMPTY;
            for(AccessibilityWindowInfo window:windows) {
                if(SystemClock.uptimeMillis()>deadline) return EMPTY;
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                // Only our specifically titled transparent bridge may be skipped, never the Inbox/editor.
                boolean bridge=CaptureTriggerActivity.SOURCE_BRIDGE_TITLE.contentEquals(window.getTitle()==null?"":window.getTitle());
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null) { if(bridge) continue; return EMPTY; }
                try {
                    String pkg=packageName(root);
                    if(bridge && pkg.equals(service.getPackageName())) continue;
                    if(pkg.equals("com.android.systemui")) continue;
                    if(pkg.isEmpty() || pkg.equals(service.getPackageName())) return EMPTY;
                    if(!source.isEmpty() && !source.equals(pkg)) return result;
                    source=pkg;
                    CaptureSelectedText selected=fromRoot(root,deadline);
                    if(selected.found()) return selected;
                    result=selected;
                } finally { root.recycle(); }
            }
            if(!source.isEmpty()) return result;
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root==null) return EMPTY;
            try {
                String pkg=packageName(root);
                return pkg.equals(service.getPackageName()) || pkg.equals("com.android.systemui") || pkg.isEmpty()
                        ? EMPTY : fromRoot(root,deadline);
            } finally { root.recycle(); }
        } catch(RuntimeException error) { return EMPTY; }
        finally { for(AccessibilityWindowInfo window:windows) window.recycle(); }
    }
    static CaptureSelectedText fromRoot(AccessibilityNodeInfo root,long deadline) {
        if(SystemClock.uptimeMillis()>deadline) return EMPTY;
        String pkg=packageName(root);
        CaptureSelectedText none=new CaptureSelectedText(pkg,root.getWindowId(),"","",-1);
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        try {
            AccessibilityNodeInfo focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(focus!=null) {
                try { CaptureSelectedText selected=fromNode(focus,pkg); if(selected.found()) return selected; }
                finally { focus.recycle(); }
            }
            queue.add(root); // Root is borrowed; child/focus handles are owned by this scan.
            CaptureSelectedText found=none;
            int visited=0;
            while(!queue.isEmpty()) {
                if(++visited>256 || SystemClock.uptimeMillis()>deadline) return EMPTY;
                AccessibilityNodeInfo node=queue.removeFirst();
                try {
                    if(!node.refresh() || !node.isVisibleToUser() || node.isPassword() || !pkg.equals(packageName(node))) continue;
                    if(Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive()) continue;
                    CaptureSelectedText selected=fromRefreshedNode(node,pkg);
                    if(selected.found()) {
                        if(found.found() && (!found.quote.equals(selected.quote) || !found.original.equals(selected.original)
                                || found.start!=selected.start)) return EMPTY;
                        found=selected;
                    }
                    int children=node.getChildCount();
                    if(children+visited+queue.size()>256) return EMPTY;
                    for(int index=0;index<children;index++) { AccessibilityNodeInfo child=node.getChild(index); if(child!=null) queue.addLast(child); }
                } finally { if(node!=root) node.recycle(); }
            }
            return found;
        } catch(RuntimeException error) { return EMPTY; }
        finally { while(!queue.isEmpty()) { AccessibilityNodeInfo node=queue.removeFirst(); if(node!=root) node.recycle(); } }
    }
    private static CaptureSelectedText fromNode(AccessibilityNodeInfo node,String pkg) {
        if(!node.refresh() || !node.isVisibleToUser() || node.isPassword() || !pkg.equals(packageName(node))) return EMPTY;
        if(Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive()) return EMPTY;
        return fromRefreshedNode(node,pkg);
    }
    private static CaptureSelectedText fromRefreshedNode(AccessibilityNodeInfo node,String pkg) {
        int start=Math.min(node.getTextSelectionStart(),node.getTextSelectionEnd());
        int end=Math.max(node.getTextSelectionStart(),node.getTextSelectionEnd());
        // A cursor is not a selection. Never inspect unselected node text or descriptions.
        if(start<0 || end<=start || end-start>100_000) return EMPTY;
        CharSequence value=node.getText();
        if(value==null || end>value.length()) return EMPTY;
        String quote=value.subSequence(start,end).toString();
        if(quote.trim().isEmpty()) return EMPTY;
        String original=value.length()<=CaptureContext.MAX_TEXT ? value.toString() : "";
        return new CaptureSelectedText(pkg,node.getWindowId(),quote,original,original.isEmpty()?-1:start);
    }
    private static String packageName(AccessibilityNodeInfo node) {
        return node.getPackageName()==null?"":node.getPackageName().toString();
    }
}
