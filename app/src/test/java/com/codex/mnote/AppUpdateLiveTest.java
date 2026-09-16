package com.codex.mnote;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Opt-in, read-only test of the real updater transport; no installation or account access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AppUpdateLiveTest {
    @Test
    public void realFirstPartyFeedWorksWithoutGithubOrAccountCredentials() throws Exception {
        Assume.assumeTrue("1".equals(System.getenv("MNOTE_LIVE_UPDATE_CHECK")));
        AppRelease release = AppUpdateClient.check("1.8.0-test");
        assertNotNull(release);
        release.validate();
        assertTrue(
            release.url.startsWith("https://chenyu.online/heartnote-capture/updates/files/"));
        assertTrue(AppRelease.allowedDownload(release.url));
        assertNull(AppUpdateClient.check(release.version));
    }
}
