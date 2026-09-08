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
    static final int MAX_NODES=1024, MAX_CHILD_QUERIES=2048;
    static final long CLICK_BUDGET_MS=180, SOURCE_BUDGET_MS=1000;
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
        return readWithBudget(service,-1,CLICK_BUDGET_MS);
    }
    static CaptureSelectedText read(AccessibilityService service,int ownBridgeWindowId) {
        return readWithBudget(service,ownBridgeWindowId,SOURCE_BUDGET_MS);
    }
    private static CaptureSelectedText readWithBudget(AccessibilityService service,int ownBridgeWindowId,long budgetMillis) {
        Scan trace=new Scan();
        try {
            CaptureSelectedText result=read(service,ownBridgeWindowId,trace,trace.started+budgetMillis);
            if(result.found()) trace.reason="已读取选中文字";
            return result;
        } finally { lastDiagnostic=trace.summary(); }
    }
    private static CaptureSelectedText read(AccessibilityService service,int ownBridgeWindowId,Scan trace,long deadline) {
        KeyguardManager lock=service.getSystemService(KeyguardManager.class);
        if(lock!=null && lock.isKeyguardLocked()) { trace.reason="锁屏，已停止读取"; return EMPTY; }
        List<AccessibilityWindowInfo> windows=new ArrayList<>();
        try {
            // Discard stale framework snapshots once, only for this user request.
            // Freshly fetched unselected children then need no duplicate Binder refresh.
            if(Build.VERSION.SDK_INT>=33) {
                try { trace.freshCache=service.clearCache(); } catch(RuntimeException ignored) { }
            }
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
                    if(!source.isEmpty() && !source.equals(pkg)) return result;
                    source=pkg;
                    trace.roots++;
                    CaptureSelectedText selected=fromRoot(root,deadline,trace);
                    if(selected.found()) return selected;
                    // Mnote itself is a valid source, not a window to skip. Never
                    // search its older editor/detail windows if the current one has no quote.
                    if(pkg.equals(service.getPackageName())) {
                        if(trace.reason.equals(Scan.NO_SELECTION)) trace.reason="当前 Mnote 页面未提供可用文字选区，未向后读取其他窗口";
                        return selected;
                    }
                    result=selected;
                } finally { root.recycle(); }
            }
            if(!source.isEmpty()) return result;
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root==null) { trace.reason="没有可读的来源窗口"; return EMPTY; }
            try {
                String pkg=packageName(root);
                if(pkg.equals("com.android.systemui") || pkg.isEmpty()
                        || (pkg.equals(service.getPackageName()) && (trace.bridges>0
                        || (ownBridgeWindowId>=0 && root.getWindowId()==ownBridgeWindowId)))) {
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
        ArrayDeque<Frame> stack=new ArrayDeque<>();
        try {
            for(int kind:new int[]{AccessibilityNodeInfo.FOCUS_INPUT,AccessibilityNodeInfo.FOCUS_ACCESSIBILITY}) {
                if(SystemClock.uptimeMillis()>deadline) { trace.reason="焦点查询达到时间上限"; return EMPTY; }
                AccessibilityNodeInfo focus=root.findFocus(kind);
                if(focus!=null) {
                    try { CaptureSelectedText selected=fromNode(focus,pkg,trace); if(selected.found()) return selected; }
                    finally { focus.recycle(); }
                }
            }
            stack.push(new Frame(root)); // Root is borrowed; child/focus handles are owned.
            CaptureSelectedText found=none;
            while(!stack.isEmpty()) {
                if(SystemClock.uptimeMillis()>deadline) { trace.reason="节点扫描达到时间上限"; return EMPTY; }
                Frame frame=stack.peek(); AccessibilityNodeInfo node=frame.node;
                if(!frame.entered) {
                    if(trace.nodes>=MAX_NODES) { trace.reason="节点扫描达到数量上限"; return EMPTY; }
                    trace.nodes++; frame.entered=true;
                    boolean refresh=node==root || !trace.freshCache || hasRange(node);
                    if((refresh && !refresh(node,trace)) || !eligible(node,pkg)) {
                        stack.pop(); if(node!=root) node.recycle(); continue;
                    }
                    CaptureSelectedText selected=fromRefreshedNode(node,pkg,trace);
                    if(selected.found()) {
                        if(found.found() && (!found.quote.equals(selected.quote) || !found.original.equals(selected.original)
                                || found.start!=selected.start)) { trace.reason="多个选区有冲突，未猜测文字"; return EMPTY; }
                        found=selected;
                    }
                    frame.children=node.getChildCount();
                }
                if(frame.next>=frame.children) { stack.pop(); if(node!=root) node.recycle(); continue; }
                if(++trace.childQueries>MAX_CHILD_QUERIES) { trace.reason="子节点查询达到数量上限"; return EMPTY; }
                // Fetch children lazily; a large DOM must not fail just because
                // one parent advertises more than the old 256-node queue limit.
                AccessibilityNodeInfo child=node.getChild(frame.next++);
                if(child!=null) stack.push(new Frame(child));
            }
            return found;
        } catch(RuntimeException error) { trace.reason="系统节点查询失败"; return EMPTY; }
        finally { while(!stack.isEmpty()) { AccessibilityNodeInfo node=stack.pop().node; if(node!=root) node.recycle(); } }
    }
    private static CaptureSelectedText fromNode(AccessibilityNodeInfo node,String pkg,Scan trace) {
        if(trace.nodes>=MAX_NODES) { trace.reason="节点扫描达到数量上限"; return EMPTY; }
        trace.nodes++;
        if(!refresh(node,trace) || !eligible(node,pkg)) return EMPTY;
        return fromRefreshedNode(node,pkg,trace);
    }
    private static boolean refresh(AccessibilityNodeInfo node,Scan trace) { trace.refreshes++; return node.refresh(); }
    private static boolean eligible(AccessibilityNodeInfo node,String pkg) {
        return node.isVisibleToUser() && !node.isPassword() && pkg.equals(packageName(node))
                && !(Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive());
    }
    private static boolean hasRange(AccessibilityNodeInfo node) {
        return node.getTextSelectionStart()>=0 && node.getTextSelectionEnd()>=0
                && node.getTextSelectionStart()!=node.getTextSelectionEnd();
    }
    private static final class Frame {
        final AccessibilityNodeInfo node;
        boolean entered; int children,next;
        Frame(AccessibilityNodeInfo node) { this.node=node; }
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
        static final String NO_SELECTION="未找到可用文字选区（扫描完成，可能未暴露、已取消或被过滤）";
        final long started=SystemClock.uptimeMillis();
        boolean freshCache;
        int totalWindows,windows,bridges,roots,nodes,ranges,refreshes,childQueries;
        String reason=NO_SELECTION;
        String summary() { return reason+"\n返回窗口 "+totalWindows+" · 已检查应用窗口 "+windows+" · 跳过过渡页 "+bridges
                +"\n来源根节点 "+roots+" · 检查节点 "+nodes+" · 有效范围 "+ranges
                +"\n耗时 "+(SystemClock.uptimeMillis()-started)+" ms · 刷新查询 "+refreshes+" · 缓存重置 "+(freshCache?"成功":"未使用"); }
    }
}
