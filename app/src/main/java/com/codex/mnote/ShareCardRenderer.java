package com.codex.mnote;

import android.graphics.*;
import android.text.*;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

/** One deterministic bitmap is used by both the preview and the album writer. */
final class ShareCardRenderer {
    static final int WIDTH = 1080, HEIGHT = 1920;
    static final int QUOTE = 1, THOUGHT = 2, CROP = 4, CONTEXT = 8, ORIGINAL = 16, SOURCE = 32,
                     ANNOTATED = 64;
    static final int PAPER = Color.rgb(247, 242, 232), INK = Color.rgb(48, 46, 40),
                     ACCENT = Color.rgb(169, 80, 53);
    static final class Result {
        final Bitmap bitmap;
        final String error;
        Result(Bitmap b, String e) {
            bitmap = b;
            error = e;
        }
    }
    private static final class Block {
        StaticLayout text;
        Bitmap image;
        float height;
        boolean quote;
    }
    static Bitmap qr(String url) throws Exception {
        var matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 252, 252,
            Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        Bitmap bitmap =
            Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++)
            for (int x = 0; x < matrix.getWidth(); x++)
                pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        bitmap.setPixels(pixels, 0, matrix.getWidth(), 0, 0, matrix.getWidth(), matrix.getHeight());
        return bitmap;
    }
    static Result render(
        String quote, String thought, Bitmap crop, Bitmap context, int mask, Bitmap qr) {
        return render(
            quote, thought, crop, context, mask, qr, Typeface.create("serif", Typeface.NORMAL));
    }
    static Result render(String quote, String thought, Bitmap crop, Bitmap context, int mask,
        Bitmap qr, Typeface serif) {
        boolean withQr = (mask & (ORIGINAL | SOURCE)) != 0;
        if ((mask & 63) == 0)
            return new Result(null, "请选择至少一个分享模块。");
        if (((mask & QUOTE) != 0 && quote.length() > 8000)
            || ((mask & THOUGHT) != 0 && thought.length() > 8000))
            return new Result(null,
                "文字过长，一屏无法完整展示。请减少正文内容或取消该模块；页面原文可通过二维码分享"
                    + "。");
        if (((mask & CROP) != 0 && crop == null) || ((mask & CONTEXT) != 0 && context == null))
            return new Result(null, "所选截图无法读取，请重新打开记录或取消该图片模块。");
        if (withQr && qr == null)
            return new Result(null, "二维码不可用，请登录并同步记录，或取消原文和来源。");
        List<Block> blocks = null;
        float gap = 30, margin = 68;
        for (int step = 0; step <= 10; step++) {
            float factor = 1 - step * 0.038f;
            List<Block> candidate = new ArrayList<>();
            if ((mask & QUOTE) != 0)
                candidate.add(textBlock(quote, 78 * factor, true, serif));
            if ((mask & CROP) != 0)
                candidate.add(imageBlock(crop));
            if ((mask & THOUGHT) != 0)
                candidate.add(textBlock(thought, 52 * factor, false, serif));
            if ((mask & CONTEXT) != 0)
                candidate.add(imageBlock(context));
            float fixed = 0;
            int count = 0;
            for (Block b : candidate) {
                if (b.image != null)
                    count++;
                else
                    fixed += b.height;
            }
            float available = HEIGHT - 170 - margin - (withQr ? 290 : 0)
                - gap * Math.max(0, candidate.size() - 1) - fixed;
            if (available >= count * 190 && available >= 0) {
                float sum = 0;
                for (Block b : candidate)
                    if (b.image != null)
                        sum += Math.min(
                            620, (WIDTH - 2 * margin) * b.image.getHeight() / b.image.getWidth());
                for (Block b : candidate)
                    if (b.image != null) {
                        float ideal = Math.min(
                            620, (WIDTH - 2 * margin) * b.image.getHeight() / b.image.getWidth());
                        b.height = Math.min(
                            ideal, 190 + (available - count * 190) * ideal / Math.max(1, sum));
                    }
                blocks = candidate;
                break;
            }
        }
        if (blocks == null)
            return new Result(
                null, "内容超出一屏可读范围，请减少分享模块或缩短正文。没有截断任何内容。");
        Bitmap out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(PAPER);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColor(INK);
        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(46);
        canvas.drawText("Mnote", margin, 91, paint);
        paint.setColor(0xffc9bdae);
        paint.setStrokeWidth(1.5f);
        canvas.drawLine(margin, 120, WIDTH - margin, 120, paint);
        float y = 170;
        for (Block b : blocks) {
            if (b.image != null) {
                float scale = Math.min(
                    (WIDTH - 2 * margin) / b.image.getWidth(), b.height / b.image.getHeight());
                float w = b.image.getWidth() * scale, h = b.image.getHeight() * scale;
                canvas.drawBitmap(
                    b.image, null, new RectF((WIDTH - w) / 2, y, (WIDTH + w) / 2, y + h), paint);
            } else {
                paint.setColor(ACCENT);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setTextSize(32);
                if (b.quote) {
                    paint.setTypeface(Typeface.create("serif", Typeface.BOLD));
                    paint.setTextSize(54);
                    canvas.drawText("“", margin, y + 44, paint);
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(32);
                    canvas.drawText("摘录", margin + 58, y + 39, paint);
                } else {
                    if (y > 170)
                        canvas.drawLine(margin, y - 12, WIDTH - margin, y - 12, paint);
                    canvas.drawText("我的想法", margin, y + 39, paint);
                }
                canvas.save();
                canvas.translate(margin, y + 64);
                b.text.draw(canvas);
                canvas.restore();
            }
            y += b.height + gap;
        }
        if (withQr) {
            float top = HEIGHT - margin - 252;
            paint.setColor(0xffbd9e86);
            canvas.drawLine(margin, top - 24, WIDTH - margin, top - 24, paint);
            paint.setFilterBitmap(false);
            canvas.drawBitmap(qr, margin, top, paint);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setColor(INK);
            paint.setTextSize(38);
            String label = (mask & (ORIGINAL | SOURCE)) == (ORIGINAL | SOURCE) ? "查看原文与来源"
                : (mask & ORIGINAL) != 0                                       ? "查看保存的原文"
                                                                               : "打开来源";
            canvas.drawText(label, margin + 286, top + 100, paint);
            paint.setColor(0xff746a60);
            paint.setTextSize(30);
            canvas.drawText("长按识别或扫码", margin + 286, top + 154, paint);
        }
        return new Result(out, "");
    }
    private static Block imageBlock(Bitmap image) {
        Block b = new Block();
        b.image = image;
        return b;
    }
    private static Block textBlock(String value, float size, boolean quote, Typeface serif) {
        Block b = new Block();
        b.quote = quote;
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(INK);
        p.setTextSize(size);
        p.setTypeface(quote ? serif : Typeface.create("sans-serif", Typeface.NORMAL));
        b.text =
            StaticLayout.Builder.obtain(value, 0, value.length(), p, 944)
                .setIncludePad(false)
                .setLineSpacing(4, 1.04f)
                .build();
        b.height = 64 + b.text.getHeight();
        return b;
    }
}
