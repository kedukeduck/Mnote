package com.codex.mnote;

import static org.junit.Assert.*;
import org.junit.Test;

public class CaptureSourceUrlTest {
    @Test public void keepsExactWebLinkIncludingQueryAndFragment() {
        String url = "https://m.weibo.cn/detail/123456789?from=share#comments";
        assertEquals(url, CaptureSourceUrl.clean("  " + url + "  "));
        assertEquals("http://t.cn/A1234", CaptureSourceUrl.clean("http://t.cn/A1234"));
    }

    @Test public void extractsSingleLinkFromShareTemplateWithoutResolvingIt() {
        assertEquals("https://m.weibo.cn/detail/123456789",
                CaptureSourceUrl.fromSharedText("分享一条动态：https://m.weibo.cn/detail/123456789，来自微博"));
        assertEquals("https://example.com/article",
                CaptureSourceUrl.fromSharedText("看看这篇文章（https://example.com/article）"));
    }

    @Test public void multipleDifferentLinksAreNotGuessed() {
        assertEquals("", CaptureSourceUrl.fromSharedText("作者 https://example.com/u/1 原帖 https://example.com/p/2"));
        assertEquals("https://example.com/a", CaptureSourceUrl.fromSharedText(
                "原帖 https://example.com/a 再附一次 https://example.com/a"));
    }

    @Test public void rejectsUnsafeOrNonWebSchemesAndEmbeddedCredentials() {
        for (String url : new String[]{"javascript:alert(1)", "file:///etc/passwd", "content://private/key",
                "intent://detail#Intent;scheme=sinaweibo;end", "sinaweibo://detail?id=1",
                "https://user:password@example.com/post", "https:///missing-host", "https://example.com/a\nb",
                "https://example.com/a b", "https://example.com/\\evil"}) {
            assertEquals(url, "", CaptureSourceUrl.clean(url));
        }
    }

    @Test public void emptyOversizedAndScreenshotTextNeverBecomePageUrls() {
        assertEquals("", CaptureSourceUrl.fromSharedText("微博 某个人的动态"));
        assertEquals("", CaptureSourceUrl.clean(null));
        assertEquals("", CaptureSourceUrl.clean("https://example.com/" + "a".repeat(8192)));
        assertEquals("", CaptureSourceUrl.fromSharedText("a".repeat(100001)));
    }
}
