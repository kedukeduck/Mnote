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
    private static volatile String lastDiagnostic="尚未尝试读取选中文字";
    static String diagnostic() { return lastDiagnostic; }
    static CaptureSelectedText unavailable(String reason) { lastDiagnostic=reason; return EMPTY; }
    static CaptureSelectedText read(AccessibilityService service) {
        return read(service,-1);
    }
    static CaptureSelectedText read(AccessibilityService service,int ownBridgeWindowId) {
        Scan trace=new Scan();
        try {
            CaptureSelectedText result=read(service,ownBridgeWindowId,trace);
            if(result.found()) trace.reason="已读取选中文字";
            return result;
        } finally { lastDiagnostic=trace.summary(); }
    }
    private static CaptureSelectedText read(AccessibilityService service,int ownBridgeWindowId,Scan trace) {
        KeyguardManager lock=service.getSystemService(KeyguardManager.class);
        if(lock!=null && lock.isKeyguardLocked()) { trace.reason="锁屏，已停止读取"; return EMPTY; }
        List<AccessibilityWindowInfo> windows=new ArrayList<>();
        long deadline=SystemClock.uptimeMillis()+180;
        try {
            windows.addAll(service.getWindows()); windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
            trace.totalWindows=windows.size();
            String source=""; CaptureSelectedText result=EMPTY;
            for(AccessibilityWindowInfo window:windows) {
                if(SystemClock.uptimeMillis()>deadline) { trace.reason="窗口查询超时"; return EMPTY; }
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                trace.windows++;
                // The live bridge passes its own attached decor's window ID. Titles
                // can be replaced by the framework/OEM; never skip every Mnote window.
                boolean localBridge=ownBridgeWindowId>=0 && window.getId()==ownBridgeWindowId;
                boolean titledBridge=CaptureTriggerActivity.SOURCE_BRIDGE_TITLE.contentEquals(window.getTitle()==null?"":window.getTitle());
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null) {
                    if(localBridge || titledBridge) { trace.bridges++; continue; }
                    trace.reason="来源窗口没有提供可读根节点"; return EMPTY;
                }
                try {
                    String pkg=packageName(root);
                    if(localBridge && !pkg.isEmpty() && !pkg.equals(service.getPackageName())) {
                        trace.reason="过渡窗口 ID 与应用身份冲突，已停止读取"; return EMPTY;
                    }
                    if((localBridge && pkg.isEmpty()) || ((localBridge || titledBridge) && pkg.equals(service.getPackageName()))) {
                        trace.bridges++; continue;
                    }
                    if(pkg.equals("com.android.systemui")) continue;
                    if(pkg.isEmpty()) { trace.reason="来源根节点未提供应用包名，未猜测来源"; return EMPTY; }
                    if(pkg.equals(service.getPackageName())) { trace.reason="当前是 Mnote 窗口，但未匹配本次过渡页，已停止读取"; return EMPTY; }
                    if(!source.isEmpty() && !source.equals(pkg)) return result;
                    source=pkg;
                    trace.roots++;
                    CaptureSelectedText selected=fromRoot(root,deadline,trace);
                    if(selected.found()) return selected;
                    result=selected;
                } finally { root.recycle(); }
            }
            if(!source.isEmpty()) return result;
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root==null) { trace.reason="没有可读的来源窗口"; return EMPTY; }
            try {
                String pkg=packageName(root);
                if(pkg.equals(service.getPackageName()) || pkg.equals("com.android.systemui") || pkg.isEmpty()) {
                    trace.reason="当前只读到 Mnote 或系统窗口，未读到来源窗口"; return EMPTY;
                }
                trace.roots++;
                return fromRoot(root,deadline,trace);
            } finally { root.recycle(); }
        } catch(RuntimeException error) { trace.reason="系统窗口查询失败"; return EMPTY; }
        finally { for(AccessibilityWindowInfo window:windows) window.recycle(); }
    }
    static CaptureSelectedText fromRoot(AccessibilityNodeInfo root,long deadline) {
        return fromRoot(root,deadline,new Scan());
    }
    private static CaptureSelectedText fromRoot(AccessibilityNodeInfo root,long deadline,Scan trace) {
        if(SystemClock.uptimeMillis()>deadline) { trace.reason="窗口查询超时"; return EMPTY; }
        String pkg=packageName(root);
        CaptureSelectedText none=new CaptureSelectedText(pkg,root.getWindowId(),"","",-1);
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        try {
            AccessibilityNodeInfo focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(focus!=null) {
                try { CaptureSelectedText selected=fromNode(focus,pkg,trace); if(selected.found()) return selected; }
                finally { focus.recycle(); }
            }
            queue.add(root); // Root is borrowed; child/focus handles are owned by this scan.
            CaptureSelectedText found=none;
            int visited=0;
            while(!queue.isEmpty()) {
                if(++visited>256 || SystemClock.uptimeMillis()>deadline) { trace.reason="节点扫描达到数量或时间上限"; return EMPTY; }
                AccessibilityNodeInfo node=queue.removeFirst();
                trace.nodes++;
                try {
                    if(!node.refresh() || !node.isVisibleToUser() || node.isPassword() || !pkg.equals(packageName(node))) continue;
                    if(Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive()) continue;
                    CaptureSelectedText selected=fromRefreshedNode(node,pkg,trace);
                    if(selected.found()) {
                        if(found.found() && (!found.quote.equals(selected.quote) || !found.original.equals(selected.original)
                                || found.start!=selected.start)) { trace.reason="多个选区有冲突，未猜测文字"; return EMPTY; }
                        found=selected;
                    }
                    int children=node.getChildCount();
                    if(children+visited+queue.size()>256) { trace.reason="节点扫描达到数量上限"; return EMPTY; }
                    for(int index=0;index<children;index++) { AccessibilityNodeInfo child=node.getChild(index); if(child!=null) queue.addLast(child); }
                } finally { if(node!=root) node.recycle(); }
            }
            return found;
        } catch(RuntimeException error) { trace.reason="系统节点查询失败"; return EMPTY; }
        finally { while(!queue.isEmpty()) { AccessibilityNodeInfo node=queue.removeFirst(); if(node!=root) node.recycle(); } }
    }
    private static CaptureSelectedText fromNode(AccessibilityNodeInfo node,String pkg,Scan trace) {
        trace.nodes++;
        if(!node.refresh() || !node.isVisibleToUser() || node.isPassword() || !pkg.equals(packageName(node))) return EMPTY;
        if(Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive()) return EMPTY;
        return fromRefreshedNode(node,pkg,trace);
    }
    private static CaptureSelectedText fromRefreshedNode(AccessibilityNodeInfo node,String pkg,Scan trace) {
        int start=Math.min(node.getTextSelectionStart(),node.getTextSelectionEnd());
        int end=Math.max(node.getTextSelectionStart(),node.getTextSelectionEnd());
        // A cursor is not a selection. Never inspect unselected node text or descriptions.
        if(start<0 || end<=start || end-start>100_000) return EMPTY;
        trace.ranges++;
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
    /** In-memory counters only; never retain text, URLs, package names or exception messages. */
    private static final class Scan {
        int totalWindows,windows,bridges,roots,nodes,ranges;
        String reason="未找到可用文字选区（可能未暴露、已取消或被过滤）";
        String summary() { return reason+"\n返回窗口 "+totalWindows+" · 已检查应用窗口 "+windows+" · 跳过过渡页 "+bridges
                +"\n来源根节点 "+roots+" · 检查节点 "+nodes+" · 有效范围 "+ranges; }
    }
}
