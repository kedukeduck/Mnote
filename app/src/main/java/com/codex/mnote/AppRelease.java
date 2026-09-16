package com.codex.mnote;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public release metadata only. Never accepts account-server supplied update URLs. */
final class AppRelease {
    static final String API = "https://chenyu.online/heartnote-capture/updates/releases.json";
    static final String PAGE = "https://chenyu.online/heartnote-capture/updates/";
    static final long MAX_PACKAGE = 128L * 1024 * 1024;
    private static final Pattern VERSION =
        Pattern.compile("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})(-test)?");
    final String version, url, sha256, notes;
    final long size;
    AppRelease(String version, String url, String sha256, String notes, long size) {
        this.version = version;
        this.url = url;
        this.sha256 = sha256;
        this.notes = notes;
        this.size = size;
    }
    void validate() throws IOException {
        compare(version, version);
        String expected = "https://chenyu.online/heartnote-capture/updates/files/mnote-android-v"
            + version + "/Mnote-Android-" + version + ".apk";
        if (!expected.equals(url) || !sha256.matches("[a-f0-9]{64}") || size <= 0
            || size > MAX_PACKAGE)
            throw new IOException("invalid_release");
    }
    static int compare(String a, String b) throws IOException {
        Matcher x = VERSION.matcher(a), y = VERSION.matcher(b);
        if (!x.matches() || !y.matches())
            throw new IOException("invalid_version");
        for (int i = 1; i <= 3; i++) {
            int n = Integer.compare(Integer.parseInt(x.group(i)), Integer.parseInt(y.group(i)));
            if (n != 0)
                return n;
        }
        return Boolean.compare(x.group(4) == null, y.group(4) == null);
    }
    static AppRelease select(String json, String current) throws Exception {
        compare(current, current);
        JSONArray releases = new JSONArray(json);
        AppRelease best = null;
        if (releases.length() > 100)
            throw new IOException("invalid_release");
        for (int i = 0; i < releases.length(); i++) {
            JSONObject item = releases.getJSONObject(i);
            String tag = item.optString("tag_name");
            if (item.optBoolean("draft") || !tag.startsWith("mnote-android-v"))
                continue;
            String version = tag.substring("mnote-android-v".length());
            if (!VERSION.matcher(version).matches())
                continue;
            if (!current.endsWith("-test")
                && (item.optBoolean("prerelease") || version.endsWith("-test")))
                continue;
            if (compare(version, current) <= 0
                || (best != null && compare(version, best.version) <= 0))
                continue;
            String name = "Mnote-Android-" + version + ".apk",
                   url =
                       "https://chenyu.online/heartnote-capture/updates/files/" + tag + "/" + name;
            JSONArray assets = item.optJSONArray("assets");
            if (assets == null)
                continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                if (!name.equals(asset.optString("name")))
                    continue;
                String digest = asset.optString("digest");
                long size = asset.optLong("size", 0);
                if (!url.equals(asset.optString("browser_download_url"))
                    || !digest.matches("sha256:[a-f0-9]{64}") || size <= 0 || size > MAX_PACKAGE)
                    throw new IOException("invalid_release");
                String notes = item.optString("body");
                if (notes.length() > 12000)
                    notes = notes.substring(0, 12000) + "\n…";
                best = new AppRelease(version, url, digest.substring(7), notes, size);
            }
        }
        return best;
    }
    static boolean allowedDownload(String value) {
        try {
            URI u = new URI(value);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null
                && u.getFragment() == null && (u.getPort() == -1 || u.getPort() == 443)
                && u.getRawQuery() == null && "chenyu.online".equals(u.getHost())
                && u.getRawPath().matches("/heartnote-capture/updates/files/mnote-android-v[0-9]+\\.[0-9]+\\.[0-9]+(-test)?/Mnote-Android-[0-9]+\\.[0-9]+\\.[0-9]+(-test)?\\.apk");
        } catch (Exception error) {
            return false;
        }
    }
}
