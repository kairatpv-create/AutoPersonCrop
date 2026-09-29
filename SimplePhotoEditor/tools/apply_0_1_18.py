from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_17.py"), run_name="__main__")

MAIN = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/MainActivity.java"
CROP = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/CropEditorView.java"
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

# Multi-selection state for gallery deletion.
m = replace_once(
    m,
    "import java.util.List;\n",
    "import java.util.List;\nimport java.util.HashSet;\nimport java.util.Set;\n",
    "selection imports",
)

m = replace_once(
    m,
    "    private Album cachedGalleryAlbum = null;\n",
    "    private Album cachedGalleryAlbum = null;\n    private final Set<String> selectedGalleryUris = new HashSet<>();\n    private boolean gallerySelectionMode = false;\n    private ImageButton galleryDeleteButton = null;\n    private TextView gallerySelectionCount = null;\n",
    "gallery selection fields",
)

# Remove editor back button from the bottom bar. System/phone Back remains the only back action.
m = replace_once(
    m,
    "        ImageButton back = makeIconButton(R.drawable.ic_back, \"Назад\", 0xFF374151, dp(13));\n        back.setOnClickListener(v -> showGallery());\n",
    "",
    "remove bottom back button",
)
m = replace_once(
    m,
    "        bar.addView(back, p);\n",
    "",
    "remove bottom back placement",
)

# Add selection UI to the persistent gallery header.
old_title_add = "        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));\n        page.addView(header);\n"
new_title_add = '''        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));\n\n        gallerySelectionCount = new TextView(this);\n        gallerySelectionCount.setTextColor(Color.WHITE);\n        gallerySelectionCount.setTextSize(15);\n        gallerySelectionCount.setGravity(Gravity.CENTER_VERTICAL);\n        gallerySelectionCount.setVisibility(android.view.View.GONE);\n        header.addView(gallerySelectionCount, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));\n\n        galleryDeleteButton = makeIconButton(android.R.drawable.ic_menu_delete, \"Удалить выбранные\", 0xFF8B2E2E, dp(11));\n        galleryDeleteButton.setVisibility(android.view.View.GONE);\n        galleryDeleteButton.setOnClickListener(v -> deleteSelectedGalleryItems());\n        header.addView(galleryDeleteButton, new LinearLayout.LayoutParams(dp(44), dp(42)));\n        page.addView(header);\n'''
m = replace_once(m, old_title_add, new_title_add, "gallery selection header")

# Gallery item rendering: visibly mark selected images.
old_thumb_line = "                final Uri uri = editorImages.get(position);\n                loadThumbnailCompat(image, uri, tile);\n                return image;\n"
new_thumb_line = '''                final Uri uri = editorImages.get(position);\n                boolean selected = selectedGalleryUris.contains(uri.toString());\n                image.setAlpha(selected ? 0.58f : 1f);\n                image.setForeground(selected ? new android.graphics.drawable.ColorDrawable(0x5544CC77) : null);\n                loadThumbnailCompat(image, uri, tile);\n                return image;\n'''
m = replace_once(m, old_thumb_line, new_thumb_line, "selected gallery rendering")

# Replace gallery click behavior: long press starts selection, normal taps toggle while in selection mode.
old_click = '''        grid.setOnItemClickListener((parent, view, position, id) -> {\n            galleryFirstVisible = grid.getFirstVisiblePosition();\n            android.view.View first = grid.getChildAt(0);\n            galleryTop = first != null ? first.getTop() : 0;\n            galleryAnchorPosition = position;\n            galleryAnchorTop = view != null ? view.getTop() : 0;\n            index = position;\n            showEditor();\n            loadCurrent();\n        });'''
new_click = '''        grid.setOnItemClickListener((parent, view, position, id) -> {\n            if (gallerySelectionMode) {\n                toggleGallerySelection(position);\n                return;\n            }\n            galleryFirstVisible = grid.getFirstVisiblePosition();\n            android.view.View first = grid.getChildAt(0);\n            galleryTop = first != null ? first.getTop() : 0;\n            galleryAnchorPosition = position;\n            galleryAnchorTop = view != null ? view.getTop() : 0;\n            index = position;\n            showEditor();\n            loadCurrent();\n        });\n        grid.setOnItemLongClickListener((parent, view, position, id) -> {\n            gallerySelectionMode = true;\n            toggleGallerySelection(position);\n            return true;\n        });'''
m = replace_once(m, old_click, new_click, "gallery multi selection gestures")

# Helpers for selection and batch deletion, inserted before requestDeleteCurrent.
anchor = "    private void requestDeleteCurrent() {"
pos = m.find(anchor)
if pos < 0:
    raise RuntimeError("requestDeleteCurrent anchor missing")
helpers = r'''    private void updateGallerySelectionUi() {
        int count = selectedGalleryUris.size();
        gallerySelectionMode = count > 0;
        if (galleryDeleteButton != null) galleryDeleteButton.setVisibility(count > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (gallerySelectionCount != null) {
            gallerySelectionCount.setText(count > 0 ? Integer.toString(count) : "");
            gallerySelectionCount.setVisibility(count > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        if (cachedGalleryGrid != null && cachedGalleryGrid.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) cachedGalleryGrid.getAdapter()).notifyDataSetChanged();
        }
    }

    private void toggleGallerySelection(int position) {
        if (position < 0 || position >= editorImages.size()) return;
        String key = editorImages.get(position).toString();
        if (selectedGalleryUris.contains(key)) selectedGalleryUris.remove(key);
        else selectedGalleryUris.add(key);
        updateGallerySelectionUi();
    }

    private boolean deleteUriDirect(Uri uri) {
        String path = queryDataPath(uri);
        boolean deleted = false;
        try {
            if (path != null && !path.isEmpty()) {
                File file = new File(path);
                deleted = !file.exists() || file.delete();
                if (deleted) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                    MediaScannerConnection.scanFile(MainActivity.this, new String[]{path}, null, null);
                }
            }
            if (!deleted) {
                try { deleted = getContentResolver().delete(uri, null, null) > 0; }
                catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return deleted;
    }

    private void deleteSelectedGalleryItems() {
        if (selectedGalleryUris.isEmpty()) return;
        final int first = cachedGalleryGrid != null ? cachedGalleryGrid.getFirstVisiblePosition() : 0;
        final android.view.View firstView = cachedGalleryGrid != null ? cachedGalleryGrid.getChildAt(0) : null;
        final int top = firstView != null ? firstView.getTop() : 0;
        final List<Uri> targets = new ArrayList<>();
        for (Uri uri : editorImages) {
            if (selectedGalleryUris.contains(uri.toString())) targets.add(uri);
        }
        saveIoPool.execute(() -> {
            final List<Uri> deleted = new ArrayList<>();
            for (Uri uri : targets) if (deleteUriDirect(uri)) deleted.add(uri);
            runOnUiThread(() -> {
                for (Uri uri : deleted) editorImages.remove(uri);
                selectedGalleryUris.clear();
                gallerySelectionMode = false;
                previewCache.evictAll();
                fullPhotoCache.evictAll();
                thumbnailCache.evictAll();
                updateGallerySelectionUi();
                if (cachedGalleryGrid != null) {
                    cachedGalleryGrid.post(() -> cachedGalleryGrid.setSelectionFromTop(
                            Math.max(0, Math.min(first, Math.max(0, editorImages.size() - 1))), top));
                }
                Toast.makeText(MainActivity.this,
                        deleted.size() + " удалено", Toast.LENGTH_SHORT).show();
            });
        });
    }

'''
m = m[:pos] + helpers + m[pos:]

# Reuse the direct-delete helper for current-photo deletion too.
rs, re_ = function_bounds(m, "    private void requestDeleteCurrent()")
new_request_delete = r'''    private void requestDeleteCurrent() {
        if (index < 0 || index >= editorImages.size()) return;
        final Uri uri = editorImages.get(index);
        saveIoPool.execute(() -> {
            final boolean ok = deleteUriDirect(uri);
            runOnUiThread(() -> {
                if (ok) finishDelete(uri);
                else Toast.makeText(MainActivity.this,
                        "Android не разрешил удалить этот файл напрямую",
                        Toast.LENGTH_LONG).show();
            });
        });
    }'''
m = m[:rs] + new_request_delete + m[re_:]

# Build a small in-memory thumbnail directly from the just-edited bitmap. This is
# inserted into the gallery cache BEFORE JPEG/PNG disk compression, so returning to
# the gallery shows the edited result instantly instead of waiting several seconds.
anchor = "    private void refreshThumbnailAfterSave(Uri uri) {"
pos = m.find(anchor)
if pos < 0:
    raise RuntimeError("refreshThumbnailAfterSave anchor missing")
instant_method = r'''    private Bitmap createInstantThumbnail(Bitmap source, int maxSide) {
        if (source == null || source.isRecycled()) return null;
        int sw = source.getWidth(), sh = source.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        float factor = Math.min(1f, (float) maxSide / Math.max(sw, sh));
        int w = Math.max(1, Math.round(sw * factor));
        int h = Math.max(1, Math.round(sh * factor));
        return Bitmap.createScaledBitmap(source, w, h, true);
    }

    private void publishEditedThumbnailNow(Uri uri, Bitmap result) {
        if (uri == null || result == null) return;
        int tile = Math.max(1, getResources().getDisplayMetrics().widthPixels / 3);
        Bitmap thumb = createInstantThumbnail(result, tile * 2);
        if (thumb != null) {
            String base = uri.toString();
            thumbnailCache.put(base + "@" + tile, thumb);
            thumbnailCache.put(base + "@quick", thumb);
        }
        if (cachedGalleryGrid != null && cachedGalleryGrid.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) cachedGalleryGrid.getAdapter()).notifyDataSetChanged();
        }
    }

'''
m = m[:pos] + instant_method + m[pos:]

# Publish the edited thumbnail immediately while the exact result bitmap is still
# available, before starting the background file write.
needle = '''        thumbnailCache.remove(editedUriForCache.toString() + "@" + Math.max(1, getResources().getDisplayMetrics().widthPixels / 3));\n        displayingFullResolution = true;'''
replacement = '''        thumbnailCache.remove(editedUriForCache.toString() + "@" + Math.max(1, getResources().getDisplayMetrics().widthPixels / 3));\n        publishEditedThumbnailNow(editedUriForCache, result);\n        displayingFullResolution = true;'''
m = replace_once(m, needle, replacement, "instant edited thumbnail")

# Do not replace the instant thumbnail several seconds later with an OEM-cached
# MediaStore thumbnail. Keep disk refresh in the background only as a cache safety
# fallback; direct URI decode remains correct but UI already shows the result.

# Leaving a folder cancels selection mode.
m = replace_once(
    m,
    "            cachedGalleryAlbum = null;\n            refreshAlbums();\n            showFolders();\n",
    "            cachedGalleryAlbum = null;\n            selectedGalleryUris.clear();\n            gallerySelectionMode = false;\n            refreshAlbums();\n            showFolders();\n",
    "clear selection on folders",
)

MAIN.write_text(m, encoding="utf-8")

# Viewer display mode: when NOT cropping, fit image width exactly to the view. This
# removes black side borders while keeping aspect ratio; top/bottom black space is
# allowed when the image is shorter than the screen.
s = CROP.read_text(encoding="utf-8")
old_reset_start = '''    private void resetGeometry() {\n        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;\n        float margin = dp(18f);'''
new_reset_start = '''    private void resetGeometry() {\n        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;\n        if (!editMode) {\n            minScale = (float) getWidth() / Math.max(1, bitmap.getWidth());\n            scale = minScale;\n            tx = 0f;\n            ty = (getHeight() - bitmap.getHeight() * scale) / 2f;\n            constrainView();\n            return;\n        }\n        float margin = dp(18f);'''
s = replace_once(s, old_reset_start, new_reset_start, "fit viewer to width")
CROP.write_text(s, encoding="utf-8")

# Version bump.
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 18', 'versionCode = 19', 'versionCode')
g = replace_once(g, 'versionName = "0.1.17"', 'versionName = "0.1.18"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.18 instant thumbnails/multi-select/fit-width patch applied")
