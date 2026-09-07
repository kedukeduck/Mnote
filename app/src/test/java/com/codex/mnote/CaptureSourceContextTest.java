package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35}, shadows = {CaptureSourceContextTest.NodeQueryShadow.class,
        CaptureSourceContextTest.WindowQueryShadow.class, CaptureSourceContextTest.ServiceInfoShadow.class})
public class CaptureSourceContextTest {
    private static final String CHROME = "com.android.chrome";
    private static final String URL = "https://example.com/article/123?q=note#part2";

    @Test public void browserAddressPreservesFullPathQueryFragmentAndPackage() {
        AccessibilityNodeInfo root = root(CHROME, field(CHROME, "url_bar", URL));
        CaptureSourceContext source = CaptureSourceContext.fromRoot(root);
        assertEquals(CHROME, source.appPackage);
        assertEquals(URL, source.url);
        assertEquals("browser_address_bar", source.origin);
        assertEquals(Collections.singletonList(CHROME + ":id/url_bar"), query(root).queries);
        assertTrue(shadowOf(root).getPerformedActions().isEmpty());
    }

    @Test public void missingSchemeIsExplicitlyMarkedAndSearchesAreNotUrls() {
        CaptureSourceContext source = CaptureSourceContext.fromRoot(root(CHROME,
                field(CHROME, "url_bar", "example.com/article/123?q=note#part2")));
        assertEquals(URL, source.url);
        assertEquals("browser_address_bar_https", source.origin);
        for (String invalid : new String[]{"a search query", "someword", "chrome://newtab", "about:blank",
                "intent://example.com", "javascript:alert(1)", "https://example.com/…", "example.com/...", ""}) {
            assertEquals(invalid, "", CaptureSourceContext.addressUrl(invalid));
        }
    }

    @Test public void firefoxUsesItsAddressControlAndUnsupportedAppsAreNotTraversed() {
        String firefox = "org.mozilla.firefox";
        assertEquals(URL, CaptureSourceContext.fromRoot(root(firefox,
                field(firefox, "mozac_browser_toolbar_url_view", URL))).url);
        AccessibilityNodeInfo weibo = root("com.sina.weibo", field("com.sina.weibo", "url_bar", URL));
        CaptureSourceContext source = CaptureSourceContext.fromRoot(weibo);
        assertEquals("com.sina.weibo", source.appPackage);
        assertEquals("", source.url);
        assertTrue(query(weibo).queries.isEmpty());
    }

    @Test public void hiddenPasswordEditingAndWrongPackageControlsAreIgnored() {
        for (int mode = 0; mode < 4; mode++) {
            AccessibilityNodeInfo field = field(CHROME, "url_bar", URL);
            if (mode == 0) field.setVisibleToUser(false);
            if (mode == 1) field.setPassword(true);
            if (mode == 2) field.setFocused(true);
            if (mode == 3) field.setPackageName("org.untrusted.page");
            assertEquals("", CaptureSourceContext.fromRoot(root(CHROME, field)).url);
        }
    }

    @Test public void conflictingAddressesAreNotGuessedAndMissingAddressHasNoCache() {
        AccessibilityNodeInfo root = root(CHROME, field(CHROME, "url_bar", URL),
                field(CHROME, "url_bar", "https://another.example.com/post"));
        assertEquals("", CaptureSourceContext.fromRoot(root).url);
        assertEquals(URL, CaptureSourceContext.fromRoot(root(CHROME, field(CHROME, "url_bar", URL))).url);
        assertEquals("", CaptureSourceContext.fromRoot(root(CHROME)).url);
    }

    @Test public void bridgeIsSkippedButOnlyImmediatelyUnderlyingApplicationIsRead() {
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            CaptureAccessibilityService service = controller.get();
            AccessibilityNodeInfo browser = root(CHROME, field(CHROME, "url_bar", URL));
            AccessibilityNodeInfo hidden = root("org.mozilla.firefox",
                    field("org.mozilla.firefox", "mozac_browser_toolbar_url_view", "https://hidden.example.com"));
            shadowOf(service).setWindows(Arrays.asList(window(hidden, 1), window(browser, 2),
                    window(root(service.getPackageName()), 3)));
            assertEquals(URL, CaptureSourceContext.read(service).url);
            assertTrue(query(hidden).queries.isEmpty());
        } finally { controller.destroy(); }
    }

    @Test public void unreadableOrOwnSourceDoesNotStealUrlFromBackgroundApplication() {
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            CaptureAccessibilityService service = controller.get();
            AccessibilityNodeInfo browser = root(CHROME, field(CHROME, "url_bar", URL));
            shadowOf(service).setWindows(Arrays.asList(window(browser, 1), window(null, 2)));
            assertEquals("", CaptureSourceContext.read(service).appPackage);
            assertTrue(query(browser).queries.isEmpty());
            shadowOf(service).setWindows(Arrays.asList(window(browser, 1),
                    window(root(service.getPackageName()), 2), window(root(service.getPackageName()), 3)));
            assertEquals("", CaptureSourceContext.read(service).url);
            assertTrue(query(browser).queries.isEmpty());
        } finally { controller.destroy(); }
    }

    @Test public void emptyTransparentBridgeRootCanStillIdentifyUnderlyingSource() {
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityWindowInfo bridge = window(null, 2);
            shadowOf(bridge).setTitle(CaptureTriggerActivity.SOURCE_BRIDGE_TITLE);
            shadowOf(controller.get()).setWindows(Arrays.asList(bridge,
                    window(root(CHROME, field(CHROME, "url_bar", URL)), 1)));
            assertEquals(URL, CaptureSourceContext.read(controller.get()).url);
        } finally { controller.destroy(); }
    }

    @Test public void lockedScreenNeverReadsAddressFields() {
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            AccessibilityNodeInfo browser = root(CHROME, field(CHROME, "url_bar", URL));
            shadowOf(controller.get()).setWindows(Collections.singletonList(window(browser, 1)));
            shadowOf(controller.get().getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
            assertEquals("", CaptureSourceContext.read(controller.get()).appPackage);
            assertTrue(query(browser).queries.isEmpty());
        } finally { controller.destroy(); }
    }

    @Test public void serviceDoesNotSubscribeToNavigationOrReadOnEvents() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            CaptureAccessibilityService service = controller.get();
            service.setServiceInfo(new AccessibilityServiceInfo());
            AccessibilityNodeInfo browser = root(CHROME, field(CHROME, "url_bar", URL));
            shadowOf(service).setWindows(Collections.singletonList(window(browser, 1)));
            service.onServiceConnected();
            assertEquals(0, service.getServiceInfo().eventTypes);
            assertNotEquals(0, service.getServiceInfo().flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS);
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
            assertTrue(query(browser).queries.isEmpty());
            assertEquals(URL, CaptureAccessibilityService.readSourceOnce().url);
            assertEquals(1, query(browser).queries.size());
        } finally { controller.destroy(); }
    }

    @Test public void automaticSourceSurvivesStoreAndSyncContract() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureStore.CaptureRecord record = CaptureStore.save(context, null, null, null, null,
                "thought", "idea", "screen", "", CHROME, URL, "browser_address_bar_https");
        record = CaptureStore.list(context, 10).get(0);
        assertEquals(CHROME, record.sourcePackage);
        assertEquals("browser_address_bar_https", record.sourceUrlOrigin);
        JSONObject payload = ReflectionHelpers.callStaticMethod(CaptureSyncUploader.class, "metadata",
                ReflectionHelpers.ClassParameter.from(Context.class, context),
                ReflectionHelpers.ClassParameter.from(CaptureStore.CaptureRecord.class, record));
        assertEquals(CHROME, payload.getJSONObject("source").getString("app_id"));
        assertEquals(URL, payload.getJSONObject("source").getString("url"));
        assertEquals("browser_address_bar_https", payload.getJSONObject("source").getString("url_origin"));
    }

    private static AccessibilityNodeInfo root(String pkg, AccessibilityNodeInfo... fields) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        root.setPackageName(pkg);
        query(root).fields.addAll(Arrays.asList(fields));
        return root;
    }

    private static AccessibilityNodeInfo field(String pkg, String id, String text) {
        AccessibilityNodeInfo field = AccessibilityNodeInfo.obtain();
        field.setPackageName(pkg);
        field.setViewIdResourceName(pkg + ":id/" + id);
        field.setText(text);
        field.setVisibleToUser(true);
        return field;
    }

    private static AccessibilityWindowInfo window(AccessibilityNodeInfo root, int layer) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadowOf(window).setLayer(layer);
        shadowOf(window).setRoot(root);
        return window;
    }

    private static NodeQueryShadow query(AccessibilityNodeInfo node) { return Shadow.extract(node); }

    @Implements(AccessibilityNodeInfo.class)
    public static class NodeQueryShadow extends ShadowAccessibilityNodeInfo {
        final List<AccessibilityNodeInfo> fields = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        @Implementation protected List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String id) {
            queries.add(id);
            List<AccessibilityNodeInfo> matches = new ArrayList<>();
            for (AccessibilityNodeInfo field : fields) {
                if (id.equals(field.getViewIdResourceName())) matches.add(AccessibilityNodeInfo.obtain(field));
            }
            return matches;
        }
    }

    @Implements(AccessibilityWindowInfo.class)
    public static class WindowQueryShadow extends ShadowAccessibilityWindowInfo {
        private AccessibilityNodeInfo root;
        @Override public void setRoot(AccessibilityNodeInfo value) { root = value; }
        @Implementation protected AccessibilityNodeInfo getRoot() { return root; }
    }

    @Implements(AccessibilityService.class)
    public static class ServiceInfoShadow extends ShadowAccessibilityService {
        private AccessibilityServiceInfo info;
        @Implementation protected void setServiceInfo(AccessibilityServiceInfo value) { info = value; }
        @Implementation protected AccessibilityServiceInfo getServiceInfo() { return info; }
    }
}
