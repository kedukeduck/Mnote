package com.codex.mnote;

import android.content.Context;
import android.graphics.*;
import android.widget.ImageView;
import org.json.JSONObject;

/** A review-only selection outline; the full screenshot asset remains unmodified. */
final class CaptureContextPreview extends ImageView {
    private final RectF selection = new RectF();
    private final RectF display = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    CaptureContextPreview(Context context, Bitmap bitmap, JSONObject metadata) {
        super(context); setAdjustViewBounds(true); setScaleType(ScaleType.FIT_CENTER);
        paint.setColor(context.getColor(R.color.coral)); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);
        setContent(bitmap, metadata);
        setContentDescription(context.getString(R.string.capture_context_image_title));
    }
    void setContent(Bitmap bitmap, JSONObject metadata) {
        setImageBitmap(bitmap); selection.setEmpty();
        if(metadata != null && bitmap != null) {
            JSONObject rect=metadata.optJSONObject("selection");
            float sx=(float)bitmap.getWidth()/Math.max(1,metadata.optInt("width"));
            float sy=(float)bitmap.getHeight()/Math.max(1,metadata.optInt("height"));
            if(rect!=null) selection.set((float)rect.optDouble("left")*sx,(float)rect.optDouble("top")*sy,
                    (float)rect.optDouble("right")*sx,(float)rect.optDouble("bottom")*sy);
        }
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        display.set(selection); getImageMatrix().mapRect(display);
        display.offset(getPaddingLeft(),getPaddingTop());
        if(!display.isEmpty()) canvas.drawRect(display,paint);
    }
}
