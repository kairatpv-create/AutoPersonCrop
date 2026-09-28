from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_10.py"), run_name="__main__")

MAIN = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/MainActivity.java"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing anchor: {label}")
    return text.replace(old, new, 1)

m = MAIN.read_text(encoding="utf-8")

# LRU cache for current/neighbor full-size bitmaps. This makes most swipes instant.
m = replace_once(
    m,
    "import android.util.Size;\n",
    "import android.util.Size;\nimport android.util.LruCache;\n",
    "LruCache import",
)

m = replace_once(
    m,
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(4);\n    private final ExecutorService photoIoPool = Executors.newFixedThreadPool(2);\n    private int loadGeneration = 0;\n\n    private CropEditorView editor;""",
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(4);\n    private final ExecutorService photoIoPool = Executors.newFixedThreadPool(2);\n    private final LruCache<Integer, Bitmap> fullPhotoCache = new LruCache<Integer, Bitmap>(96 * 1024) {\n        @Override protected int sizeOf(Integer key, Bitmap value) {\n            return Math.max(1, value.getAllocationByteCount() / 1024);\n        }\n    };\n    private int loadGeneration = 0;\n    private boolean swipeAnimating = false;\n\n    private CropEditorView editor;""",
    "full photo cache",
)

# Smooth thumbnail appearance in gallery/folder grids.
m = replace_once(
    m,
    """        imageView.setTag(tag);\n        imageView.setImageDrawable(null);""",
    """        imageView.setTag(tag);\n        imageView.setImageDrawable(null);\n        imageView.setAlpha(0f);""",
    "thumbnail fade start",
)

m = replace_once(
    m,
    """                        imageView.setImageBitmap(ready);\n                        imageView.setAlpha(1f);\n                        imageView.invalidate();""",
    """                        imageView.setImageBitmap(ready);\n                        imageView.animate().cancel();\n                        imageView.animate().alpha(1f).setDuration(170L).start();\n                        imageView.invalidate();""",
    "thumbnail fade in",
)

old_load = """    private void loadCurrent() {\n        if (editor == null || index < 0 || index >= editorImages.size()) return;\n        final Uri uri = editorImages.get(index);\n        final int expectedIndex = index;\n        final int generation = ++loadGeneration;\n        photoIoPool.execute(() -> {\n            Bitmap bitmap = null;\n            try (InputStream in = getContentResolver().openInputStream(uri)) {\n                if (in != null) bitmap = BitmapFactory.decodeStream(in);\n            } catch (IOException ignored) { }\n            final Bitmap ready = bitmap;\n            runOnUiThread(() -> {\n                if (generation != loadGeneration || expectedIndex != index || currentScreen != SCREEN_EDITOR || editor == null) {\n                    if (ready != null && !ready.isRecycled()) ready.recycle();\n                    return;\n                }\n                if (ready != null) editor.setBitmap(ready);\n                else Toast.makeText(this, \"Не удалось открыть фото\", Toast.LENGTH_SHORT).show();\n            });\n        });\n    }"""
new_load = """    private Bitmap decodeFullPhoto(int position) {\n        if (position < 0 || position >= editorImages.size()) return null;\n        Bitmap cached = fullPhotoCache.get(position);\n        if (cached != null && !cached.isRecycled()) return cached;\n        Uri uri = editorImages.get(position);\n        Bitmap bitmap = null;\n        try (InputStream in = getContentResolver().openInputStream(uri)) {\n            if (in != null) bitmap = BitmapFactory.decodeStream(in);\n        } catch (IOException ignored) { }\n        if (bitmap != null) fullPhotoCache.put(position, bitmap);\n        return bitmap;\n    }\n\n    private void preloadNeighbors(int center) {\n        for (int p : new int[]{center - 1, center + 1}) {\n            if (p < 0 || p >= editorImages.size() || fullPhotoCache.get(p) != null) continue;\n            photoIoPool.execute(() -> decodeFullPhoto(p));\n        }\n    }\n\n    private void loadCurrent() {\n        loadCurrent(false, 0);\n    }\n\n    private void loadCurrent(boolean animated, int direction) {\n        if (editor == null || index < 0 || index >= editorImages.size()) return;\n        final int expectedIndex = index;\n        final int generation = ++loadGeneration;\n        Bitmap cached = fullPhotoCache.get(expectedIndex);\n        if (cached != null && !cached.isRecycled()) {\n            showLoadedBitmap(cached, expectedIndex, generation, animated, direction);\n            preloadNeighbors(expectedIndex);\n            return;\n        }\n        photoIoPool.execute(() -> {\n            final Bitmap ready = decodeFullPhoto(expectedIndex);\n            runOnUiThread(() -> showLoadedBitmap(ready, expectedIndex, generation, animated, direction));\n            preloadNeighbors(expectedIndex);\n        });\n    }\n\n    private void showLoadedBitmap(Bitmap ready, int expectedIndex, int generation, boolean animated, int direction) {\n        if (generation != loadGeneration || expectedIndex != index || currentScreen != SCREEN_EDITOR || editor == null) return;\n        if (ready == null) {\n            swipeAnimating = false;\n            editor.setTranslationX(0f);\n            editor.setAlpha(1f);\n            Toast.makeText(this, \"Не удалось открыть фото\", Toast.LENGTH_SHORT).show();\n            return;\n        }\n        editor.animate().cancel();\n        editor.setBitmap(ready);\n        if (animated) {\n            float width = Math.max(1f, editor.getWidth());\n            editor.setTranslationX(direction > 0 ? width * 0.34f : -width * 0.34f);\n            editor.setAlpha(0.72f);\n            editor.animate()\n                    .translationX(0f)\n                    .alpha(1f)\n                    .setDuration(185L)\n                    .withEndAction(() -> swipeAnimating = false)\n                    .start();\n        } else {\n            editor.setTranslationX(0f);\n            editor.setAlpha(1f);\n            swipeAnimating = false;\n        }\n    }"""
m = replace_once(m, old_load, new_load, "cached async photo loading")

old_relative = """    private void showRelative(int delta) {\n        if (editorImages.isEmpty()) return;\n        int next = index + delta;\n        if (next < 0 || next >= editorImages.size()) return;\n        index = next;\n        loadCurrent();\n    }"""
new_relative = """    private void showRelative(int delta) {\n        if (editorImages.isEmpty() || editor == null || swipeAnimating) return;\n        int next = index + delta;\n        if (next < 0 || next >= editorImages.size()) return;\n        swipeAnimating = true;\n        editor.animate().cancel();\n        float width = Math.max(1f, editor.getWidth());\n        editor.animate()\n                .translationX(delta > 0 ? -width * 0.34f : width * 0.34f)\n                .alpha(0.72f)\n                .setDuration(135L)\n                .withEndAction(() -> {\n                    index = next;\n                    loadCurrent(true, delta);\n                })\n                .start();\n    }"""
m = replace_once(m, old_relative, new_relative, "smooth relative swipe")

# Keep the just-saved result in cache so returning/swiping back is immediate.
m = replace_once(
    m,
    """        editor.setBitmap(result);\n\n        photoIoPool.execute(() -> {""",
    """        editor.setBitmap(result);\n        fullPhotoCache.put(index, result);\n        preloadNeighbors(index);\n\n        photoIoPool.execute(() -> {""",
    "save cache refresh",
)

MAIN.write_text(m, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 11', 'versionCode = 12', 'versionCode')
g = replace_once(g, 'versionName = "0.1.10"', 'versionName = "0.1.11"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.11 smooth browsing patch applied")
