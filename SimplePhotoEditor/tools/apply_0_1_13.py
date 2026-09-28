from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_12.py"), run_name="__main__")

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


m = MAIN.read_text(encoding="utf-8")

# Browsing must never wait for a 12-50 MP decode. Use screen-sized previews on
# dedicated workers; full resolution is loaded only when the user actually edits.
m = replace_once(
    m,
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(3);\n    private final ExecutorService photoIoPool = Executors.newSingleThreadExecutor();\n    private final ExecutorService saveIoPool = Executors.newSingleThreadExecutor();""",
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(3);\n    private final ExecutorService photoIoPool = Executors.newFixedThreadPool(2);\n    private final ExecutorService fullDecodePool = Executors.newSingleThreadExecutor();\n    private final ExecutorService saveIoPool = Executors.newSingleThreadExecutor();""",
    "separate preview and full decode pools",
)

m = replace_once(
    m,
    """    private final LruCache<Integer, Bitmap> fullPhotoCache = new LruCache<Integer, Bitmap>(48 * 1024) {\n        @Override protected int sizeOf(Integer key, Bitmap value) {\n            return Math.max(1, value.getAllocationByteCount() / 1024);\n        }\n    };\n    private int loadGeneration = 0;""",
    """    private final LruCache<Integer, Bitmap> previewCache = new LruCache<Integer, Bitmap>(48 * 1024) {\n        @Override protected int sizeOf(Integer key, Bitmap value) {\n            return Math.max(1, value.getAllocationByteCount() / 1024);\n        }\n    };\n    private final LruCache<Integer, Bitmap> fullPhotoCache = new LruCache<Integer, Bitmap>(24 * 1024) {\n        @Override protected int sizeOf(Integer key, Bitmap value) {\n            return Math.max(1, value.getAllocationByteCount() / 1024);\n        }\n    };\n    private int loadGeneration = 0;""",
    "screen preview cache",
)

m = replace_once(
    m,
    """    private int galleryFirstVisible = 0;\n    private int galleryTop = 0;\n\n    private CropEditorView editor;""",
    """    private int galleryFirstVisible = 0;\n    private int galleryTop = 0;\n    private boolean displayingFullResolution = false;\n    private int fullResolutionIndex = -1;\n\n    private CropEditorView editor;""",
    "full resolution state",
)

# Insert preview decoder before full decoder.
anchor = "    private Bitmap decodeFullPhoto(int position) {"
pos = m.find(anchor)
if pos < 0:
    raise RuntimeError("Missing decodeFullPhoto anchor")
preview_method = r'''    private Bitmap decodePreviewPhoto(int position) {
        if (position < 0 || position >= editorImages.size()) return null;
        Bitmap cached = previewCache.get(position);
        if (cached != null && !cached.isRecycled()) return cached;
        Uri uri = editorImages.get(position);
        int w = Math.max(1080, getResources().getDisplayMetrics().widthPixels * 2);
        int h = Math.max(1920, getResources().getDisplayMetrics().heightPixels * 2);
        Bitmap bitmap = decodeSampled(uri, w, h);
        if (bitmap != null) previewCache.put(position, bitmap);
        return bitmap;
    }

'''
m = m[:pos] + preview_method + m[pos:]

# Neighbor preload is now cheap screen-preview decoding only.
ps, pe = function_bounds(m, "    private void preloadNeighbors(")
new_preload = r'''    private void preloadNeighbors(int center) {
        for (int p : new int[]{center - 1, center + 1}) {
            if (p < 0 || p >= editorImages.size() || previewCache.get(p) != null) continue;
            final int position = p;
            photoIoPool.execute(() -> decodePreviewPhoto(position));
        }
    }'''
m = m[:ps] + new_preload + m[pe:]

# Replace current-photo loading: preview only, so a swipe never waits for a huge
# bitmap. The full file is loaded later only for crop/rotate/save.
ls, le = function_bounds(m, "    private void loadCurrent(boolean animated, int direction)")
new_load = r'''    private void loadCurrent(boolean animated, int direction) {
        if (editor == null || index < 0 || index >= editorImages.size()) return;
        final int expectedIndex = index;
        final int generation = ++loadGeneration;
        displayingFullResolution = false;
        fullResolutionIndex = -1;

        Bitmap cached = previewCache.get(expectedIndex);
        if (cached != null && !cached.isRecycled()) {
            showLoadedBitmap(cached, expectedIndex, generation, animated, direction);
            preloadNeighbors(expectedIndex);
            return;
        }

        photoIoPool.execute(() -> {
            final Bitmap ready = decodePreviewPhoto(expectedIndex);
            runOnUiThread(() -> showLoadedBitmap(ready, expectedIndex, generation, animated, direction));
            preloadNeighbors(expectedIndex);
        });
    }'''
m = m[:ls] + new_load + m[le:]

# Full resolution is requested only for real editing operations. Browsing stays
# light; crop quality remains original-resolution.
insert_before = "    private void showRelative(int delta) {"
pos = m.find(insert_before)
if pos < 0:
    raise RuntimeError("Missing showRelative anchor")
ensure_method = r'''    private void ensureFullResolutionThen(Runnable action) {
        if (editor == null || index < 0 || index >= editorImages.size()) return;
        final int expectedIndex = index;
        if (displayingFullResolution && fullResolutionIndex == expectedIndex) {
            action.run();
            return;
        }
        Bitmap cached = fullPhotoCache.get(expectedIndex);
        if (cached != null && !cached.isRecycled()) {
            editor.setBitmap(cached);
            displayingFullResolution = true;
            fullResolutionIndex = expectedIndex;
            action.run();
            return;
        }
        final int generation = ++loadGeneration;
        fullDecodePool.execute(() -> {
            final Bitmap ready = decodeFullPhoto(expectedIndex);
            runOnUiThread(() -> {
                if (ready == null || editor == null || currentScreen != SCREEN_EDITOR ||
                        expectedIndex != index || generation != loadGeneration) return;
                editor.setBitmap(ready);
                displayingFullResolution = true;
                fullResolutionIndex = expectedIndex;
                action.run();
            });
        });
    }

'''
m = m[:pos] + ensure_method + m[pos:]

# Crop and rotate first promote the current preview to the original bitmap.
m = replace_once(
    m,
    "rotate.setOnClickListener(v -> editor.rotate90());",
    "rotate.setOnClickListener(v -> ensureFullResolutionThen(() -> editor.rotate90()));",
    "full resolution rotate",
)
m = replace_once(
    m,
    "crop.setOnClickListener(v -> editor.setEditMode(true));",
    "crop.setOnClickListener(v -> ensureFullResolutionThen(() -> editor.setEditMode(true)));",
    "full resolution crop",
)

# Save also guarantees original-resolution data. Move the previous save body into
# an internal function, so tapping Done while merely browsing cannot overwrite the
# original with a screen preview.
ss, se = function_bounds(m, "    private void saveAndStay()")
old_save = m[ss:se]
body_start = old_save.find("{") + 1
body_end = old_save.rfind("}")
body = old_save[body_start:body_end]
new_save = """    private void saveAndStay() {\n        ensureFullResolutionThen(this::saveAndStayInternal);\n    }\n\n    private void saveAndStayInternal() {""" + body + "\n    }"
# The internal body no longer needs to cache a huge full result for browsing.
new_save = new_save.replace("        fullPhotoCache.put(index, result);\n        preloadNeighbors(index);", "        fullPhotoCache.remove(index);\n        previewCache.remove(index);\n        displayingFullResolution = true;\n        fullResolutionIndex = index;")
m = m[:ss] + new_save + m[se:]

# Keep the swipe animation extremely short; it should feel continuous, not staged.
shs, she = function_bounds(m, "    private void showLoadedBitmap(")
show = m[shs:she]
show = show.replace("width * 0.12f", "width * 0.06f")
show = show.replace("editor.setAlpha(0.90f);", "editor.setAlpha(0.96f);")
show = show.replace(".setDuration(95L)", ".setDuration(55L)")
m = m[:shs] + show + m[she:]

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 13', 'versionCode = 14', 'versionCode')
g = replace_once(g, 'versionName = "0.1.12"', 'versionName = "0.1.13"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.13 instant browsing patch applied")
