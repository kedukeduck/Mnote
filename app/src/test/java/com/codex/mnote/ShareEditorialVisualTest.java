package com.codex.mnote;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Isolated visual fixtures: no account, publishing, or private records. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "zh-rCN-w390dp-h844dp-mdpi")
public class ShareEditorialVisualTest {
    @Test public void captureEditorialFixtures() throws Exception {
        Bitmap qr = ShareCardRenderer.qr("https://chenyu.online/heartnote-capture/c/" + "a".repeat(64));
        Bitmap screenshot = BitmapFactory.decodeFile("../docs/design/mixed-record-cards-materials.png");
        assertNotNull("The screenshot fixture must be the real native synthetic-record UI", screenshot);
        Bitmap crop = Bitmap.createBitmap(screenshot, 22, 313, 346, 282);
        for (int mask : new int[]{2, 3, 1, 15}) {
            var result = ShareCardRenderer.render(
                    "记录，不只是保存信息。\n也保留那些被触动的时刻。",
                    "想把每周回顾变成习惯。\n不只看收藏了什么，\n也看看自己为什么会被触动。",
                    crop, screenshot, mask, qr,
                    RuntimeEnvironment.getApplication().getResources().getFont(R.font.noto_serif_cjk));
            assertEquals("", result.error);
            File dir = new File("build/ui-previews");
            assertTrue(dir.isDirectory() || dir.mkdirs());
            try (var out = new FileOutputStream(new File(dir, "share-editorial-" + mask + ".png"))) {
                assertTrue(result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            result.bitmap.recycle();
        }
        qr.recycle();
        screenshot.recycle();
        crop.recycle();
    }
}
