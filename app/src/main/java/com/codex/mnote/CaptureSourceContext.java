package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One-shot source snapshot. Never reads page text, clicks controls or caches navigation. */
final class CaptureSourceContext {
    static final CaptureSourceContext EMPTY = new CaptureSourceContext("", "", "");
    private static final CaptureSourceContext BLOCKED = new CaptureSourceContext("", "", "", false);
    final String appPackage;
    final String url;
    final String origin;
    final boolean allowClickFallback;

    CaptureSourceContext(String appPackage, String url, String origin) {
        this(appPackage, url, origin, true);
    }

    private CaptureSourceContext(String appPackage, String url, String origin, boolean allowClickFallback) {
        this.appPackage = appPackage == null ? "" : appPackage;
        this.url = CaptureSourceUrl.clean(url);
        this.origin = this.url.isEmpty() ? "" : origin;
        this.allowClickFallback = allowClickFallback;
    }

    String appLabel(Context context) { return appLabel(context, appPackage); }

    void attachTo(Intent intent) {
        intent.putExtra("mnote.source.package", appPackage)
                .putExtra("mnote.source.url", url).putExtra("mnote.source.origin", origin)
                .putExtra("mnote.source.time", SystemClock.elapsedRealtime());
    }

    static CaptureSourceContext fromClick(Intent intent) {
        long age = SystemClock.elapsedRealtime() - intent.getLongExtra("mnote.source.time", -10_000L);
        if (age < 0 || age > 2_000L) return EMPTY;
        return new CaptureSourceContext(intent.getStringExtra("mnote.source.package"),
                intent.getStringExtra("mnote.source.url"), intent.getStringExtra("mnote.source.origin"));
    }

    private static CaptureSourceContext activeRoot(AccessibilityService service) {
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return EMPTY;
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (pkg.isEmpty() || pkg.equals(service.getPackageName()) || pkg.equals("com.android.systemui")) return EMPTY;
            return fromRoot(root);
        } catch (RuntimeException ignored) { return EMPTY; }
        finally { if (root != null) root.recycle(); }
    }

    static String appLabel(Context context, String appPackage) {
        if (appPackage.isEmpty()) return context.getString(R.string.capture_source_unknown);
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(appPackage, 0)).toString();
        } catch (Exception ignored) { return appPackage; }
    }

    static CaptureSourceContext read(AccessibilityService service) {
        KeyguardManager lock = service.getSystemService(KeyguardManager.class);
        if (lock != null && lock.isKeyguardLocked()) return BLOCKED;
        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        try {
            windows.addAll(service.getWindows());
            windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
            boolean skippedBridge = false;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    // A contentless transparent bridge may have no accessibility root.
                    // Other unreadable app windows remain a hard attribution boundary.
                    if (!skippedBridge && CaptureTriggerActivity.SOURCE_BRIDGE_TITLE.contentEquals(
                            window.getTitle() == null ? "" : window.getTitle())) {
                        skippedBridge = true;
                        continue;
                    }
                    return activeRoot(service);
                }
                try {
                    String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
                    if (pkg.equals(service.getPackageName()) && !skippedBridge) {
                        skippedBridge = true; // Transparent trigger above the source app.
                        continue;
                    }
                    // OEMs may expose Quick Settings as an application window.
                    // It is capture chrome, not the app visible underneath it.
                    if (pkg.equals("com.android.systemui")) continue;
                    if (pkg.isEmpty()) return activeRoot(service);
                    if (pkg.equals(service.getPackageName())) return BLOCKED;
                    return fromRoot(root);
                } finally { root.recycle(); }
            }
        } catch (RuntimeException ignored) {
            // Revoked/reconnecting content access must not break screenshots.
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        return activeRoot(service);
    }

    static CaptureSourceContext fromRoot(AccessibilityNodeInfo root) {
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        try { return readAddress(root, pkg); }
        catch (RuntimeException ignored) {
            // URL lookup failure must not discard an already-known source app.
            return new CaptureSourceContext(pkg, "", "");
        }
    }

    private static CaptureSourceContext readAddress(AccessibilityNodeInfo root, String pkg) {
        Set<String> candidates = new LinkedHashSet<>();
        boolean inferred = false;
        for (String id : addressIds(pkg)) {
            List<AccessibilityNodeInfo> fields = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
            for (AccessibilityNodeInfo field : fields) {
                try {
                    if (!field.isVisibleToUser() || field.isPassword() || field.isFocused()
                            || !pkg.contentEquals(field.getPackageName() == null ? "" : field.getPackageName())) continue;
                    String raw = field.getText() == null ? "" : field.getText().toString().trim();
                    String url = addressUrl(raw);
                    if (!url.isEmpty()) {
                        candidates.add(url);
                        inferred |= !raw.regionMatches(true, 0, "http://", 0, 7)
                                && !raw.regionMatches(true, 0, "https://", 0, 8);
                    }
                } finally { field.recycle(); }
            }
        }
        return new CaptureSourceContext(pkg, candidates.size() == 1 ? candidates.iterator().next() : "",
                inferred ? "browser_address_bar_https" : "browser_address_bar");
    }

    static String[] addressIds(String pkg) {
        switch (pkg) {
            case "com.android.chrome":
            case "com.chrome.beta":
            case "com.chrome.dev":
            case "com.chrome.canary":
            case "com.microsoft.emmx":
            case "com.brave.browser":
                return new String[]{"url_bar"};
            case "org.mozilla.firefox":
            case "org.mozilla.firefox_beta":
            case "org.mozilla.fenix":
                return new String[]{"mozac_browser_toolbar_url_view"};
            default: return new String[0];
        }
    }

    static String addressUrl(String raw) {
        if (raw == null || raw.contains("…") || raw.contains("...")) return "";
        String exact = CaptureSourceUrl.clean(raw);
        if (!exact.isEmpty()) return exact;
        // Preserve displayed path/query; record the scheme assumption rather than hide it.
        String candidate = CaptureSourceUrl.clean("https://" + raw);
        try {
            String host = new URI(candidate).getHost();
            return host != null && host.contains(".") && !raw.contains("://") ? candidate : "";
        } catch (Exception ignored) { return ""; }
    }
}
