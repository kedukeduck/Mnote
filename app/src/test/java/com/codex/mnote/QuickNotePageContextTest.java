package com.codex.mnote;

import android.view.accessibility.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},shadows={QuickNotePageContextTest.NodeShadow.class,CaptureSourceContextTest.WindowQueryShadow.class})
public class QuickNotePageContextTest {
    static final String APP="reader.app";
    private static AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName(APP);n.setVisibleToUser(true);n.setText(text);return n;
    }
    private static NodeShadow info(AccessibilityNodeInfo n){return Shadow.extract(n);}
    private static AccessibilityWindowInfo window(AccessibilityNodeInfo root,int layer) {
        AccessibilityWindowInfo w=AccessibilityWindowInfo.obtain();shadowOf(w).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadowOf(w).setLayer(layer);shadowOf(w).setId(layer+10);shadowOf(w).setRoot(root);return w;
    }
    @Test public void onlyVisibleNonEditableSameAppTextIsCollected() throws Exception {
        AccessibilityNodeInfo root=node("");info(root).children.add(node("正文"));
        AccessibilityNodeInfo password=node("密码");password.setPassword(true);
        AccessibilityNodeInfo edit=node("私密输入");edit.setEditable(true);
        AccessibilityNodeInfo hidden=node("隐藏");hidden.setVisibleToUser(false);
        AccessibilityNodeInfo foreign=node("其他应用");foreign.setPackageName("other.app");
        info(root).children.addAll(Arrays.asList(password,edit,hidden,foreign,node("正文"),node("后文")));
        assertEquals("正文\n正文\n后文",QuickNotePageContext.visibleText(root,APP));
    }
    @Test public void emptyAndOversizedTextFailInsteadOfSavingPartialContext() {
        assertThrows(Exception.class,()->QuickNotePageContext.visibleText(node(""),APP));
        assertThrows(Exception.class,()->QuickNotePageContext.visibleText(node("a".repeat(40001)),APP));
        AccessibilityNodeInfo root=node("看似有效的开头");
        for(int i=0;i<1100;i++)info(root).children.add(node("child"));
        assertThrows(Exception.class,()->QuickNotePageContext.visibleText(root,APP));
        assertTrue(info(root).queries<=1024);
    }
    @Test public void nestedParagraphsFollowDocumentOrderAndLeafDescriptionsFillMissingText() throws Exception {
        AccessibilityNodeInfo root=node("标题"), section=node(""), first=node("第一段");
        AccessibilityNodeInfo label=node("");label.setContentDescription("只提供描述的第二段");
        info(section).children.addAll(Arrays.asList(first,label));
        section.setContentDescription("不重复容器摘要");
        info(root).children.addAll(Arrays.asList(section,node("尾段")));
        assertEquals("标题\n第一段\n只提供描述的第二段\n尾段",QuickNotePageContext.visibleText(root,APP));
    }
    @Test @Config(sdk=35) public void systemSensitiveTextIsNeverRead() {
        AccessibilityNodeInfo root=node("敏感内容");root.setAccessibilityDataSensitive(true);
        assertThrows(Exception.class,()->QuickNotePageContext.visibleText(root,APP));
    }
    @Test public void nodeAndTimeBudgetsAlsoFailClosed() {
        AccessibilityNodeInfo root=node("开头");
        for(int i=0;i<600;i++)info(root).children.add(node("child"+i));
        assertThrows(Exception.class,()->QuickNotePageContext.visibleText(root,APP));
        AccessibilityNodeInfo slow=node("slow");info(slow).children.add(node("child"));info(slow).delay=800;
        assertThrows(Exception.class,()->QuickNotePageContext.visibleText(slow,APP));
    }
    @Test public void knownBridgeWithNullRootCanExposeExternalPageButOtherMnoteWindowsCannot() {
        var c=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityWindowInfo bridge=window(null,3);shadowOf(bridge).setTitle(QuickNoteActivity.CONTEXT_TITLE);
            shadowOf(c.get()).setWindows(Arrays.asList(bridge,window(node("正文"),2)));
            QuickNotePageContext page=QuickNotePageContext.read(c.get(),-1,true);
            assertTrue(page.error,page.found());assertEquals("正文",page.text);assertEquals(APP,page.source.appPackage);
            AccessibilityNodeInfo own=node("Mnote内容");own.setPackageName(c.get().getPackageName());
            shadowOf(c.get()).setWindows(Arrays.asList(window(own,3),window(node("不应读取"),2)));
            assertFalse(QuickNotePageContext.read(c.get(),-1,true).found());
        } finally {c.destroy();}
    }
    @Test public void identifiedBridgeSkipsOnlyOneOwnWindowAndUnreadableExternalWindowStopsSearch() {
        var c=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityNodeInfo own=node("桥");own.setPackageName(c.get().getPackageName());
            shadowOf(c.get()).setWindows(Arrays.asList(window(own,3),window(node("正文"),2)));
            assertTrue(QuickNotePageContext.read(c.get(),13,true).found());
            shadowOf(c.get()).setWindows(Arrays.asList(window(null,3),window(node("不应读取"),2)));
            assertFalse(QuickNotePageContext.read(c.get(),-1,true).found());
            AccessibilityWindowInfo bridge=window(null,3);shadowOf(bridge).setTitle(QuickNoteActivity.CONTEXT_TITLE);
            AccessibilityNodeInfo editor=node("编辑器");editor.setPackageName(c.get().getPackageName());
            shadowOf(c.get()).setWindows(Arrays.asList(bridge,window(editor,2),window(node("旧应用"),1)));
            assertFalse(QuickNotePageContext.read(c.get(),-1,true).found());
        } finally {c.destroy();}
    }
    @Test public void keyboardBlocksContextAndScreenshotGuardDoesNotReadPageText() {
        var c=Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityWindowInfo ime=window(null,4);shadowOf(ime).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD);
            shadowOf(c.get()).setWindows(Arrays.asList(ime,window(node("正文"),2)));
            assertFalse(QuickNotePageContext.read(c.get(),-1,false).found());
            AccessibilityNodeInfo root=node("正文");info(root).children.add(node("不扫描"));
            shadowOf(c.get()).setWindows(List.of(window(root,2)));
            QuickNotePageContext page=QuickNotePageContext.read(c.get(),-1,false);
            assertTrue(page.found());assertEquals("",page.text);assertEquals(0,info(root).queries);
        } finally {c.destroy();}
    }
    @Implements(AccessibilityNodeInfo.class)
    public static class NodeShadow extends ShadowAccessibilityNodeInfo {
        final List<AccessibilityNodeInfo> children=new ArrayList<>();int queries,delay;
        @Implementation protected int getChildCount(){return children.size();}
        @Implementation protected AccessibilityNodeInfo getChild(int index){
            queries++;if(delay>0)org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(delay));
            return children.get(index);
        }
    }
}
