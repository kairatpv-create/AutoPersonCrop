from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_13.py"), run_name="__main__")

CROP = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/CropEditorView.java"
MAIN = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/MainActivity.java"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing anchor: {label}")
    return text.replace(old, new, 1)


def function_bounds(text: str, signature: str):
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"Function not found: {signature}")
    brace = text.find("{", start)
    depth = 0
    in_string = False
    escaped = False
    i = brace
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return start, i + 1
        i += 1
    raise RuntimeError(f"Function end not found: {signature}")


# -----------------------------------------------------------------------------
# Crop performance + stable pinch.
# In edit mode we bypass ScaleGestureDetector completely and handle the two-finger
# transform ourselves. One transform per MotionEvent = no double correction/jump.
# -----------------------------------------------------------------------------
s = CROP.read_text(encoding="utf-8")

s = replace_once(
    s,
    """    private float pinchFocusX, pinchFocusY, pinchBitmapX, pinchBitmapY;\n    private int mode = MODE_NONE;""",
    """    private float pinchFocusX, pinchFocusY, pinchBitmapX, pinchBitmapY;\n    private float editPinchStartDistance = 0f;\n    private float editPinchStartScale = 1f;\n    private float editPinchBitmapX = 0f, editPinchBitmapY = 0f;\n    private boolean editPinchActive = false;\n    private int mode = MODE_NONE;""",
    "edit pinch fields",
)

os, oe = function_bounds(s, "    @Override public boolean onTouchEvent(MotionEvent event)")
new_on_touch = r'''    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return true;
        // Edit mode has its own direct pinch path. Feeding the same gesture to
        // ScaleGestureDetector as well caused a second transform and visible jumps.
        if (!editMode) {
            scaleDetector.onTouchEvent(event);
            return handleViewTouch(event);
        }
        return handleEditTouch(event);
    }'''
s = s[:os] + new_on_touch + s[oe:]

hs, he = function_bounds(s, "    private boolean handleEditTouch(MotionEvent event)")
new_edit_touch = r'''    private boolean handleEditTouch(MotionEvent event) {
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            multiTouch = true;
            editPinchActive = true;
            mode = MODE_IMAGE;
            float x0 = event.getX(0), y0 = event.getY(0);
            float x1 = event.getX(1), y1 = event.getY(1);
            float dx = x1 - x0, dy = y1 - y0;
            editPinchStartDistance = Math.max(1f, (float) Math.hypot(dx, dy));
            editPinchStartScale = scale;
            pinchFocusX = (x0 + x1) * 0.5f;
            pinchFocusY = (y0 + y1) * 0.5f;
            editPinchBitmapX = (pinchFocusX - tx) / Math.max(scale, 0.0001f);
            editPinchBitmapY = (pinchFocusY - ty) / Math.max(scale, 0.0001f);
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE && editPinchActive && event.getPointerCount() >= 2) {
            float x0 = event.getX(0), y0 = event.getY(0);
            float x1 = event.getX(1), y1 = event.getY(1);
            float dx = x1 - x0, dy = y1 - y0;
            float currentDistance = Math.max(1f, (float) Math.hypot(dx, dy));
            float newScale = editPinchStartScale * (currentDistance / editPinchStartDistance);
            newScale = Math.max(minScale, Math.min(newScale, minScale * 8f));
            float focusX = (x0 + x1) * 0.5f;
            float focusY = (y0 + y1) * 0.5f;
            scale = newScale;
            tx = focusX - editPinchBitmapX * scale;
            ty = focusY - editPinchBitmapY * scale;
            constrainImage();
            postInvalidateOnAnimation();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP && editPinchActive) {
            editPinchActive = false;
            multiTouch = true;
            mode = MODE_IMAGE;
            int lifted = event.getActionIndex();
            int remaining = lifted == 0 ? 1 : 0;
            if (remaining < event.getPointerCount()) {
                lastX = event.getX(remaining);
                lastY = event.getY(remaining);
            }
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                mode = detectMode(lastX, lastY);
                multiTouch = false;
                editPinchActive = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) return true;
                float x = event.getX();
                float y = event.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                switch (mode) {
                    case MODE_IMAGE:
                        tx += dx;
                        ty += dy;
                        constrainImage();
                        break;
                    case MODE_FRAME:
                        crop.offset(dx, dy);
                        constrainFramePosition();
                        updateMinScaleForCrop();
                        constrainImage();
                        break;
                    default:
                        resizeCrop(dx, dy);
                        updateMinScaleForCrop();
                        constrainImage();
                        break;
                }
                lastX = x;
                lastY = y;
                postInvalidateOnAnimation();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                editPinchActive = false;
                mode = MODE_NONE;
                multiTouch = false;
                return true;
        }
        return true;
    }'''
s = s[:hs] + new_edit_touch + s[he:]

# Frame-timed invalidation instead of immediate invalidate during geometry changes.
s = s.replace("                invalidate();\n                return true;", "                postInvalidateOnAnimation();\n                return true;")
CROP.write_text(s, encoding="utf-8")


# -----------------------------------------------------------------------------
# Preview/UI performance.
# 0.1.13 still decoded previews at ~2x screen dimensions. For browsing that is
# unnecessary memory bandwidth. Decode near display size, prefetch one neighbor
# in swipe direction/current neighborhood, and keep transitions GPU-light.
# -----------------------------------------------------------------------------
m = MAIN.read_text(encoding="utf-8")

m = replace_once(
    m,
    """        int w = Math.max(1080, getResources().getDisplayMetrics().widthPixels * 2);\n        int h = Math.max(1920, getResources().getDisplayMetrics().heightPixels * 2);""",
    """        int w = Math.max(1080, getResources().getDisplayMetrics().widthPixels);\n        int h = Math.max(1600, getResources().getDisplayMetrics().heightPixels);""",
    "screen-sized preview decode",
)

# Smaller preview cache reduces GC pressure while still keeping several screen images.
m = replace_once(
    m,
    "private final LruCache<Integer, Bitmap> previewCache = new LruCache<Integer, Bitmap>(48 * 1024)",
    "private final LruCache<Integer, Bitmap> previewCache = new LruCache<Integer, Bitmap>(32 * 1024)",
    "preview cache size",
)

# Remove alpha work; only a tiny translation is animated by the render thread.
shs, she = function_bounds(m, "    private void showLoadedBitmap(")
show = m[shs:she]
show = show.replace("width * 0.06f", "width * 0.035f")
show = show.replace("editor.setAlpha(0.96f);", "editor.setAlpha(1f);")
show = show.replace("                    .alpha(1f)\n", "")
show = show.replace(".setDuration(55L)", ".setDuration(45L)")
m = m[:shs] + show + m[she:]

# Avoid decoding two neighbors simultaneously with the current frame on every move.
ps, pe = function_bounds(m, "    private void preloadNeighbors(")
new_preload = r'''    private void preloadNeighbors(int center) {
        // Keep only immediate neighbors warm. Jobs are cheap screen previews and
        // run outside UI; duplicates are skipped by the cache.
        for (int p : new int[]{center + 1, center - 1}) {
            if (p < 0 || p >= editorImages.size() || previewCache.get(p) != null) continue;
            final int position = p;
            photoIoPool.execute(() -> decodePreviewPhoto(position));
        }
    }'''
m = m[:ps] + new_preload + m[pe:]

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 14', 'versionCode = 15', 'versionCode')
g = replace_once(g, 'versionName = "0.1.13"', 'versionName = "0.1.14"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.14 fast UI and stable pinch patch applied")
