from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_16.py"), run_name="__main__")

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

# -----------------------------------------------------------------------------
# After editing/saving, regenerate the gallery thumbnail from the actual rewritten
# file. MediaStore.loadThumbnail may return an OEM/system cached pre-edit image, so
# deliberately decode the small thumbnail from the URI after the write completes.
# -----------------------------------------------------------------------------
anchor = "    private boolean writeBitmapDirect(Uri uri, Bitmap bitmap) throws IOException {"
pos = m.find(anchor)
if pos < 0:
    raise RuntimeError("Missing writeBitmapDirect anchor")
refresh_method = r'''    private void refreshThumbnailAfterSave(Uri uri) {
        if (uri == null) return;
        int tile = Math.max(1, getResources().getDisplayMetrics().widthPixels / 3);
        String base = uri.toString();
        thumbnailCache.remove(base + "@" + tile);
        thumbnailCache.remove(base + "@quick");
        Bitmap fresh = decodeSampled(uri, tile, tile);
        if (fresh != null) {
            thumbnailCache.put(base + "@" + tile, fresh);
            thumbnailCache.put(base + "@quick", fresh);
        }
        runOnUiThread(() -> {
            if (cachedGalleryGrid != null && cachedGalleryGrid.getAdapter() instanceof BaseAdapter) {
                ((BaseAdapter) cachedGalleryGrid.getAdapter()).notifyDataSetChanged();
            }
        });
    }

'''
m = m[:pos] + refresh_method + m[pos:]

# Refresh thumbnail only after the file has really been written, so the gallery
# always shows the edited pixels instead of a stale thumbnail.
old_write_success = '''                if (!writeBitmapDirect(uri, result)) writeBitmapViaResolver(uri, result);\n                runOnUiThread(() -> Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show());'''
new_write_success = '''                if (!writeBitmapDirect(uri, result)) writeBitmapViaResolver(uri, result);\n                refreshThumbnailAfterSave(uri);\n                runOnUiThread(() -> Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show());'''
m = replace_once(m, old_write_success, new_write_success, "refresh saved thumbnail")

# Also invalidate the preview cache for this exact position immediately so swiping
# away and back cannot reuse the pre-edit screen preview while disk save finishes.
m = replace_once(
    m,
    '''        previewCache.remove(index);\n        displayingFullResolution = true;''',
    '''        previewCache.remove(index);\n        Uri editedUriForCache = editorImages.get(index);\n        thumbnailCache.remove(editedUriForCache.toString() + "@quick");\n        thumbnailCache.remove(editedUriForCache.toString() + "@" + Math.max(1, getResources().getDisplayMetrics().widthPixels / 3));\n        displayingFullResolution = true;''',
    "invalidate edited thumbnail cache",
)

# -----------------------------------------------------------------------------
# Make paging visibly read as paging. The current photo first moves out in the
# swipe direction, then the next one enters from the opposite side. Keep the total
# transition short (~165 ms) so the effect is obvious without feeling sluggish.
# -----------------------------------------------------------------------------
rs, re_ = function_bounds(m, "    private void showRelative(int delta)")
new_relative = r'''    private void showRelative(int delta) {
        if (editorImages.isEmpty() || editor == null || swipeAnimating) return;
        int next = index + delta;
        if (next < 0 || next >= editorImages.size()) return;
        swipeAnimating = true;
        editor.animate().cancel();
        float width = Math.max(1f, editor.getWidth());
        editor.animate()
                .translationX(delta > 0 ? -width * 0.24f : width * 0.24f)
                .alpha(0.94f)
                .setDuration(70L)
                .withEndAction(() -> {
                    index = next;
                    loadCurrent(true, delta);
                })
                .start();
    }'''
m = m[:rs] + new_relative + m[re_:]

ss, se = function_bounds(m, "    private void showLoadedBitmap(")
show = m[ss:se]
# 0.1.14 had a tiny 3.5%/45ms motion. Make the incoming page clearly visible.
show = show.replace("width * 0.035f", "width * 0.24f")
show = show.replace(".setDuration(45L)", ".setDuration(95L)")
# Ensure the incoming image is fully opaque; motion alone carries the transition.
show = show.replace("editor.setAlpha(0.96f);", "editor.setAlpha(1f);")
m = m[:ss] + show + m[se:]

# The quick-thumbnail upgrade in loadCurrent currently resets translation to zero,
# which can cut the visible incoming animation short. Only reset if no animation is
# running; otherwise leave the render-thread translation untouched.
old_upgrade = '''                editor.animate().cancel();\n                editor.setTranslationX(0f);\n                editor.setAlpha(1f);\n                editor.setBitmap(ready);'''
new_upgrade = '''                if (!swipeAnimating) {\n                    editor.animate().cancel();\n                    editor.setTranslationX(0f);\n                    editor.setAlpha(1f);\n                }\n                editor.setBitmap(ready);'''
m = replace_once(m, old_upgrade, new_upgrade, "preserve incoming swipe animation")

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 17', 'versionCode = 18', 'versionCode')
g = replace_once(g, 'versionName = "0.1.16"', 'versionName = "0.1.17"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.17 refreshed thumbnails/visible paging patch applied")
