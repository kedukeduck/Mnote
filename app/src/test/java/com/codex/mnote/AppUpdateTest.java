package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import androidx.core.content.FileProvider;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AppUpdateTest {
    private JSONObject release(String version) throws Exception {
        String name = "Mnote-Android-" + version + ".apk", tag = "mnote-android-v" + version;
        return new JSONObject()
            .put("tag_name", tag)
            .put("draft", false)
            .put("prerelease", version.endsWith("-test"))
            .put("body", "更新说明")
            .put("assets",
                new JSONArray().put(new JSONObject()
                        .put("name", name)
                        .put("browser_download_url",
                            "https://chenyu.online/heartnote-capture/updates/files/" + tag + "/"
                                + name)
                        .put("size", 100)
                        .put("digest",
                            "sha256:"
                                + "a".repeat(64))));
    }
    private AppRelease selected() throws Exception {
        return AppRelease.select(
            new JSONArray().put(release("1.7.0-test")).toString(), "1.6.0-test");
    }
    @Test
    public void comparesNumericallyAndStableWins() throws Exception {
        assertTrue(AppRelease.compare("1.10.0-test", "1.9.9-test") > 0);
        assertTrue(AppRelease.compare("1.7.0", "1.7.0-test") > 0);
        assertEquals(0, AppRelease.compare("1.7.0-test", "1.7.0-test"));
        for (String bad : new String[] {"1.7", "01.7.0", "1.7.0-beta", "../1.7.0", "1000000.0.0"})
            assertThrows(Exception.class, () -> AppRelease.compare(bad, "1.0.0"));
    }
    @Test
    public void filtersPlatformDraftChannelAndNeverDowngrades() throws Exception {
        JSONArray feed =
            new JSONArray()
                .put(release("1.9.0-test"))
                .put(release("1.10.0-test"))
                .put(release("9.0.0-test").put("draft", true))
                .put(release("99.0.0-test").put("tag_name", "mnote-windows-v99.0.0-test"));
        assertEquals("1.10.0-test", AppRelease.select(feed.toString(), "1.6.0-test").version);
        assertNull(AppRelease.select(feed.toString(), "1.10.0-test"));
        assertNull(AppRelease.select(feed.toString(), "1.6.0"));
        feed.put(release("1.7.0"));
        assertEquals("1.7.0", AppRelease.select(feed.toString(), "1.6.0").version);
        assertEquals("1.7.0",
            AppRelease.select(new JSONArray().put(release("1.7.0")).toString(), "1.7.0-test")
                .version);
    }
    @Test
    public void exactAssetAndDigestAreMandatory() throws Exception {
        for (String key : new String[] {"browser_download_url", "digest", "size"}) {
            JSONObject r = release("1.7.0-test"), asset = r.getJSONArray("assets").getJSONObject(0);
            asset.put(key,
                key.equals("size") ? AppRelease.MAX_PACKAGE + 1 : "https://attacker.test/evil.apk");
            assertThrows(Exception.class,
                () -> AppRelease.select(new JSONArray().put(r).toString(), "1.6.0-test"));
        }
        JSONObject wrong = release("1.7.0-test");
        wrong.getJSONArray("assets").getJSONObject(0).put("name", "wrong.apk");
        assertNull(AppRelease.select(new JSONArray().put(wrong).toString(), "1.6.0-test"));
        assertThrows(Exception.class, () -> AppRelease.select("{}", "1.6.0-test"));
        AppRelease unsafe = new AppRelease("1.7.0-test", selected().url, "../escape", "", 100);
        assertThrows(Exception.class, unsafe::validate);
    }
    @Test
    public void onlyPinnedHttpsRedirectHostsAllowed() throws Exception {
        assertTrue(AppRelease.allowedDownload(selected().url));
        assertFalse(AppRelease.allowedDownload("https://release-assets.githubusercontent.com/a?sig=temporary"));
        assertFalse(AppRelease.allowedDownload("https://github.com/kedukeduck/Mnote/releases/download/tag/a.apk"));
        for(String suffix:new String[]{"?token=secret","#fragment","/../secret","%2f.."})
            assertFalse(AppRelease.allowedDownload(selected().url+suffix));
        for (String bad : new String[] {"http://github.com/kedukeduck/Mnote/releases/download/a",
                 "https://github.com.evil.test/a",
                 "https://user:pass@release-assets.githubusercontent.com/a",
                 "https://release-assets.githubusercontent.com:444/a",
                 "https://github.com/other/repo/releases/download/a",
                 "https://release-assets.githubusercontent.com/a#fragment", "file:///tmp/evil.apk",
                 "https://evil.test/a", "https://github.com\\@evil.test/a"})
            assertFalse(bad, AppRelease.allowedDownload(bad));
    }
    @SuppressWarnings("deprecation")
    private PackageInfo info(int code, String signer) {
        PackageInfo info = new PackageInfo();
        info.packageName = "com.codex.mnote";
        info.versionName = "1.7.0-test";
        info.versionCode = code;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.minSdkVersion = 26;
        Signature[] signatures = {new Signature(signer)};
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            shadowOf(info.signingInfo).setSignatures(signatures);
        } else
            info.signatures = signatures;
        return info;
    }
    @Test
    @Config(sdk = {27, 35})
    public void acceptsSameSignerAndNewerPackageOnly() throws Exception {
        AppUpdateClient.verifyIdentity(
            "com.codex.mnote", info(20, "aabb"), info(21, "aabb"), selected());
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(20, "aabb"), info(21, "ccdd"), selected()));
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(21, "aabb"), info(20, "aabb"), selected()));
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(21, "aabb"), info(21, "aabb"), selected()));
        PackageInfo wrong = info(21, "aabb");
        wrong.packageName = "other.app";
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(20, "aabb"), wrong, selected()));
        wrong.packageName = "com.codex.mnote";
        wrong.applicationInfo.minSdkVersion = 99;
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(20, "aabb"), wrong, selected()));
        wrong.applicationInfo.minSdkVersion = 26;
        wrong.versionName = "9.0.0-test";
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(20, "aabb"), wrong, selected()));
        assertThrows(Exception.class,
            ()
                -> AppUpdateClient.verifyIdentity(
                    "com.codex.mnote", info(20, "aabb"), null, selected()));
    }
    @Test
    public void rejectsTruncatedAndCorruptPackageBeforeParsing() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getCacheDir(), "corrupt.apk");
        Files.write(file.toPath(), new byte[100]);
        assertThrows(Exception.class, () -> AppUpdateClient.verify(context, file, selected()));
        Files.write(file.toPath(), new byte[10]);
        assertThrows(Exception.class, () -> AppUpdateClient.verify(context, file, selected()));
    }
    @Test
    public void providerExposesOnlyUpdateCacheAndHomeHasEntry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("content",
            FileProvider
                .getUriForFile(context, "com.codex.mnote.updates",
                    new File(context.getCacheDir(), "updates/test.apk"))
                .getScheme());
        assertThrows(IllegalArgumentException.class,
            ()
                -> FileProvider.getUriForFile(context, "com.codex.mnote.updates",
                    new File(context.getFilesDir(), "account-session")));
        android.content.pm.ProviderInfo provider =
            context.getPackageManager().resolveContentProvider("com.codex.mnote.updates", 0);
        assertFalse(provider.exported);
        assertTrue(provider.grantUriPermissions);
        try (var controller = Robolectric.buildActivity(CaptureInboxActivity.class).setup()) {
            controller.get().findViewById(R.id.capture_settings_button).performClick();
            assertEquals(SettingsActivity.class.getName(),
                shadowOf(controller.get()).getNextStartedActivity().getComponent().getClassName());
        }
    }
}
