package com.codex.mnote;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** Full-width scrollable reader; tall cards never become a single GPU-sized texture. */
final class ShareCardReadingView extends View {
    private final ShareCardRenderer.Document document;
    public ShareCardReadingView(Context context) {
        super(context);
        document = null;
    }
    ShareCardReadingView(Context context, ShareCardRenderer.Document document) {
        super(context);
        this.document = document;
        setContentDescription(
            "完整分享卡片，可上下滑动。" + document.summary + "。" + document.fullThought);
    }
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(1, MeasureSpec.getSize(widthSpec));
        setMeasuredDimension(width,
            document == null
                ? 0
                : (int) Math.ceil((double) document.height * width / ShareCardRenderer.WIDTH));
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (document == null)
            return;
        canvas.save();
        float scale = (float) getWidth() / ShareCardRenderer.WIDTH;
        canvas.scale(scale, scale);
        document.draw(canvas);
        canvas.restore();
    }
}
