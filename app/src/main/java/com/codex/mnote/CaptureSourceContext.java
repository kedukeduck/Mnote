package com.codex.mnote;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
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
    final String appPackage;
    final String url;
    final String origin;

    CaptureSourceContext(String appPackage, String url, String origin) {
        this.appPackage = appPackage == null ? "" : appPackage;
        this.url = CaptureSourceUrl.clean(url);
        this.origin = this.url.isEmpty() ? "" : origin;
    }

    String appLabel(Context context) { return appLabel(context, appPackage); }

    static String appLabel(Context context, String appPackage) {
        if (appPackage.isEmpty()) return context.getString(R.string.capture_source_unknown);
        try {
            return context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(appPackage, 0)).toString();
        } catch (Exception ignored) { return appPackage; }
    }

    static CaptureSourceContext read(AccessibilityService service) {
        KeyguardManager lock = service.getSystemService(KeyguardManager.class);
        if (lock != null && lock.isKeyguardLocked()) return EMPTY;
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
                    return EMPTY;
                }
                try {
                    String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
                    if (pkg.equals(service.getPackageName()) && !skippedBridge) {
                        skippedBridge = true; // Transparent trigger above the source app.
                        continue;
                    }
                    if (pkg.isEmpty() || pkg.equals(service.getPackageName()) || pkg.equals("com.android.systemui")) return EMPTY;
                    return fromRoot(root);
                } finally { root.recycle(); }
            }
        } catch (RuntimeException ignored) {
            // Revoked/reconnecting content access must not break screenshots.
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        return EMPTY;
    }

    static CaptureSourceContext fromRoot(AccessibilityNodeInfo root) {
        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
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
