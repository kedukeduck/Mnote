package com.codex.mnote;

import android.graphics.*;
import android.text.*;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

/** Measure once: preview, full-size reader and PNG export share the same immutable layout. */
final class ShareCardRenderer {
    static final int WIDTH = 1080, HEIGHT = 1920, PREVIEW_HEIGHT = 4096, MAX_HEIGHT = 120000;
    static final int QUOTE = 1, THOUGHT = 2, CROP = 4, CONTEXT = 8, ORIGINAL = 16, SOURCE = 32,
                     ANNOTATED = 64;
    static final int PAPER = Color.rgb(247, 242, 232), INK = Color.rgb(48, 46, 40),
                     ACCENT = Color.rgb(169, 80, 53);
    private static final int MARGIN = 68, CONTENT_WIDTH = 944, NOTICE = 44;
    static final class Result {
        final Bitmap bitmap;
        final String error;
        final Document document;
        Result(Bitmap bitmap, String error) {
            this(bitmap, error, null);
        }
        Result(Bitmap bitmap, String error, Document document) {
            this.bitmap = bitmap;
            this.error = error;
            this.document = document;
        }
    }
    private static final class Block {
        int kind;
        StaticLayout text;
        Bitmap image;
        float height, desired, minimum, weight;
        boolean partial;
    }
    static final class Document {
        final int height;
        final boolean quoteTruncated, contextCropped, compact;
        final String displayedQuote, fullThought, summary;
        final float contextPosition;
        private final List<Block> blocks;
        private final Bitmap qr;
        private final int mask, top, gap, heading;
        private final float qrTop;
        Document(List<Block> blocks, Bitmap qr, int mask, int top, int gap, int heading, int height,
            float qrTop, boolean compact, float position, String thought) {
            this.blocks = Collections.unmodifiableList(blocks);
            this.qr = qr;
            this.mask = mask;
            this.top = top;
            this.gap = gap;
            this.heading = heading;
            this.height = height;
            this.qrTop = qrTop;
            this.compact = compact;
            contextPosition = position;
            Block q = find(blocks, QUOTE), c = find(blocks, CONTEXT);
            quoteTruncated = q != null && q.partial;
            contextCropped = c != null && c.partial;
            displayedQuote = q == null ? "" : q.text.getText().toString();
            Block own = find(blocks, THOUGHT);
            fullThought = own == null ? "" : own.text.getText().toString();
            List<String> details = new ArrayList<>();
            details.add(height > HEIGHT    ? "已生成长图"
                    : height < HEIGHT - 80 ? "已收为短卡片"
                                           : "已适配一屏");
            if ((mask & THOUGHT) != 0)
                details.add("想法完整");
            if (quoteTruncated)
                details.add("摘录部分展示");
            if (contextCropped)
                details.add("页面截图局部");
            summary = String.join(" · ", details);
        }
        void draw(Canvas canvas) {
            canvas.drawColor(PAPER);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColor(INK);
            paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            paint.setTextSize(46);
            canvas.drawText("Mnote", MARGIN, 91, paint);
            paint.setColor(0xffc9bdae);
            paint.setStrokeWidth(1.5f);
            canvas.drawLine(MARGIN, 120, WIDTH - MARGIN, 120, paint);
            float y = top;
            for (Block b : blocks) {
                if (b.image != null) {
                    float imageHeight = b.height - (b.partial ? NOTICE : 0);
                    if (b.kind == CONTEXT && b.partial) {
                        RectF source = contextSource(b.image, imageHeight, contextPosition);
                        canvas.save();
                        canvas.clipRect(MARGIN, y, WIDTH - MARGIN, y + imageHeight);
                        float scale = (float) CONTENT_WIDTH / b.image.getWidth();
                        canvas.drawBitmap(b.image, null,
                            new RectF(MARGIN, y - source.top * scale, WIDTH - MARGIN,
                                y - source.top * scale + b.image.getHeight() * scale),
                            paint);
                        canvas.restore();
                    } else {
                        float scale = Math.min((float) CONTENT_WIDTH / b.image.getWidth(),
                            imageHeight / b.image.getHeight());
                        float w = b.image.getWidth() * scale, h = b.image.getHeight() * scale;
                        canvas.drawBitmap(b.image, null,
                            new RectF((WIDTH - w) / 2, y, (WIDTH + w) / 2, y + h), paint);
                    }
                    if (b.partial)
                        notice(canvas, paint, "页面截图 · 局部预览", y + imageHeight + 32);
                } else {
                    paint.setColor(ACCENT);
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(32);
                    if (b.kind == QUOTE) {
                        paint.setTypeface(Typeface.create("serif", Typeface.BOLD));
                        paint.setTextSize(54);
                        canvas.drawText("“", MARGIN, y + 44, paint);
                        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                        paint.setTextSize(32);
                        canvas.drawText("摘录", MARGIN + 58, y + 39, paint);
                    } else {
                        if (y > top)
                            canvas.drawLine(MARGIN, y - 12, WIDTH - MARGIN, y - 12, paint);
                        canvas.drawText("我的想法", MARGIN, y + 39, paint);
                    }
                    canvas.save();
                    canvas.translate(MARGIN, y + heading);
                    b.text.draw(canvas);
                    canvas.restore();
                    if (b.partial)
                        notice(canvas, paint, "摘录未完整展示", y + b.height - 12);
                }
                y += b.height + gap;
            }
            if (qr != null) {
                paint.setColor(0xffbd9e86);
                canvas.drawLine(MARGIN, qrTop - 24, WIDTH - MARGIN, qrTop - 24, paint);
                paint.setFilterBitmap(false);
                canvas.drawBitmap(qr, MARGIN, qrTop, paint);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setColor(INK);
                paint.setTextSize(38);
                String label = (mask & 48) == 48 ? "查看原文与来源"
                    : (mask & ORIGINAL) != 0     ? "查看保存的原文"
                                                 : "打开来源";
                canvas.drawText(label, MARGIN + 286, qrTop + 100, paint);
                paint.setColor(0xff746a60);
                paint.setTextSize(30);
                canvas.drawText("长按识别或扫码", MARGIN + 286, qrTop + 154, paint);
            }
        }
        void drawContextPreview(Canvas canvas, int width, int height) {
            Block page = find(blocks, CONTEXT);
            if (page == null)
                return;
            float viewport = page.height - (page.partial ? NOTICE : 0);
            float scale = Math.min((float) width / CONTENT_WIDTH, height / viewport);
            float left = (width - CONTENT_WIDTH * scale) / 2, top = (height - viewport * scale) / 2;
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            canvas.clipRect(0, 0, CONTENT_WIDTH, viewport);
            RectF source = contextSource(page.image, viewport, contextPosition);
            float imageScale = (float) CONTENT_WIDTH / page.image.getWidth();
            canvas.drawBitmap(page.image, null,
                new RectF(0, -source.top * imageScale, CONTENT_WIDTH,
                    (page.image.getHeight() - source.top) * imageScale),
                new Paint(Paint.FILTER_BITMAP_FLAG));
            canvas.restore();
        }
        private static void notice(Canvas c, Paint p, String text, float baseline) {
            p.setColor(0xff746a60);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(28);
            c.drawText(text, MARGIN, baseline, p);
        }
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
        return render(quote, thought, crop, context, mask, qr, serif, 0);
    }
    static Result render(String quote, String thought, Bitmap crop, Bitmap context, int mask,
        Bitmap qr, Typeface serif, float position) {
        if ((mask & 63) == 0)
            return new Result(null, "请选择至少一个分享模块。");
        if (((mask & CROP) != 0 && crop == null) || ((mask & CONTEXT) != 0 && context == null))
            return new Result(null, "所选截图无法读取，请重新打开记录或取消该图片模块。");
        if ((mask & 48) != 0 && qr == null)
            return new Result(null, "二维码不可用，请登录并同步记录，或取消原文和来源。");
        if ((mask & THOUGHT) != 0 && thought.length() > 100000)
            return new Result(
                null, "想法超过单张长图的安全生成上限，未截断或保存；原记录保持完整。");
        Document doc = layout(quote, thought, crop, context, mask, (mask & 48) != 0 ? qr : null,
            serif, position, false);
        if (doc.height > MAX_HEIGHT)
            return new Result(
                null, "想法超过单张长图的安全生成上限，未截断或保存；原记录保持完整。");
        float scale = Math.min(1, (float) PREVIEW_HEIGHT / doc.height);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(WIDTH * scale)),
            Math.max(1, Math.round(doc.height * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        doc.draw(canvas);
        return new Result(bitmap, "", doc);
    }
    private static Document layout(String quote, String thought, Bitmap crop, Bitmap context,
        int mask, Bitmap qr, Typeface serif, float position, boolean compact) {
        int heading = compact ? 56 : 64, gap = compact ? 22 : 30, top = compact ? 150 : 170;
        int moduleCount = Integer.bitCount(mask & 15);
        int singleImageBudget = HEIGHT - top - MARGIN - (qr == null ? 0 : 292);
        List<Block> blocks = new ArrayList<>(), optional = new ArrayList<>();
        if ((mask & QUOTE) != 0) {
            Block b = new Block();
            b.kind = QUOTE;
            // Bound measurement work for huge excerpts, never the stored record or thought.
            String prefix = quote.substring(0, safeBoundary(quote, Math.min(6000, quote.length())));
            b.text = text(prefix, compact ? 74 : 78, serif);
            b.desired = heading + b.text.getHeight();
            b.minimum = Math.min(b.desired, heading + b.text.getLineBottom(0) + NOTICE);
            b.weight = 3;
            blocks.add(b);
            optional.add(b);
        }
        if ((mask & CROP) != 0) {
            Block b = image(CROP, crop, moduleCount == 1 ? singleImageBudget : 700, 3);
            blocks.add(b);
            optional.add(b);
        }
        if ((mask & THOUGHT) != 0) {
            Block b = new Block();
            b.kind = THOUGHT;
            b.text = text(thought, 52, Typeface.create("sans-serif", Typeface.NORMAL));
            b.height = heading + b.text.getHeight();
            blocks.add(b);
        }
        if ((mask & CONTEXT) != 0) {
            Block b = image(CONTEXT, context,
                moduleCount == 1       ? singleImageBudget - NOTICE
                    : moduleCount == 2 ? 900
                                       : 560,
                1);
            blocks.add(b);
            optional.add(b);
        }
        Block own = find(blocks, THOUGHT);
        float fixed = top + MARGIN + (qr == null ? 0 : 292) + gap * Math.max(0, blocks.size() - 1)
            + (own == null ? 0 : own.height);
        float natural = fixed;
        for (Block b : optional) natural += b.desired;
        if (!compact && natural > HEIGHT && natural <= HEIGHT + 200)
            return layout(quote, thought, crop, context, mask, qr, serif, position, true);
        float minimum = 0;
        for (Block b : optional) {
            b.height = b.minimum;
            minimum += b.minimum;
        }
        distribute(optional, Math.max(0, HEIGHT - fixed - minimum));
        Block q = find(blocks, QUOTE);
        if (q != null) {
            q.partial = q.text.getText().length() < quote.length()
                || heading + q.text.getHeight() > q.height;
            if (q.partial) {
                int maxHeight = Math.max(0, (int) q.height - heading - NOTICE), end = 0;
                for (int line = 0;
                    line < q.text.getLineCount() && q.text.getLineBottom(line) <= maxHeight; line++)
                    end = q.text.getLineEnd(line);
                end = excerptEnd(quote, end);
                q.text = text(quote.substring(0, end), compact ? 74 : 78, serif);
            }
            float old = q.height;
            q.height = heading + q.text.getHeight() + (q.partial ? NOTICE : 0);
            List<Block> images = new ArrayList<>();
            for (Block b : optional)
                if (b.image != null)
                    images.add(b);
            distribute(images, Math.max(0, old - q.height));
        }
        Block page = find(blocks, CONTEXT);
        if (page != null) {
            float full = (float) CONTENT_WIDTH * page.image.getHeight() / page.image.getWidth();
            page.partial = full > page.height + 0.5f;
            if (!page.partial)
                page.height = full;
        }
        float end = top;
        for (Block b : blocks) end += b.height;
        end += gap * Math.max(0, blocks.size() - 1);
        int height = (int) Math.ceil(end + MARGIN + (qr == null ? 0 : 292) - 0.01f);
        return new Document(blocks, qr, mask, top, gap, heading, height, end + 40, compact,
            Float.isFinite(position) ? Math.max(0, Math.min(1, position)) : 0, thought);
    }
    private static Block image(int kind, Bitmap bitmap, int cap, float weight) {
        Block b = new Block();
        b.kind = kind;
        b.image = bitmap;
        b.weight = weight;
        float full = (float) CONTENT_WIDTH * bitmap.getHeight() / bitmap.getWidth();
        b.desired = Math.min(cap, full) + (kind == CONTEXT && full > cap ? NOTICE : 0);
        b.minimum = Math.min(b.desired, kind == CROP ? 180 : 224);
        return b;
    }
    private static void distribute(List<Block> blocks, float extra) {
        for (int pass = 0; pass < blocks.size() + 1 && extra > 0.1f; pass++) {
            float weights = 0;
            for (Block b : blocks)
                if (b.height < b.desired - 0.1f)
                    weights += b.weight;
            if (weights == 0)
                break;
            float spent = 0;
            for (Block b : blocks)
                if (b.height < b.desired - 0.1f) {
                    float amount = Math.min(b.desired - b.height, extra * b.weight / weights);
                    b.height += amount;
                    spent += amount;
                }
            extra -= spent;
        }
    }
    private static Block find(List<Block> blocks, int kind) {
        for (Block b : blocks)
            if (b.kind == kind)
                return b;
        return null;
    }
    private static StaticLayout text(String value, float size, Typeface face) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(INK);
        p.setTextSize(size);
        p.setTypeface(face);
        return StaticLayout.Builder.obtain(value, 0, value.length(), p, CONTENT_WIDTH)
            .setIncludePad(false)
            .setLineSpacing(4, 1.04f)
            .build();
    }
    static int safeBoundary(String value, int end) {
        if (end <= 0)
            return 0;
        if (end >= value.length())
            return value.length();
        android.icu.text.BreakIterator boundary =
            android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT);
        boundary.setText(value);
        return boundary.isBoundary(end) ? end : Math.max(0, boundary.preceding(end));
    }
    static int excerptEnd(String value, int limit) {
        int end = safeBoundary(value, Math.min(limit, value.length()));
        for (int i = end - 1; i >= 0; i--)
            if (value.charAt(i) == '\n' && i > 0)
                return safeBoundary(value, value.charAt(i - 1) == '\r' ? i - 1 : i);
        for (int i = end - 1; i >= 0; i--)
            if ("。！？；".indexOf(value.charAt(i)) >= 0
                || (".!?;".indexOf(value.charAt(i)) >= 0
                    && (i + 1 == value.length() || Character.isWhitespace(value.charAt(i + 1)))))
                return safeBoundary(value, i + 1);
        if (end > 0 && end < value.length() && latinWord(value.codePointBefore(end))
            && latinWord(value.codePointAt(end)))
            while (end > 0 && latinWord(value.codePointBefore(end)))
                end -= Character.charCount(value.codePointBefore(end));
        return safeBoundary(value, end);
    }
    private static boolean latinWord(int cp) {
        return cp < 0x250
            && (Character.isLetterOrDigit(cp) || cp == '\'' || cp == '-' || cp == '_');
    }
    static RectF contextSource(Bitmap image, float viewportHeight, float position) {
        float visible =
            Math.min(image.getHeight(), viewportHeight * image.getWidth() / CONTENT_WIDTH);
        float top = (image.getHeight() - visible) * Math.max(0, Math.min(1, position));
        return new RectF(0, top, image.getWidth(), top + visible);
    }
}
