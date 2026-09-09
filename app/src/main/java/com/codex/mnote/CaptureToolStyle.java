package com.codex.mnote;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small translucent controls, not a screenshot-obscuring toolbar surface. */
final class CaptureToolStyle {
    static void tool(TextView view, int symbol) {
        int accent = view.getContext().getColor(R.color.coral);
        int color = view.isSelected() ? 0xFFFFFFFF : 0xFFF2F4F8;
        view.setTextSize(10);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(view, 48));
        view.setMinHeight(dp(view, 56));
        view.setPadding(0, dp(view, 6), 0, dp(view, 5));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(view, 48), dp(view, 56));
        params.setMargins(dp(view, 2), 0, dp(view, 2), 0);
        view.setLayoutParams(params);
        view.setCompoundDrawablesWithIntrinsicBounds(null, new Symbol(view, symbol, color), null, null);
        view.setCompoundDrawablePadding(dp(view, 3));
        view.setTextColor(color);
        view.setShadowLayer(0, 0, 0, 0);
        surface(view, view.isSelected() ? (0xE6000000 | (accent & 0xFFFFFF)) : 0x99202430, 17);
    }

    static void action(TextView view, int symbol, boolean primary, boolean composing) {
        int accent = view.getContext().getColor(R.color.coral);
        view.setShadowLayer(0, 0, 0, 0);
        view.setBackgroundTintList(null);
        view.setMinHeight(dp(view, 48));
        view.setMinWidth(dp(view, primary ? 80 : 48));
        view.setGravity(Gravity.CENTER);
        view.setTextSize(14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(view, primary ? 14 : 10), 0, dp(view, primary ? 14 : 10), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                primary ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(view, 48), dp(view, 48));
        params.setMarginStart(dp(view, 4));
        view.setLayoutParams(params);
        int color = composing && !primary ? accent : Color.WHITE;
        view.setTextColor(color);
        if (!primary) {
            view.setText("");
            view.setCompoundDrawablesWithIntrinsicBounds(null, new Symbol(view, symbol, color), null, null);
            view.setPadding(dp(view, 12), dp(view, 12), dp(view, 12), dp(view, 12));
        }
        surface(view, primary ? (composing ? accent : (0xEB000000 | (accent & 0xFFFFFF)))
                : composing ? view.getContext().getColor(R.color.coral_soft) : 0x99202430, 24);
    }

    private static void surface(TextView view, int color, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(view, radius));
        fill.setStroke(dp(view, 1), 0x30FFFFFF);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, null));
        view.setAlpha(view.isEnabled() ? 1f : .4f);
    }

    private static int dp(TextView view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }

    /** All symbols share a 24-unit canvas and a rounded 1.7-unit stroke. */
    static final class Symbol extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int size;
        final int symbol;
        Symbol(TextView view, int symbol, int color) {
            this.symbol = symbol; size = dp(view, 22);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            canvas.save();
            Rect b = getBounds(); canvas.translate(b.left, b.top); canvas.scale(b.width() / 24f, b.height() / 24f);
            switch (symbol) {
                case 0: // selection corners
                    path(canvas, 4,9, 4,4, 9,4); path(canvas, 15,4, 20,4, 20,9);
                    path(canvas, 4,15, 4,20, 9,20); path(canvas, 15,20, 20,20, 20,15); break;
                case 1: // pen
                    path(canvas, 4,20, 5,15, 16,4, 20,8, 9,19, 4,20);
                    line(canvas, 14,6, 18,10); break;
                case 2: // marker
                    path(canvas, 6,15, 15,4, 20,9, 11,18, 6,15);
                    path(canvas, 6,15, 4,19, 8,20, 11,18); line(canvas, 14,21, 21,21); break;
                case 3: // undo
                    path(canvas, 8,5, 4,9, 8,13); path(canvas, 4,9, 14,9);
                    canvas.drawArc(9,9,21,21,-90,210,false,paint); break;
                case 4: // entire image
                    canvas.drawRoundRect(3,4,21,20,3,3,paint);
                    path(canvas, 5,17, 10,12, 14,16, 17,13, 20,16);
                    canvas.drawCircle(16,9,1.2f,paint); break;
                case 5: // move dock
                    line(canvas,12,3,12,21); path(canvas,8,7,12,3,16,7); path(canvas,8,17,12,21,16,17); break;
                case 6: line(canvas,6,6,18,18); line(canvas,18,6,6,18); break;
                case 7: line(canvas,5,12,19,12); break;
                case 8: path(canvas,15,5,8,12,15,19); break;
                default: break;
            }
            canvas.restore();
        }
        private void line(Canvas c, float x, float y, float x2, float y2) { c.drawLine(x,y,x2,y2,paint); }
        private void path(Canvas c, float... points) {
            Path path = new Path(); path.moveTo(points[0],points[1]);
            for (int i=2; i<points.length; i+=2) path.lineTo(points[i],points[i+1]);
            c.drawPath(path,paint);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
