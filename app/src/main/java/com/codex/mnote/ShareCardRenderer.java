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
    static final int QR_SIZE = 208, QR_GAP = 44, QR_FOOTER = QR_SIZE + QR_GAP;
    static final int QUOTE = 1, THOUGHT = 2, CROP = 4, CONTEXT = 8, ORIGINAL = 16, SOURCE = 32,
                     ANNOTATED = 64;
    static final int PAPER = 0xfff7f4ec, INK = 0xff24494e, ACCENT = 0xff955530;
    private static final int MARGIN = 68, CONTENT_WIDTH = 944, NOTICE = 44,
                             QUOTE_INSET = 34, QUOTE_PAPER = 0xffeeece3,
                             SECONDARY_INK = 0xff4c5957, MUTED_INK = 0xff6d726a;
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
        int textTop, textBottom, textInset;
        boolean partial;
    }
    static final class Document {
        final int height;
        final boolean quoteTruncated, contextCropped, compact;
        final String displayedQuote, fullThought, summary;
        final float contextPosition;
        private final List<Block> blocks;
        private final Bitmap qr;
        private final int mask, top, gap;
        private final float qrTop;
        Document(List<Block> blocks, Bitmap qr, int mask, int top, int gap, int height,
            float qrTop, boolean compact, float position, String thought) {
            this.blocks = Collections.unmodifiableList(blocks);
            this.qr = qr;
            this.mask = mask;
            this.top = top;
            this.gap = gap;
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
            paint.setTextSize(38);
            canvas.drawText("Mnote", MARGIN, 88, paint);
            paint.setColor(ACCENT);
            paint.setStrokeWidth(3);
            canvas.drawLine(MARGIN, 113, MARGIN + 44, 113, paint);
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
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(26);
                    if (b.kind == QUOTE) {
                        paint.setColor(QUOTE_PAPER);
                        canvas.drawRoundRect(
                            MARGIN, y, WIDTH - MARGIN, y + b.height, 8, 8, paint);
                        paint.setColor(ACCENT);
                        canvas.drawRect(MARGIN, y + 24, MARGIN + 3, y + b.height - 24, paint);
                        canvas.drawText("摘录", MARGIN + b.textInset, y + 45, paint);
                    } else {
                        paint.setColor(ACCENT);
                        canvas.drawText("我的想法", MARGIN, y + 27, paint);
                    }
                    canvas.save();
                    canvas.translate(MARGIN + b.textInset, y + b.textTop);
                    b.text.draw(canvas);
                    canvas.restore();
                    if (b.partial) {
                        canvas.save();
                        canvas.translate(b.textInset, 0);
                        notice(canvas, paint, "摘录未完整展示 · 扫码继续阅读",
                            y + b.height - b.textBottom - 10);
                        canvas.restore();
                    }
                }
                y += b.height + gap;
            }
            if (qr != null) {
                paint.setFilterBitmap(false);
                canvas.drawBitmap(qr, WIDTH - MARGIN - qr.getWidth(), qrTop, paint);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setColor(INK);
                paint.setTextSize(28);
                canvas.drawText("查看本次分享", MARGIN, qrTop + QR_SIZE / 2f - 6, paint);
                paint.setColor(MUTED_INK);
                paint.setTextSize(24);
                canvas.drawText("长按识别二维码", MARGIN, qrTop + QR_SIZE / 2f + 32, paint);
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
            p.setColor(MUTED_INK);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(24);
            c.drawText(text, MARGIN, baseline, p);
        }
    }
    static Bitmap qr(String url) throws Exception {
        var matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE,
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
        if (qr == null)
            return new Result(null, "二维码不可用，请先登录并同步此记录。");
        if ((mask & THOUGHT) != 0 && thought.length() > 100000)
            return new Result(
                null, "想法超过单张长图的安全生成上限，未截断或保存；原记录保持完整。");
        Document doc = layout(quote, thought, crop, context, mask, qr, serif, position, false);
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
        int gap = compact ? 26 : 40, top = compact ? 146 : 168;
        int moduleCount = Integer.bitCount(mask & 15);
        int singleImageBudget = HEIGHT - top - MARGIN - QR_FOOTER;
        List<Block> blocks = new ArrayList<>(), optional = new ArrayList<>();
        // Keep personal thoughts first and all image modules after the text.
        if ((mask & THOUGHT) != 0) {
            Block b = new Block();
            b.kind = THOUGHT;
            b.textTop = compact ? 46 : 54;
            b.text = text(thought, 54, serif, CONTENT_WIDTH, INK, 8, 1.14f);
            b.height = b.textTop + b.text.getHeight();
            blocks.add(b);
        }
        if ((mask & QUOTE) != 0) {
            Block b = new Block();
            b.kind = QUOTE;
            // Bound measurement work for huge excerpts, never the stored record or thought.
            String prefix = quote.substring(0, safeBoundary(quote, Math.min(6000, quote.length())));
            b.textTop = compact ? 58 : 64;
            b.textBottom = compact ? 22 : 28;
            b.textInset = QUOTE_INSET;
            b.text = quoteText(prefix);
            b.desired = b.textTop + b.text.getHeight() + b.textBottom;
            b.minimum = Math.min(b.desired,
                b.textTop + b.text.getLineBottom(0) + NOTICE + b.textBottom);
            b.weight = 3;
            blocks.add(b);
            optional.add(b);
        }
        if ((mask & CROP) != 0) {
            Block b = image(CROP, crop, moduleCount == 1 ? singleImageBudget : 700, 3);
            blocks.add(b);
            optional.add(b);
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
        float fixed = top + MARGIN + QR_FOOTER + gap * Math.max(0, blocks.size() - 1)
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
                || q.textTop + q.text.getHeight() + q.textBottom > q.height;
            if (q.partial) {
                int maxHeight =
                        Math.max(0, (int) q.height - q.textTop - q.textBottom - NOTICE), end = 0;
                for (int line = 0;
                    line < q.text.getLineCount() && q.text.getLineBottom(line) <= maxHeight; line++)
                    end = q.text.getLineEnd(line);
                end = excerptEnd(quote, end);
                q.text = quoteText(quote.substring(0, end));
            }
            float old = q.height;
            q.height = q.textTop + q.text.getHeight() + q.textBottom + (q.partial ? NOTICE : 0);
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
        // Keep the QR on the two-pixel grid so 540px sharing previews do not blur its modules.
        int qrTop = (int) Math.ceil((end + QR_GAP - 0.01f) / 2f) * 2;
        int height = qrTop + QR_SIZE + MARGIN;
        return new Document(blocks, qr, mask, top, gap, height, qrTop, compact,
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
    private static StaticLayout quoteText(String value) {
        return text(value, 44, Typeface.create("sans-serif", Typeface.NORMAL),
            CONTENT_WIDTH - 2 * QUOTE_INSET, SECONDARY_INK, 6, 1.12f);
    }
    private static StaticLayout text(String value, float size, Typeface face, int width,
        int color, float spacing, float multiplier) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(face);
        return StaticLayout.Builder.obtain(value, 0, value.length(), p, width)
            .setIncludePad(false)
            .setLineSpacing(spacing, multiplier)
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
