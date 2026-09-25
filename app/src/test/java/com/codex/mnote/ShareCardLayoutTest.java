package com.codex.mnote;

import static org.junit.Assert.*;

import android.graphics.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {30, 35}, qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
public class ShareCardLayoutTest {
    private ShareCardRenderer.Result render(
        String q, String t, Bitmap crop, Bitmap page, int mask, float position) throws Exception {
        return ShareCardRenderer.render(q, t, crop, page, mask,
            (mask & 48) != 0 ? ShareCardRenderer.qr("https://example.test/c/"
                                   + "a".repeat(64))
                             : null,
            RuntimeEnvironment.getApplication().getResources().getFont(R.font.noto_serif_cjk),
            position);
    }
    @Test
    public void shortCardsShrinkAndThoughtIsNeverTruncated() throws Exception {
        var shortCard = render("", "一个想法。", null, null, 2, 0);
        assertTrue(shortCard.document.height < 900);
        assertEquals("一个想法。", shortCard.document.fullThought);
        save(shortCard, "share-card-short.png");
        shortCard.bitmap.recycle();
        String thought =
            "想法完整保留，包括段落与表情 👩🏽‍💻。\n".repeat(45)
            + "结束标记。";
        var longCard = render("摘录。".repeat(200), thought, null, null, 3, 0);
        assertEquals("", longCard.error);
        assertTrue(longCard.document.height > 1920);
        assertEquals(thought, longCard.document.fullThought);
        ShareCardTest.assertContentOrder(longCard.document, 3);
        assertTrue(longCard.document.quoteTruncated);
        assertFalse(longCard.document.displayedQuote.isEmpty());
        assertTrue(longCard.bitmap.getHeight() <= 4096);
        save(longCard, "share-card-long-overview.png");
        longCard.bitmap.recycle();
    }
    @Test
    public void optionalModulesUseCapsAndQuoteRestoresWhenSpaceFreed() throws Exception {
        Bitmap crop = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        Bitmap page = Bitmap.createBitmap(400, 2000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(page);
        canvas.drawColor(Color.BLUE);
        Paint p = new Paint();
        p.setColor(Color.GREEN);
        canvas.drawRect(0, 1000, 400, 2000, p);
        String quote = "记录，不只是保存信息。也保留那些被触动的时刻。".repeat(4);
        var all = render(quote, ShareCardTest.THOUGHT, crop, page, 63, 0);
        assertEquals("", all.error);
        ShareCardTest.assertContentOrder(all.document, 63);
        assertTrue(all.document.quoteTruncated);
        assertTrue(all.document.contextCropped);
        assertEquals(ShareCardTest.THOUGHT, all.document.fullThought);
        assertTrue(all.document.height <= 1920);
        var freed = render(quote, "", null, null, 1, 0);
        assertTrue(freed.document.displayedQuote.length() > all.document.displayedQuote.length());
        var lower = render(quote, ShareCardTest.THOUGHT, crop, page, 63, 1);
        assertFalse(all.bitmap.sameAs(lower.bitmap));
        assertEquals(all.document.height, lower.document.height);
        RectF source = ShareCardRenderer.contextSource(page, 300, 1);
        assertEquals(2000f, source.bottom, 0.01f);
        assertTrue(source.top > 0);
        var pageOnly = render("", "", null, page, 8, 0);
        assertEquals(1920, pageOnly.document.height);
        assertTrue(pageOnly.document.contextCropped);
        pageOnly.bitmap.recycle();
        save(all, "share-card-truncated.png");
        for (var r : List.of(all, freed, lower)) r.bitmap.recycle();
        crop.recycle();
        page.recycle();
    }
    @Test
    public void boundariesPreserveParagraphsWordsAndEmojiClusters() {
        assertEquals(
            "一段话。".length(), ShareCardRenderer.excerptEnd("一段话。\n第二段还没有结束", 10));
        assertEquals(4, ShareCardRenderer.excerptEnd("一句话。下一句还未完成", 8));
        assertEquals(6, ShareCardRenderer.excerptEnd("hello extraordinary", 12));
        String emoji = "甲👩🏽‍💻乙";
        for (int i = 2; i < emoji.length() - 1; i++)
            assertEquals(1, ShareCardRenderer.safeBoundary(emoji, i));
        assertEquals("甲".length(), ShareCardRenderer.excerptEnd(emoji, 4));
    }
    @Test
    public void streamingPngMatchesRenderedCardIncludingStripBoundaries() throws Exception {
        var result = render(ShareCardTest.QUOTE, ShareCardTest.THOUGHT, null, null, 51, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ShareCardPng.write(result.document, out);
        byte[] png = out.toByteArray();
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(decoded);
        assertEquals(result.document.height, decoded.getHeight());
        assertTrue(result.bitmap.sameAs(decoded));
        decoded.recycle();
        result.bitmap.recycle();
    }
    @Test
    public void longPngHasFullHeightAndReadableFinalThoughtAndQr() throws Exception {
        String thought = "完整保留想法与上下文。\n".repeat(65) + "最后这一句，依然完整。";
        var result = render("摘录。", thought, null, null, 51, 0);
        assertTrue(result.document.height > 4096);
        assertEquals(thought, result.document.fullThought);
        ShareCardTest.assertContentOrder(result.document, 51);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ShareCardPng.write(result.document, out);
        byte[] png = out.toByteArray();
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(decoded);
        assertEquals(1080, decoded.getWidth());
        assertEquals(result.document.height, decoded.getHeight());
        // Decode the actual final QR, not the reduced overview thumbnail.
        int h = 400;
        int[] pixels = new int[1080 * h];
        decoded.getPixels(pixels, 0, 1080, 0, decoded.getHeight() - h, 1080, h);
        String url = new com.google.zxing.MultiFormatReader()
                         .decode(new com.google.zxing.BinaryBitmap(
                             new com.google.zxing.common.HybridBinarizer(
                                 new com.google.zxing.RGBLuminanceSource(1080, h, pixels))))
                         .getText();
        assertTrue(url.endsWith("a".repeat(64)));
        File dir = new File("build/ui-previews");
        dir.mkdirs();
        try (OutputStream file = new FileOutputStream(new File(dir, "share-card-long.png"))) {
            file.write(png);
        }
        decoded.recycle();
        result.bitmap.recycle();
    }
    @Test
    public void ordinaryTwentyThousandCharacterThoughtDoesNotHitOldTextLimit() throws Exception {
        String thought = "这是完整的想法内容。".repeat(2000);
        var result = render("", thought, null, null, 2, 0);
        assertEquals("", result.error);
        assertEquals(thought, result.document.fullThought);
        assertTrue(result.document.height > 4096);
        result.bitmap.recycle();
        var pathological = render("", "\n".repeat(10000), null, null, 2, 0);
        assertNull(pathological.bitmap);
        assertTrue(pathological.error.contains("未截断"));
    }
    @Test
    public void slightlyOverBudgetTightensSpacingWithoutShrinkingThoughtText() throws Exception {
        boolean compactSeen = false;
        for (int n = 1; n < 30; n++) {
            String thought = "每一条想法都需要完整保留。\n".repeat(n);
            var r = render("这是摘录的一句话。", thought, null, null, 3, 0);
            assertEquals(thought, r.document.fullThought);
            compactSeen |= r.document.compact;
            r.bitmap.recycle();
            if (compactSeen)
                break;
        }
        assertTrue(compactSeen);
    }
    private static void save(ShareCardRenderer.Result r, String name) throws Exception {
        File dir = new File("build/ui-previews");
        dir.mkdirs();
        try (OutputStream out = new FileOutputStream(new File(dir, name))) {
            r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
