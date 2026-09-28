package kz.kairat.simplephotoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public class CropEditorView extends View {
    public interface SwipeListener { void onSwipe(int direction); }

    private static final int MODE_NONE = 0;
    private static final int MODE_IMAGE = 1;
    private static final int MODE_FRAME = 2;
    private static final int MODE_LEFT = 3;
    private static final int MODE_TOP = 4;
    private static final int MODE_RIGHT = 5;
    private static final int MODE_BOTTOM = 6;
    private static final int MODE_TOP_LEFT = 7;
    private static final int MODE_TOP_RIGHT = 8;
    private static final int MODE_BOTTOM_LEFT = 9;
    private static final int MODE_BOTTOM_RIGHT = 10;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final RectF crop = new RectF();
    private final ScaleGestureDetector scaleDetector;

    private Bitmap bitmap;
    private float scale = 1f;
    private float minScale = 1f;
    private float tx = 0f;
    private float ty = 0f;
    private float lastX, lastY, downX, downY;
    private int mode = MODE_NONE;
    private boolean multiTouch = false;
    private boolean editMode = false;
    private SwipeListener swipeListener;

    public CropEditorView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setStyle(Paint.Style.STROKE);

        cornerPaint.setColor(Color.WHITE);
        cornerPaint.setStrokeWidth(dp(4f));
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
        cornerPaint.setStyle(Paint.Style.STROKE);

        shadePaint.setColor(0x77000000);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                multiTouch = true;
                return editMode && bitmap != null;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (!editMode || bitmap == null) return false;
                float oldScale = scale;
                scale *= detector.getScaleFactor();
                scale = Math.max(minScale, Math.min(scale, minScale * 8f));
                float factor = scale / oldScale;
                float fx = detector.getFocusX();
                float fy = detector.getFocusY();
                tx = fx - (fx - tx) * factor;
                ty = fy - (fy - ty) * factor;
                constrainImage();
                invalidate();
                return true;
            }
        });
    }

    public void setSwipeListener(SwipeListener listener) { swipeListener = listener; }

    public void setEditMode(boolean enabled) {
        editMode = enabled;
        mode = MODE_NONE;
        multiTouch = false;
        if (enabled) resetGeometry();
        invalidate();
    }

    public boolean isEditMode() { return editMode; }

    public void setBitmap(Bitmap source) {
        if (bitmap != null && bitmap != source && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = source;
        editMode = false;
        resetGeometry();
        invalidate();
    }

    public void rotate90() {
        if (bitmap == null) return;
        Matrix matrix = new Matrix();
        matrix.postRotate(90f);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = rotated;
        resetGeometry();
        invalidate();
    }

    private void resetGeometry() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float margin = dp(18f);
        float availW = Math.max(dp(100f), getWidth() - margin * 2f);
        float availH = Math.max(dp(100f), getHeight() - margin * 2f);
        float photoAspect = (float) bitmap.getWidth() / (float) bitmap.getHeight();

        float cropW = availW;
        float cropH = cropW / photoAspect;
        if (cropH > availH) {
            cropH = availH;
            cropW = cropH * photoAspect;
        }

        float left = (getWidth() - cropW) / 2f;
        float top = (getHeight() - cropH) / 2f;
        crop.set(left, top, left + cropW, top + cropH);
        minScale = Math.max(crop.width() / bitmap.getWidth(), crop.height() / bitmap.getHeight());
        scale = minScale;
        tx = crop.centerX() - bitmap.getWidth() * scale / 2f;
        ty = crop.centerY() - bitmap.getHeight() * scale / 2f;
        constrainImage();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetGeometry();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (bitmap == null) return;

        RectF dst = new RectF(tx, ty, tx + bitmap.getWidth() * scale, ty + bitmap.getHeight() * scale);
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint);

        if (editMode) {
            canvas.drawRect(0, 0, getWidth(), crop.top, shadePaint);
            canvas.drawRect(0, crop.bottom, getWidth(), getHeight(), shadePaint);
            canvas.drawRect(0, crop.top, crop.left, crop.bottom, shadePaint);
            canvas.drawRect(crop.right, crop.top, getWidth(), crop.bottom, shadePaint);
            canvas.drawRect(crop, borderPaint);
            drawCorners(canvas);
        }
    }

    private void drawCorners(Canvas canvas) {
        float len = dp(24f);
        drawCorner(canvas, crop.left, crop.top, len, 1, 1);
        drawCorner(canvas, crop.right, crop.top, len, -1, 1);
        drawCorner(canvas, crop.left, crop.bottom, len, 1, -1);
        drawCorner(canvas, crop.right, crop.bottom, len, -1, -1);
    }

    private void drawCorner(Canvas canvas, float x, float y, float len, int sx, int sy) {
        canvas.drawLine(x, y, x + len * sx, y, cornerPaint);
        canvas.drawLine(x, y, x, y + len * sy, cornerPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return true;

        if (!editMode) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > dp(80f) && Math.abs(dx) > Math.abs(dy) * 1.3f && swipeListener != null) {
                        swipeListener.onSwipe(dx < 0 ? -1 : 1);
                    }
                    return true;
            }
            return true;
        }

        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                mode = detectMode(lastX, lastY);
                multiTouch = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress()) return true;
                float x = event.getX();
                float y = event.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                switch (mode) {
                    case MODE_IMAGE:
                        tx += dx; ty += dy; constrainImage(); break;
                    case MODE_FRAME:
                        crop.offset(dx, dy); constrainFramePosition(); updateMinScaleForCrop(); constrainImage(); break;
                    default:
                        resizeCrop(dx, dy); updateMinScaleForCrop(); constrainImage(); break;
                }
                lastX = x; lastY = y; invalidate(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE; multiTouch = false; return true;
        }
        return true;
    }

    private int detectMode(float x, float y) {
        float hit = dp(30f);
        boolean nearLeft = Math.abs(x - crop.left) <= hit;
        boolean nearRight = Math.abs(x - crop.right) <= hit;
        boolean nearTop = Math.abs(y - crop.top) <= hit;
        boolean nearBottom = Math.abs(y - crop.bottom) <= hit;
        boolean withinX = x >= crop.left - hit && x <= crop.right + hit;
        boolean withinY = y >= crop.top - hit && y <= crop.bottom + hit;
        if (nearLeft && nearTop) return MODE_TOP_LEFT;
        if (nearRight && nearTop) return MODE_TOP_RIGHT;
        if (nearLeft && nearBottom) return MODE_BOTTOM_LEFT;
        if (nearRight && nearBottom) return MODE_BOTTOM_RIGHT;
        if (nearLeft && withinY) return MODE_LEFT;
        if (nearRight && withinY) return MODE_RIGHT;
        if (nearTop && withinX) return MODE_TOP;
        if (nearBottom && withinX) return MODE_BOTTOM;
        if (crop.contains(x, y)) return MODE_IMAGE;
        return MODE_FRAME;
    }

    private void resizeCrop(float dx, float dy) {
        float minW = dp(90f), minH = dp(90f), margin = dp(6f);
        if (mode == MODE_LEFT || mode == MODE_TOP_LEFT || mode == MODE_BOTTOM_LEFT)
            crop.left = clamp(crop.left + dx, margin, crop.right - minW);
        if (mode == MODE_RIGHT || mode == MODE_TOP_RIGHT || mode == MODE_BOTTOM_RIGHT)
            crop.right = clamp(crop.right + dx, crop.left + minW, getWidth() - margin);
        if (mode == MODE_TOP || mode == MODE_TOP_LEFT || mode == MODE_TOP_RIGHT)
            crop.top = clamp(crop.top + dy, margin, crop.bottom - minH);
        if (mode == MODE_BOTTOM || mode == MODE_BOTTOM_LEFT || mode == MODE_BOTTOM_RIGHT)
            crop.bottom = clamp(crop.bottom + dy, crop.top + minH, getHeight() - margin);
    }

    private void constrainFramePosition() {
        float margin = dp(6f);
        if (crop.left < margin) crop.offset(margin - crop.left, 0);
        if (crop.right > getWidth() - margin) crop.offset((getWidth() - margin) - crop.right, 0);
        if (crop.top < margin) crop.offset(0, margin - crop.top);
        if (crop.bottom > getHeight() - margin) crop.offset(0, (getHeight() - margin) - crop.bottom);
    }

    private void updateMinScaleForCrop() {
        if (bitmap == null || crop.width() <= 0 || crop.height() <= 0) return;
        minScale = Math.max(crop.width() / bitmap.getWidth(), crop.height() / bitmap.getHeight());
        if (scale < minScale) {
            float oldScale = Math.max(scale, 0.0001f);
            float factor = minScale / oldScale;
            float cx = crop.centerX(), cy = crop.centerY();
            tx = cx - (cx - tx) * factor;
            ty = cy - (cy - ty) * factor;
            scale = minScale;
        }
    }

    private void constrainImage() {
        if (bitmap == null) return;
        float imageW = bitmap.getWidth() * scale;
        float imageH = bitmap.getHeight() * scale;
        if (imageW < crop.width() || imageH < crop.height()) {
            updateMinScaleForCrop();
            imageW = bitmap.getWidth() * scale;
            imageH = bitmap.getHeight() * scale;
        }
        if (tx > crop.left) tx = crop.left;
        if (ty > crop.top) ty = crop.top;
        if (tx + imageW < crop.right) tx = crop.right - imageW;
        if (ty + imageH < crop.bottom) ty = crop.bottom - imageH;
    }

    public Bitmap createCroppedBitmap() {
        if (bitmap == null) return null;
        if (!editMode) return Bitmap.createBitmap(bitmap);
        int left = Math.max(0, Math.round((crop.left - tx) / scale));
        int top = Math.max(0, Math.round((crop.top - ty) / scale));
        int right = Math.min(bitmap.getWidth(), Math.round((crop.right - tx) / scale));
        int bottom = Math.min(bitmap.getHeight(), Math.round((crop.bottom - ty) / scale));
        if (right <= left || bottom <= top) return null;
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
    }

    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
