package kz.kairat.simplephotoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public class CropEditorView extends View {
    public interface SwipeListener { void onSwipe(int direction); }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_LEFT = 1;
    private static final int HANDLE_TOP = 2;
    private static final int HANDLE_RIGHT = 4;
    private static final int HANDLE_BOTTOM = 8;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final RectF crop = new RectF();
    private final ScaleGestureDetector scaleDetector;

    private Bitmap bitmap;
    private float scale = 1f;
    private float minScale = 1f;
    private float tx = 0f;
    private float ty = 0f;
    private float lastX, lastY, downX, downY;
    private int activeHandle = HANDLE_NONE;
    private boolean movedImage = false;
    private SwipeListener swipeListener;

    public CropEditorView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setStyle(Paint.Style.STROKE);
        shadePaint.setColor(0x66000000);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (bitmap == null) return false;
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

    public void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    public void setBitmap(Bitmap source) {
        if (bitmap != null && bitmap != source && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = source;
        resetGeometry();
        invalidate();
    }

    public void rotate90() {
        if (bitmap == null) return;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(90f);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        bitmap = rotated;
        resetGeometry();
        invalidate();
    }

    private void resetGeometry() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float margin = dp(12);
        crop.set(margin, margin, getWidth() - margin, getHeight() - margin);
        float sx = crop.width() / bitmap.getWidth();
        float sy = crop.height() / bitmap.getHeight();
        minScale = Math.max(sx, sy);
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
        if (bitmap == null) return;
        RectF dst = new RectF(tx, ty, tx + bitmap.getWidth() * scale, ty + bitmap.getHeight() * scale);
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint);

        canvas.drawRect(0, 0, getWidth(), crop.top, shadePaint);
        canvas.drawRect(0, crop.bottom, getWidth(), getHeight(), shadePaint);
        canvas.drawRect(0, crop.top, crop.left, crop.bottom, shadePaint);
        canvas.drawRect(crop.right, crop.top, getWidth(), crop.bottom, shadePaint);
        canvas.drawRect(crop, borderPaint);

        float c = dp(18);
        drawCorner(canvas, crop.left, crop.top, c, 1, 1);
        drawCorner(canvas, crop.right, crop.top, c, -1, 1);
        drawCorner(canvas, crop.left, crop.bottom, c, 1, -1);
        drawCorner(canvas, crop.right, crop.bottom, c, -1, -1);
    }

    private void drawCorner(Canvas canvas, float x, float y, float len, int sx, int sy) {
        canvas.drawLine(x, y, x + len * sx, y, borderPaint);
        canvas.drawLine(x, y, x, y + len * sy, borderPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return true;
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                activeHandle = detectHandle(lastX, lastY);
                movedImage = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress()) return true;
                float x = event.getX();
                float y = event.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                if (activeHandle != HANDLE_NONE) {
                    resizeCrop(dx, dy);
                } else {
                    tx += dx;
                    ty += dy;
                    movedImage = true;
                    constrainImage();
                }
                lastX = x;
                lastY = y;
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (activeHandle == HANDLE_NONE && !scaleDetector.isInProgress()) {
                    float totalX = event.getX() - downX;
                    float totalY = event.getY() - downY;
                    if (Math.abs(totalX) > dp(120) && Math.abs(totalX) > Math.abs(totalY) * 1.4f) {
                        if (swipeListener != null) swipeListener.onSwipe(totalX < 0 ? -1 : 1);
                    }
                }
                activeHandle = HANDLE_NONE;
                return true;

            case MotionEvent.ACTION_CANCEL:
                activeHandle = HANDLE_NONE;
                return true;
        }
        return true;
    }

    private int detectHandle(float x, float y) {
        float hit = dp(34);
        int h = HANDLE_NONE;
        if (Math.abs(x - crop.left) <= hit && y >= crop.top - hit && y <= crop.bottom + hit) h |= HANDLE_LEFT;
        if (Math.abs(x - crop.right) <= hit && y >= crop.top - hit && y <= crop.bottom + hit) h |= HANDLE_RIGHT;
        if (Math.abs(y - crop.top) <= hit && x >= crop.left - hit && x <= crop.right + hit) h |= HANDLE_TOP;
        if (Math.abs(y - crop.bottom) <= hit && x >= crop.left - hit && x <= crop.right + hit) h |= HANDLE_BOTTOM;
        return h;
    }

    private void resizeCrop(float dx, float dy) {
        float min = dp(80);
        float margin = dp(4);
        if ((activeHandle & HANDLE_LEFT) != 0) crop.left = clamp(crop.left + dx, margin, crop.right - min);
        if ((activeHandle & HANDLE_RIGHT) != 0) crop.right = clamp(crop.right + dx, crop.left + min, getWidth() - margin);
        if ((activeHandle & HANDLE_TOP) != 0) crop.top = clamp(crop.top + dy, margin, crop.bottom - min);
        if ((activeHandle & HANDLE_BOTTOM) != 0) crop.bottom = clamp(crop.bottom + dy, crop.top + min, getHeight() - margin);
        updateMinScaleForCrop();
        constrainImage();
    }

    private void updateMinScaleForCrop() {
        if (bitmap == null) return;
        minScale = Math.max(crop.width() / bitmap.getWidth(), crop.height() / bitmap.getHeight());
        if (scale < minScale) {
            float old = scale;
            scale = minScale;
            if (old > 0f) {
                float factor = scale / old;
                tx = crop.centerX() - (crop.centerX() - tx) * factor;
                ty = crop.centerY() - (crop.centerY() - ty) * factor;
            }
        }
    }

    private void constrainImage() {
        if (bitmap == null) return;
        float imageW = bitmap.getWidth() * scale;
        float imageH = bitmap.getHeight() * scale;
        if (tx > crop.left) tx = crop.left;
        if (ty > crop.top) ty = crop.top;
        if (tx + imageW < crop.right) tx = crop.right - imageW;
        if (ty + imageH < crop.bottom) ty = crop.bottom - imageH;
    }

    public Bitmap createCroppedBitmap() {
        if (bitmap == null) return null;
        int left = Math.max(0, Math.round((crop.left - tx) / scale));
        int top = Math.max(0, Math.round((crop.top - ty) / scale));
        int right = Math.min(bitmap.getWidth(), Math.round((crop.right - tx) / scale));
        int bottom = Math.min(bitmap.getHeight(), Math.round((crop.bottom - ty) / scale));
        if (right <= left || bottom <= top) return null;
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
