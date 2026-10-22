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

    @Test public void transcriptRecognizesMarkdownCopiesOfOneLink() {
        String url = "https://v.douyin.com/P3DO6rRvucA/";
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText(
                "8.43 复制打开抖音，看看【示例的作品】分享 [" + url + "](" + url + " \"" + url + "\") sEh:/ 08/23"));
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText("视频 <" + url + ">。"));
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText("[视频](" + url + " '" + url + "')"));
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText("`" + url + "`"));
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText(url + " 再附一次 " + url));
    }

    @Test public void transcriptDoesNotChooseBetweenDistinctMarkdownLinks() {
        assertEquals("", CaptureSourceUrl.fromTranscriptShareText(
                "[https://v.douyin.com/first/](https://v.douyin.com/second/)"));
        assertEquals("", CaptureSourceUrl.fromTranscriptShareText(
                "https://v.douyin.com/first/ https://user:secret@example.com/video"));
        assertEquals("", CaptureSourceUrl.fromTranscriptShareText("http://v.douyin.com/first/"));
        assertEquals("", CaptureSourceUrl.fromTranscriptShareText("a".repeat(8193)));
    }

    @Test public void transcriptKeepsBalancedAndEncodedParenthesesInSignedQuery() {
        String url = "https://media.example/audio.mp3?signature=abc(def)%29&token=ghi(jkl)";
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText(url));
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText("[" + url + "](" + url + ")"));
        assertEquals(url, CaptureSourceUrl.fromTranscriptShareText("(视频) [来源](" + url + ")"));
        assertEquals("https://media.example/audio.mp3?signature=abc)",
                CaptureSourceUrl.fromTranscriptShareText("https://media.example/audio.mp3?signature=abc)"));
    }

    @Test public void malformedNakedTranscriptUrlsAreNotTruncatedToAValidPrefix() {
        for (String suffix : new String[]{"[unclosed", "bad]", "`bad", "\"bad", "'bad"}) {
            assertEquals(suffix, "", CaptureSourceUrl.fromTranscriptShareText("https://v.douyin.com/test/" + suffix));
        }
        String encoded = "https://media.example/audio.mp3?signature=%5Babc%5D&token=a(b)";
        assertEquals(encoded, CaptureSourceUrl.fromTranscriptShareText(encoded));
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
