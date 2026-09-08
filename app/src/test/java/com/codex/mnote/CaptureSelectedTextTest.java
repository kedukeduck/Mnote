package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.*;
import android.view.accessibility.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},shadows={CaptureSelectedTextTest.NodeShadow.class,
        CaptureSourceContextTest.WindowQueryShadow.class,CaptureSelectedTextTest.QueryServiceShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureSelectedTextTest {
    static final String APP="com.example.reader";
    @Before public void setup() { CaptureSelectionTicket.clear(); QueryServiceShadow.clearSucceeds=false; }
    static AccessibilityNodeInfo node(String pkg,String text,int start,int end) {
        AccessibilityNodeInfo node=AccessibilityNodeInfo.obtain(); node.setPackageName(pkg); node.setVisibleToUser(true);
        node.setText(text); node.setTextSelection(start,end); return node;
    }
    static NodeShadow info(AccessibilityNodeInfo node) { return Shadow.extract(node); }
    CaptureSelectedText scan(AccessibilityNodeInfo root) { return CaptureSelectedText.fromRoot(root,SystemClock.uptimeMillis()+180); }
    static AccessibilityWindowInfo window(AccessibilityNodeInfo root,int layer) {
        AccessibilityWindowInfo window=AccessibilityWindowInfo.obtain(); shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadowOf(window).setRoot(root); shadowOf(window).setLayer(layer); return window;
    }
    @Test public void exactSelectionAndItsTextBlockAreReadButOtherNodeTextIsNot() {
        AccessibilityNodeInfo root=node(APP,"do not read entire root",-1,-1);
        AccessibilityNodeInfo quote=node(APP,"前文：选中文字：后文",3,7);
        info(root).children.add(quote);
        CaptureSelectedText result=scan(root);
        assertEquals("选中文字",result.quote); assertEquals("前文：选中文字：后文",result.original);
        assertEquals(3,result.start); assertEquals(APP,result.appPackage);
        assertEquals(0,info(root).textReads); assertEquals(1,info(quote).textReads);
        assertTrue(shadowOf(quote).getPerformedActions().isEmpty());
    }
    @Test public void cursorHiddenPasswordForeignSensitiveAndStaleNodesAreNotRead() {
        for(int mode=0;mode<7;mode++) {
            AccessibilityNodeInfo root=node(APP,"",-1,-1), child=node(APP,"secret",0,6);
            info(root).children.add(child);
            if(mode==0) child.setTextSelection(2,2);
            if(mode==1) child.setVisibleToUser(false);
            if(mode==2) child.setPassword(true);
            if(mode==3) child.setPackageName("other.app");
            if(mode==4) info(child).refreshable=false;
            if(mode==5) child.setTextSelection(-1,-1);
            if(mode==6) { if(Build.VERSION.SDK_INT<34) continue; child.setAccessibilityDataSensitive(true); }
            assertFalse(scan(root).found()); assertEquals(0,info(child).textReads);
        }
    }
    @Test public void invertedBoundsWorkButInvalidAndAmbiguousSelectionsDoNotGuess() {
        assertEquals("选中",scan(node(APP,"前选中后",3,1)).quote);
        assertFalse(scan(node(APP,"short",0,100)).found());
        AccessibilityNodeInfo root=node(APP,"",-1,-1);
        info(root).children.add(node(APP,"first",0,5)); info(root).children.add(node(APP,"second",0,6));
        assertFalse(scan(root).found());
    }
    @Test public void longNodeOnlyHandsOffBoundedQuoteAndNoUnboundedOriginal() {
        CaptureSelectedText result=scan(node(APP,"x".repeat(50000),10,15));
        assertEquals("xxxxx",result.quote); assertEquals("",result.original); assertEquals(-1,result.start);
    }
    @Test public void traversalBudgetStopsWithoutReadingUnselectedContent() {
        AccessibilityNodeInfo root=node(APP,"never read",-1,-1);
        for(int i=0;i<257;i++) info(root).children.add(node(APP,"unused",-1,-1));
        assertFalse(scan(root).found()); assertEquals(0,info(root).textReads);
        assertFalse(CaptureSelectedText.fromRoot(node(APP,"selected",0,8),SystemClock.uptimeMillis()-1).found());
    }
    @Test public void quickSettingsAndOurBridgeAreSkippedButOtherAppsAndInboxAreBoundaries() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityService service=controller.get();
            AccessibilityWindowInfo bridge=window(null,3); shadowOf(bridge).setTitle(CaptureTriggerActivity.SOURCE_BRIDGE_TITLE);
            AccessibilityNodeInfo selected=node(APP,"quote",0,5);
            shadowOf(service).setWindows(Arrays.asList(bridge,window(node("com.android.systemui","",-1,-1),2),window(selected,1)));
            assertEquals("quote",CaptureSelectedText.read(service).quote);
            shadowOf(service).setWindows(Arrays.asList(window(node("other.app","",-1,-1),2),window(selected,1)));
            assertFalse(CaptureSelectedText.read(service).found());
            shadowOf(service).setWindows(Arrays.asList(window(node(service.getPackageName(),"",-1,-1),2),window(selected,1)));
            assertFalse(CaptureSelectedText.read(service).found());
            shadowOf(service).setWindows(Arrays.asList(window(null,2),window(selected,1)));
            assertFalse(CaptureSelectedText.read(service).found());
        } finally { controller.destroy(); }
    }
    @Test public void lockScreenStopsBeforeAnyTextRead() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityNodeInfo selected=node(APP,"quote",0,5);
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(selected,1)));
            shadowOf(controller.get().getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
            assertFalse(CaptureSelectedText.read(controller.get()).found()); assertEquals(0,info(selected).textReads);
        } finally { controller.destroy(); }
    }
    @Test public void widePageCanReachSelectionBeyondOldEagerChildLimit() {
        AccessibilityNodeInfo root=node(APP,"",-1,-1);
        for(int index=0;index<300;index++) info(root).children.add(node(APP,"unselected",-1,-1));
        info(root).children.add(node(APP,"late quote",5,10));
        assertEquals("quote",scan(root).quote);
    }
    @Test public void currentMnoteWindowCanBeTheTextSourceWithoutSearchingOlderWindows() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            String own=controller.get().getPackageName();
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(node(own,"my note quote",8,13),1)));
            assertEquals("quote",CaptureSelectedText.read(controller.get()).quote);
            AccessibilityNodeInfo older=node(own,"older secret",0,12);
            shadowOf(controller.get()).setWindows(Arrays.asList(window(node(own,"",-1,-1),2),window(older,1)));
            assertFalse(CaptureSelectedText.read(controller.get()).found());
            assertEquals(0,info(older).textReads);
        } finally { controller.destroy(); }
    }
    @Test public void liveWindowIdSkipsRenamedBridgeEvenWhenItsRootOrPackageIsMissing() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            for(int mode=0;mode<3;mode++) {
                AccessibilityNodeInfo bridgeRoot=mode==2 ? null : node(mode==0 ? controller.get().getPackageName() : "","",-1,-1);
                AccessibilityWindowInfo bridge=window(bridgeRoot,2);
                shadowOf(bridge).setId(2301); shadowOf(bridge).setTitle("Mnote");
                AccessibilityWindowInfo source=window(node(APP,"quote",0,5),1); shadowOf(source).setId(27);
                shadowOf(controller.get()).setWindows(Arrays.asList(bridge,source));
                assertEquals("quote",CaptureSelectedText.read(controller.get(),2301).quote);
                assertTrue(CaptureSelectedText.diagnostic().contains("跳过过渡页 1"));
                assertTrue(CaptureSelectedText.diagnostic().contains("来源根节点 1"));
            }
        } finally { controller.destroy(); }
    }
    @Test public void unrelatedMnoteMissingPackageAndMismatchedIdentityDoNotReadBehindThem() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            for(int mode=0;mode<3;mode++) {
                String pkg=mode==0 ? controller.get().getPackageName() : mode==1 ? "" : "another.app";
                AccessibilityWindowInfo front=window(node(pkg,"",-1,-1),2);
                shadowOf(front).setId(mode==2 ? 2301 : 2302); shadowOf(front).setTitle("Mnote");
                AccessibilityNodeInfo selected=node(APP,"private quote",0,13);
                shadowOf(controller.get()).setWindows(Arrays.asList(front,window(selected,1)));
                assertFalse(CaptureSelectedText.read(controller.get(),2301).found());
                assertEquals(0,info(selected).textReads);
                assertTrue(CaptureSelectedText.diagnostic().contains(mode==0 ? "当前 Mnote 页面未提供" : mode==1 ? "未提供应用包名" : "身份冲突"));
            }
        } finally { controller.destroy(); }
    }
    @Test public void ticketIsOneUseExpiringAndAccountBound() {
        Context context=RuntimeEnvironment.getApplication();
        CaptureSelectedText selected=new CaptureSelectedText(APP,17,"quote","before quote after",7);
        String ticket=CaptureSelectionTicket.put(context,selected);
        assertFalse(ticket.contains("quote")); assertSame(selected,CaptureSelectionTicket.take(context,ticket));
        assertFalse(CaptureSelectionTicket.take(context,ticket).found());
        ticket=CaptureSelectionTicket.put(context,selected);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));
        assertFalse(CaptureSelectionTicket.take(context,ticket).found());
        ticket=CaptureSelectionTicket.put(context,selected);
        CaptureAccountSession.preferences(context).edit().putString("scope","a".repeat(64)).commit();
        assertFalse(CaptureSelectionTicket.take(context,ticket).found());
    }
    @Test public void diagnosticDistinguishesMissingWindowFromMissingSelectionWithoutStoringContent() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            shadowOf(controller.get()).setWindows(Collections.emptyList());
            assertFalse(CaptureSelectedText.read(controller.get()).found());
            assertTrue(CaptureSelectedText.diagnostic().contains("没有可读的来源窗口"));
            AccessibilityNodeInfo selected=node(APP,"private article selected",16,24);
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(selected,1)));
            assertEquals("selected",CaptureSelectedText.read(controller.get()).quote);
            String diagnostic=CaptureSelectedText.diagnostic();
            assertTrue(diagnostic.contains("已读取选中文字"));
            assertTrue(diagnostic.contains("来源根节点 1"));
            assertFalse(diagnostic.contains("private")); assertFalse(diagnostic.contains(APP));
            // Framework queries hand out fresh window handles; do not reuse the recycled one.
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(node(APP,"private article selected",-1,-1),1)));
            assertFalse(CaptureSelectedText.read(controller.get()).found());
            assertTrue(CaptureSelectedText.diagnostic().contains("未找到可用文字选区"));
            assertTrue(CaptureSelectedText.diagnostic().contains("有效范围 0"));
        } finally { controller.destroy(); }
    }
    @Test public void accessibilityFocusFindsSelectionBeforeWalkingHugePage() {
        AccessibilityNodeInfo root=node(APP,"",-1,-1);
        for(int index=0;index<1500;index++) info(root).children.add(node(APP,"",-1,-1));
        info(root).accessibilityFocus=node(APP,"focused quote",8,13);
        assertEquals("quote",scan(root).quote);
        assertEquals(0,info(root).childReads);
    }
    @Test public void nodeLimitRemainsBoundedAndDoesNotReturnPartialSelection() {
        AccessibilityNodeInfo root=node(APP,"",-1,-1);
        info(root).children.add(node(APP,"first quote",6,11));
        for(int index=0;index<1500;index++) info(root).children.add(node(APP,"",-1,-1));
        assertFalse(scan(root).found());
        assertTrue(info(root).childReads<=CaptureSelectedText.MAX_NODES);
    }
    @Test public void settledReadHasMoreTimeButStillStopsOnSlowProviders() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(slowPage(60),1)));
            assertFalse(CaptureSelectedText.read(controller.get()).found());
            assertTrue(CaptureSelectedText.diagnostic().contains("时间上限"));
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(slowPage(60),1)));
            assertEquals("quote",CaptureSelectedText.read(controller.get(),2301).quote);
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(slowPage(400),1)));
            assertFalse(CaptureSelectedText.read(controller.get(),2301).found());
            assertTrue(CaptureSelectedText.diagnostic().contains("时间上限"));
        } finally { controller.destroy(); }
    }
    private AccessibilityNodeInfo slowPage(int count) {
        AccessibilityNodeInfo root=node(APP,"",-1,-1);
        for(int index=0;index<count;index++) {
            AccessibilityNodeInfo child=node(APP,"",-1,-1); info(child).refreshDelay=4;
            info(root).children.add(child);
        }
        info(root).children.add(node(APP,"quote",0,5));
        return root;
    }
    @Test @Config(sdk=35)
    public void freshCacheAvoidsDuplicateUnselectedQueriesButRevalidatesTheQuote() {
        var controller=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            QueryServiceShadow.clearSucceeds=true;
            AccessibilityNodeInfo root=node(APP,"",-1,-1), unselected=node(APP,"private other block",-1,-1), selected=node(APP,"quote",0,5);
            info(root).children.add(unselected); info(root).children.add(selected);
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(root,1)));
            assertEquals("quote",CaptureSelectedText.read(controller.get(),2301).quote);
            assertEquals(0,info(unselected).refreshCalls); assertEquals(0,info(unselected).textReads);
            assertEquals(1,info(selected).refreshCalls);
            assertTrue(CaptureSelectedText.diagnostic().contains("缓存重置 成功"));
            AccessibilityNodeInfo changed=node(APP,"stale selection",0,5);
            info(changed).clearSelectionOnRefresh=true;
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(changed,1)));
            assertFalse(CaptureSelectedText.read(controller.get(),2301).found());
            assertEquals(0,info(changed).textReads);
        } finally { controller.destroy(); }
    }
    @Implements(AccessibilityService.class)
    public static class QueryServiceShadow extends CaptureSourceContextTest.ServiceInfoShadow {
        static boolean clearSucceeds;
        @Implementation(minSdk=33) protected boolean clearCache() { return clearSucceeds; }
    }
    @Implements(AccessibilityNodeInfo.class) public static class NodeShadow extends ShadowAccessibilityNodeInfo {
        final List<AccessibilityNodeInfo> children=new ArrayList<>();
        @org.robolectric.annotation.RealObject AccessibilityNodeInfo realNode;
        AccessibilityNodeInfo accessibilityFocus;
        CharSequence value; int textReads,refreshCalls,refreshDelay,childReads; boolean refreshable=true,clearSelectionOnRefresh;
        @Implementation protected boolean refresh() {
            refreshCalls++;
            if(refreshDelay>0) org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(refreshDelay));
            if(clearSelectionOnRefresh) realNode.setTextSelection(-1,-1);
            return refreshable;
        }
        @Implementation protected void setText(CharSequence text) { value=text; }
        @Implementation protected CharSequence getText() { textReads++; return value; }
        @Implementation protected int getChildCount() { return children.size(); }
        @Implementation protected AccessibilityNodeInfo getChild(int index) { childReads++; return children.get(index); }
        @Implementation protected AccessibilityNodeInfo findFocus(int focus) { return focus==AccessibilityNodeInfo.FOCUS_ACCESSIBILITY ? accessibilityFocus : null; }
        @Implementation protected int getWindowId() { return 17; }
    }
}
