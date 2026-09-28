from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_11.py"), run_name="__main__")

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
# 1) Stable crop pinch: anchor the image under the initial two-finger focus.
# The picture no longer drifts/jumps when one finger is lifted after zooming.
# -----------------------------------------------------------------------------
s = CROP.read_text(encoding="utf-8")
s = replace_once(
    s,
    """    private float lastX, lastY, downX, downY;\n    private int mode = MODE_NONE;""",
    """    private float lastX, lastY, downX, downY;\n    private float pinchFocusX, pinchFocusY, pinchBitmapX, pinchBitmapY;\n    private int mode = MODE_NONE;""",
    "pinch anchor fields",
)

old_listener = """            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {\n                multiTouch = true;\n                if (editMode) mode = MODE_IMAGE;\n                return bitmap != null;\n            }\n\n            @Override public boolean onScale(ScaleGestureDetector detector) {\n                if (bitmap == null) return false;\n                float oldScale = scale;\n                scale *= detector.getScaleFactor();\n                scale = Math.max(minScale, Math.min(scale, minScale * 8f));\n                float factor = scale / oldScale;\n                float fx = detector.getFocusX();\n                float fy = detector.getFocusY();\n                if (editMode) {\n                    fx = clamp(fx, crop.left, crop.right);\n                    fy = clamp(fy, crop.top, crop.bottom);\n                }\n                tx = fx - (fx - tx) * factor;\n                ty = fy - (fy - ty) * factor;\n                if (editMode) constrainImage(); else constrainView();\n                invalidate();\n                return true;\n            }"""
new_listener = """            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {\n                multiTouch = true;\n                if (bitmap == null) return false;\n                if (editMode) mode = MODE_IMAGE;\n                pinchFocusX = detector.getFocusX();\n                pinchFocusY = detector.getFocusY();\n                if (editMode) {\n                    pinchFocusX = clamp(pinchFocusX, crop.left, crop.right);\n                    pinchFocusY = clamp(pinchFocusY, crop.top, crop.bottom);\n                }\n                pinchBitmapX = (pinchFocusX - tx) / Math.max(scale, 0.0001f);\n                pinchBitmapY = (pinchFocusY - ty) / Math.max(scale, 0.0001f);\n                return true;\n            }\n\n            @Override public boolean onScale(ScaleGestureDetector detector) {\n                if (bitmap == null) return false;\n                scale *= detector.getScaleFactor();\n                scale = Math.max(minScale, Math.min(scale, minScale * 8f));\n                tx = pinchFocusX - pinchBitmapX * scale;\n                ty = pinchFocusY - pinchBitmapY * scale;\n                if (editMode) constrainImage(); else constrainView();\n                invalidate();\n                return true;\n            }\n\n            @Override public void onScaleEnd(ScaleGestureDetector detector) {\n                if (editMode) mode = MODE_IMAGE;\n            }"""
s = replace_once(s, old_listener, new_listener, "stable scale listener")
CROP.write_text(s, encoding="utf-8")


# -----------------------------------------------------------------------------
# 2) Gallery architecture: GridView recycles only visible cells instead of
# creating/decoding hundreds of ImageViews inside a ScrollView each time.
# Add a thumbnail LRU cache so going Editor -> Gallery is instantaneous.
# -----------------------------------------------------------------------------
m = MAIN.read_text(encoding="utf-8")
m = replace_once(m, "import android.widget.FrameLayout;\n", "import android.widget.FrameLayout;\nimport android.widget.BaseAdapter;\nimport android.widget.GridView;\n", "GridView imports")

m = replace_once(
    m,
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(4);\n    private final ExecutorService photoIoPool = Executors.newFixedThreadPool(2);\n    private final LruCache<Integer, Bitmap> fullPhotoCache = new LruCache<Integer, Bitmap>(96 * 1024) {""",
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(3);\n    private final ExecutorService photoIoPool = Executors.newSingleThreadExecutor();\n    private final ExecutorService saveIoPool = Executors.newSingleThreadExecutor();\n    private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(24 * 1024) {\n        @Override protected int sizeOf(String key, Bitmap value) {\n            return Math.max(1, value.getAllocationByteCount() / 1024);\n        }\n    };\n    private final LruCache<Integer, Bitmap> fullPhotoCache = new LruCache<Integer, Bitmap>(48 * 1024) {""",
    "lighter caches and separate IO pools",
)

m = replace_once(
    m,
    """    private int loadGeneration = 0;\n    private boolean swipeAnimating = false;\n\n    private CropEditorView editor;""",
    """    private int loadGeneration = 0;\n    private boolean swipeAnimating = false;\n    private int galleryFirstVisible = 0;\n    private int galleryTop = 0;\n\n    private CropEditorView editor;""",
    "gallery position fields",
)

# Reset saved gallery position when another album is selected.
m = replace_once(
    m,
    """            cell.setOnClickListener(v -> {\n                selectedAlbum = album;\n                loadSelectedAlbum();\n                showGallery();\n            });""",
    """            cell.setOnClickListener(v -> {\n                selectedAlbum = album;\n                galleryFirstVisible = 0;\n                galleryTop = 0;\n                loadSelectedAlbum();\n                showGallery();\n            });""",
    "album gallery reset",
)

# Replace the whole gallery screen with a recycling GridView.
gs, ge = function_bounds(m, "    private void showGallery()")
new_gallery = r'''    private void showGallery() {
        currentScreen = SCREEN_GALLERY;
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(5), dp(10), dp(5));
        ImageButton folders = makeIconButton(R.drawable.ic_folder, "Выбрать папку", 0xFF273449, dp(11));
        folders.setOnClickListener(v -> { refreshAlbums(); showFolders(); });
        header.addView(folders, new LinearLayout.LayoutParams(dp(44), dp(42)));

        TextView title = new TextView(this);
        title.setText(selectedAlbum != null ? selectedAlbum.name : "Фото");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, dp(6), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        page.addView(header);

        final GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setBackgroundColor(Color.BLACK);
        grid.setCacheColorHint(Color.BLACK);
        grid.setSmoothScrollbarEnabled(true);
        grid.setScrollingCacheEnabled(true);
        final int tile = getResources().getDisplayMetrics().widthPixels / 3;
        final int count = Math.min(editorImages.size(), MAX_FOLDER_ITEMS);

        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return count; }
            @Override public Object getItem(int position) { return editorImages.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
                ImageView image;
                if (convertView instanceof ImageView) {
                    image = (ImageView) convertView;
                } else {
                    image = new ImageView(MainActivity.this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(0xFF202020);
                    image.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tile));
                }
                final Uri uri = editorImages.get(position);
                loadThumbnailCompat(image, uri, tile);
                return image;
            }
        });

        grid.setOnItemClickListener((parent, view, position, id) -> {
            galleryFirstVisible = grid.getFirstVisiblePosition();
            android.view.View first = grid.getChildAt(0);
            galleryTop = first != null ? first.getTop() : 0;
            index = position;
            showEditor();
            loadCurrent();
        });

        page.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPageInsets(page, null);
        grid.post(() -> grid.setSelectionFromTop(Math.max(0, galleryFirstVisible), galleryTop));
    }'''
m = m[:gs] + new_gallery + m[ge:]

# Replace thumbnail loader: memory cache first, no blank flash for cached cells.
ts, te = function_bounds(m, "    private void loadThumbnailCompat(")
new_thumb = r'''    private void loadThumbnailCompat(ImageView imageView, Uri uri, int size) {
        final String tag = uri.toString() + "@" + size;
        imageView.setTag(tag);
        Bitmap cached = thumbnailCache.get(tag);
        if (cached != null && !cached.isRecycled()) {
            imageView.animate().cancel();
            imageView.setAlpha(1f);
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.animate().cancel();
        imageView.setAlpha(1f);
        imageView.setImageDrawable(null);
        thumbnailPool.execute(() -> {
            Bitmap thumb = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    thumb = getContentResolver().loadThumbnail(uri, new Size(size, size), null);
                }
            } catch (Exception ignored) { }
            if (thumb == null) thumb = decodeSampled(uri, size, size);
            final Bitmap ready = thumb;
            if (ready != null) thumbnailCache.put(tag, ready);
            runOnUiThread(() -> {
                if (!tag.equals(imageView.getTag()) || ready == null) return;
                imageView.setImageBitmap(ready);
                imageView.setAlpha(0.82f);
                imageView.animate().alpha(1f).setDuration(90L).start();
            });
        });
    }'''
m = m[:ts] + new_thumb + m[te:]

# Stop decoding both full-size neighbors in the background; that was causing CPU,
# memory pressure and GC pauses on every swipe.
ps, pe = function_bounds(m, "    private void preloadNeighbors(")
new_preload = r'''    private void preloadNeighbors(int center) {
        // Intentionally no full-resolution preloading. Full 12/50 MP bitmaps are
        // expensive and made browsing slower. Gallery thumbnails are cached instead.
    }'''
m = m[:ps] + new_preload + m[pe:]

# Remove the artificial two-stage 135ms + 185ms page animation. Change index
# immediately; generation checks still prevent stale loads from replacing the view.
rs, re_ = function_bounds(m, "    private void showRelative(")
new_relative = r'''    private void showRelative(int delta) {
        if (editorImages.isEmpty() || editor == null) return;
        int next = index + delta;
        if (next < 0 || next >= editorImages.size()) return;
        editor.animate().cancel();
        swipeAnimating = false;
        index = next;
        loadCurrent(true, delta);
    }'''
m = m[:rs] + new_relative + m[re_:]

# Make the incoming movement subtle and short. It should read as smooth, not lag.
ss, se = function_bounds(m, "    private void showLoadedBitmap(")
show = m[ss:se]
show = show.replace("width * 0.34f", "width * 0.12f")
show = show.replace("editor.setAlpha(0.72f);", "editor.setAlpha(0.90f);")
show = show.replace(".setDuration(185L)", ".setDuration(95L)")
m = m[:ss] + show + m[se:]

# Save compression gets its own worker. A swipe after tapping Save no longer waits
# behind JPEG compression in the same executor queue.
svs, sve = function_bounds(m, "    private void saveAndStay()")
save = m[svs:sve]
needle = "        photoIoPool.execute(() -> {"
if needle not in save:
    raise RuntimeError("Missing save worker anchor")
save = save.replace(needle, "        saveIoPool.execute(() -> {", 1)
m = m[:svs] + save + m[sve:]

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 12', 'versionCode = 13', 'versionCode')
g = replace_once(g, 'versionName = "0.1.11"', 'versionName = "0.1.12"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.12 performance overhaul applied")
