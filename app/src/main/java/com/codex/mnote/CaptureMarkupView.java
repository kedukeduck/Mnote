package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Screenshot crop and lightweight vector markup surface. */
public final class CaptureMarkupView extends View {
    enum Tool {
        SELECT,
        PEN,
        HIGHLIGHTER
    }

    private static final int PEN_COLOR = 0xFFE34D65;
    private static final int HIGHLIGHT_COLOR = 0x78FFDC3A;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint selectionBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionShadePaint = new Paint();
    private final Matrix bitmapToView = new Matrix();
    private final Matrix viewToBitmap = new Matrix();
    private final RectF imageRect = new RectF();
    private final RectF cropRect = new RectF();
    private final RectF previousCropRect = new RectF();
    private final List<Stroke> strokes = new ArrayList<>();

    private Bitmap source;
    private Tool tool = Tool.SELECT;
    private Stroke activeStroke;
    private PointF selectionStart;
    private float selectionStartViewX;
    private float selectionStartViewY;
    private float density;
    private Runnable changeListener;

    public CaptureMarkupView(Context context) {
        this(context, null);
    }

    public CaptureMarkupView(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.rgb(28, 27, 30));
        selectionBorderPaint.setStyle(Paint.Style.STROKE);
        selectionBorderPaint.setStrokeWidth(Math.max(2f, density * 2f));
        selectionBorderPaint.setColor(Color.WHITE);
        selectionShadePaint.setStyle(Paint.Style.FILL);
        selectionShadePaint.setColor(0x88000000);
        setFocusable(true);
        setContentDescription(getResources().getString(R.string.capture_canvas_description));
    }

    void setSourceBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            throw new IllegalArgumentException("Capture bitmap is empty");
        }
        source = bitmap;
        strokes.clear();
        cropRect.set(0f, 0f, bitmap.getWidth(), bitmap.getHeight());
        calculateMatrices();
        invalidate();
        notifyChanged();
    }

    void setTool(Tool newTool) {
        tool = newTool == null ? Tool.SELECT : newTool;
        activeStroke = null;
        selectionStart = null;
        invalidate();
    }

    Tool getTool() {
        return tool;
    }

    void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    boolean canUndo() {
        return !strokes.isEmpty();
    }

    void undo() {
        if (strokes.isEmpty()) {
            return;
        }
        strokes.remove(strokes.size() - 1);
        activeStroke = null;
        invalidate();
        notifyChanged();
    }

    void selectWholeImage() {
        if (source == null) {
            return;
        }
        cropRect.set(0f, 0f, source.getWidth(), source.getHeight());
        invalidate();
        notifyChanged();
    }

    Bitmap renderOriginalSelection() {
        if (source == null) {
            return null;
        }
        IntCrop crop = integerCrop();
        Bitmap output = Bitmap.createBitmap(
                crop.width(),
                crop.height(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(source, -crop.left, -crop.top, bitmapPaint);
        return output;
    }

    Bitmap renderAnnotatedSelection() {
        Bitmap output = renderOriginalSelection();
        if (output == null) {
            return null;
        }
        IntCrop crop = integerCrop();
        Canvas canvas = new Canvas(output);
        canvas.save();
        canvas.translate(-crop.left, -crop.top);
        for (Stroke stroke : strokes) {
            stroke.draw(canvas);
        }
        canvas.restore();
        return output;
    }

    JSONObject annotationLayer() throws JSONException {
        JSONObject selection = new JSONObject()
                .put("left", cropRect.left)
                .put("top", cropRect.top)
                .put("right", cropRect.right)
                .put("bottom", cropRect.bottom);
        JSONArray encodedStrokes = new JSONArray();
        for (Stroke stroke : strokes) {
            encodedStrokes.put(stroke.toJson());
        }
        return new JSONObject()
                .put("coordinateSpace", "source_bitmap_pixels")
                .put("selection", selection)
                .put("strokes", encodedStrokes);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        calculateMatrices();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (source == null || imageRect.isEmpty()) {
            return;
        }
        canvas.drawBitmap(source, null, imageRect, bitmapPaint);

        canvas.save();
        canvas.clipRect(imageRect);
        canvas.concat(bitmapToView);
        for (Stroke stroke : strokes) {
            stroke.draw(canvas);
        }
        canvas.restore();

        RectF visibleCrop = new RectF(cropRect);
        bitmapToView.mapRect(visibleCrop);
        Path outside = new Path();
        outside.setFillType(Path.FillType.EVEN_ODD);
        outside.addRect(imageRect, Path.Direction.CW);
        outside.addRect(visibleCrop, Path.Direction.CW);
        canvas.drawPath(outside, selectionShadePaint);
        canvas.drawRect(visibleCrop, selectionBorderPaint);
        drawCropHandles(canvas, visibleCrop);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (source == null || imageRect.isEmpty()) {
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!imageRect.contains(event.getX(), event.getY())) {
                return false;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            if (tool == Tool.SELECT) {
                previousCropRect.set(cropRect);
                selectionStart = bitmapPoint(event.getX(), event.getY());
                selectionStartViewX = event.getX();
                selectionStartViewY = event.getY();
                cropRect.set(
                        selectionStart.x,
                        selectionStart.y,
                        selectionStart.x,
                        selectionStart.y
                );
            } else {
                activeStroke = new Stroke(tool, strokeWidthInBitmapPixels(tool));
                activeStroke.add(bitmapPoint(event.getX(), event.getY()));
                strokes.add(activeStroke);
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (tool == Tool.SELECT && selectionStart != null) {
                PointF point = bitmapPoint(event.getX(), event.getY());
                cropRect.set(
                        Math.min(selectionStart.x, point.x),
                        Math.min(selectionStart.y, point.y),
                        Math.max(selectionStart.x, point.x),
                        Math.max(selectionStart.y, point.y)
                );
            } else if (activeStroke != null) {
                PointF point = bitmapPoint(event.getX(), event.getY());
                activeStroke.addIfMoved(point, Math.max(0.8f, 1.5f / imageScale()));
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (tool == Tool.SELECT && selectionStart != null) {
                float width = Math.abs(event.getX() - selectionStartViewX);
                float height = Math.abs(event.getY() - selectionStartViewY);
                if (width < 24f * density || height < 24f * density) {
                    cropRect.set(previousCropRect);
                } else {
                    PointF point = bitmapPoint(event.getX(), event.getY());
                    cropRect.set(
                            Math.min(selectionStart.x, point.x),
                            Math.min(selectionStart.y, point.y),
                            Math.max(selectionStart.x, point.x),
                            Math.max(selectionStart.y, point.y)
                    );
                }
                selectionStart = null;
            } else if (activeStroke != null) {
                activeStroke.addIfMoved(
                        bitmapPoint(event.getX(), event.getY()),
                        0.1f
                );
                activeStroke = null;
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
            invalidate();
            notifyChanged();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            if (selectionStart != null) {
                cropRect.set(previousCropRect);
                selectionStart = null;
            }
            if (activeStroke != null) {
                strokes.remove(activeStroke);
                activeStroke = null;
            }
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void calculateMatrices() {
        if (source == null || getWidth() <= 0 || getHeight() <= 0) {
            imageRect.setEmpty();
            return;
        }
        float sourceWidth = source.getWidth();
        float sourceHeight = source.getHeight();
        float scale = Math.min(getWidth() / sourceWidth, getHeight() / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        imageRect.set(left, top, left + width, top + height);
        bitmapToView.reset();
        bitmapToView.setScale(scale, scale);
        bitmapToView.postTranslate(left, top);
        bitmapToView.invert(viewToBitmap);
    }

    private PointF bitmapPoint(float viewX, float viewY) {
        float clampedX = Math.max(imageRect.left, Math.min(imageRect.right, viewX));
        float clampedY = Math.max(imageRect.top, Math.min(imageRect.bottom, viewY));
        float[] point = {clampedX, clampedY};
        viewToBitmap.mapPoints(point);
        return new PointF(
                Math.max(0f, Math.min(source.getWidth(), point[0])),
                Math.max(0f, Math.min(source.getHeight(), point[1]))
        );
    }

    private float imageScale() {
        if (source == null || source.getWidth() == 0) {
            return 1f;
        }
        return Math.max(0.0001f, imageRect.width() / source.getWidth());
    }

    private float strokeWidthInBitmapPixels(Tool strokeTool) {
        float viewWidthDp = strokeTool == Tool.HIGHLIGHTER ? 18f : 4f;
        return viewWidthDp * density / imageScale();
    }

    private void drawCropHandles(Canvas canvas, RectF visibleCrop) {
        float radius = 4f * density;
        Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        handle.setStyle(Paint.Style.FILL);
        handle.setColor(Color.WHITE);
        canvas.drawCircle(visibleCrop.left, visibleCrop.top, radius, handle);
        canvas.drawCircle(visibleCrop.right, visibleCrop.top, radius, handle);
        canvas.drawCircle(visibleCrop.left, visibleCrop.bottom, radius, handle);
        canvas.drawCircle(visibleCrop.right, visibleCrop.bottom, radius, handle);
    }

    private IntCrop integerCrop() {
        int left = Math.max(0, Math.min(source.getWidth() - 1, (int) Math.floor(cropRect.left)));
        int top = Math.max(0, Math.min(source.getHeight() - 1, (int) Math.floor(cropRect.top)));
        int right = Math.max(left + 1, Math.min(source.getWidth(), (int) Math.ceil(cropRect.right)));
        int bottom = Math.max(top + 1, Math.min(source.getHeight(), (int) Math.ceil(cropRect.bottom)));
        return new IntCrop(left, top, right, bottom);
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private static final class Stroke {
        final Tool tool;
        final float width;
        final List<PointF> points = new ArrayList<>();

        Stroke(Tool tool, float width) {
            this.tool = tool;
            this.width = width;
        }

        void add(PointF point) {
            points.add(point);
        }

        void addIfMoved(PointF point, float minimumDistance) {
            if (points.isEmpty()) {
                points.add(point);
                return;
            }
            PointF previous = points.get(points.size() - 1);
            float dx = point.x - previous.x;
            float dy = point.y - previous.y;
            if (dx * dx + dy * dy >= minimumDistance * minimumDistance) {
                points.add(point);
            }
        }

        void draw(Canvas canvas) {
            if (points.isEmpty()) {
                return;
            }
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(width);
            paint.setColor(tool == Tool.HIGHLIGHTER ? HIGHLIGHT_COLOR : PEN_COLOR);
            if (points.size() == 1) {
                PointF point = points.get(0);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(point.x, point.y, width / 2f, paint);
                return;
            }
            Path path = new Path();
            PointF first = points.get(0);
            path.moveTo(first.x, first.y);
            for (int index = 1; index < points.size(); index++) {
                PointF point = points.get(index);
                path.lineTo(point.x, point.y);
            }
            canvas.drawPath(path, paint);
        }

        JSONObject toJson() throws JSONException {
            JSONArray encodedPoints = new JSONArray();
            for (PointF point : points) {
                encodedPoints.put(new JSONArray().put(point.x).put(point.y));
            }
            return new JSONObject()
                    .put("tool", tool == Tool.HIGHLIGHTER ? "highlighter" : "pen")
                    .put("color", tool == Tool.HIGHLIGHTER ? HIGHLIGHT_COLOR : PEN_COLOR)
                    .put("width", width)
                    .put("points", encodedPoints);
        }
    }

    private static final class IntCrop {
        final int left;
        final int top;
        final int right;
        final int bottom;

        IntCrop(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }
    }
}
