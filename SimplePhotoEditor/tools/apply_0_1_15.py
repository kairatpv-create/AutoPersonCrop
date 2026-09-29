from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_14.py"), run_name="__main__")

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
# Delete current file with Android's MediaStore confirmation on modern Android.
# -----------------------------------------------------------------------------
m = replace_once(
    m,
    "    private static final int REQ_ALL_FILES = 103;\n",
    "    private static final int REQ_ALL_FILES = 103;\n    private static final int REQ_DELETE = 104;\n",
    "delete request code",
)

m = replace_once(
    m,
    "    private boolean pendingSaveAfterPermission = false;\n",
    "    private boolean pendingSaveAfterPermission = false;\n    private Uri pendingDeleteUri = null;\n",
    "pending delete uri",
)

# Store the clicked tile itself and exact top offset. Restoring by first-visible row
# was too approximate and sometimes returned to the beginning after rebuilding grid.
m = replace_once(
    m,
    "    private int galleryFirstVisible = 0;\n    private int galleryTop = 0;\n",
    "    private int galleryFirstVisible = 0;\n    private int galleryTop = 0;\n    private int galleryAnchorPosition = 0;\n    private int galleryAnchorTop = 0;\n",
    "gallery exact anchor fields",
)

# Reset the exact anchor when switching folders.
m = replace_once(
    m,
    "                galleryFirstVisible = 0;\n                galleryTop = 0;\n",
    "                galleryFirstVisible = 0;\n                galleryTop = 0;\n                galleryAnchorPosition = 0;\n                galleryAnchorTop = 0;\n",
    "album anchor reset",
)

# Modify GridView click handling and restoration.
old_click = '''        grid.setOnItemClickListener((parent, view, position, id) -> {\n            galleryFirstVisible = grid.getFirstVisiblePosition();\n            android.view.View first = grid.getChildAt(0);\n            galleryTop = first != null ? first.getTop() : 0;\n            index = position;\n            showEditor();\n            loadCurrent();\n        });'''
new_click = '''        grid.setOnItemClickListener((parent, view, position, id) -> {\n            galleryFirstVisible = grid.getFirstVisiblePosition();\n            android.view.View first = grid.getChildAt(0);\n            galleryTop = first != null ? first.getTop() : 0;\n            galleryAnchorPosition = position;\n            galleryAnchorTop = view != null ? view.getTop() : 0;\n            index = position;\n            showEditor();\n            loadCurrent();\n        });'''
m = replace_once(m, old_click, new_click, "gallery exact click anchor")

m = replace_once(
    m,
    "        grid.post(() -> grid.setSelectionFromTop(Math.max(0, galleryFirstVisible), galleryTop));\n",
    '''        grid.post(() -> {\n            int target = Math.max(0, Math.min(galleryAnchorPosition, Math.max(0, count - 1)));\n            grid.setSelectionFromTop(target, galleryAnchorTop);\n            // A second pass after layout prevents OEM GridView implementations from\n            // snapping back to row zero during the first layout/insets pass.\n            grid.post(() -> grid.setSelectionFromTop(target, galleryAnchorTop));\n        });\n''',
    "gallery precise restore",
)

# Keep an instantly available low-resolution frame for every gallery thumbnail.
# A swipe can show this immediately while the screen-resolution preview decodes.
m = replace_once(
    m,
    "            if (ready != null) thumbnailCache.put(tag, ready);\n",
    "            if (ready != null) {\n                thumbnailCache.put(tag, ready);\n                thumbnailCache.put(uri.toString() + \"@quick\", ready);\n            }\n",
    "quick thumbnail cache",
)

# Make current-image loading two-stage: cached preview if available; otherwise show
# the gallery thumbnail immediately, then silently upgrade to screen resolution.
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

        Uri expectedUri = editorImages.get(expectedIndex);
        Bitmap quick = thumbnailCache.get(expectedUri.toString() + "@quick");
        if (quick != null && !quick.isRecycled()) {
            showLoadedBitmap(quick, expectedIndex, generation, animated, direction);
        }

        photoIoPool.execute(() -> {
            final Bitmap ready = decodePreviewPhoto(expectedIndex);
            runOnUiThread(() -> {
                if (ready == null || expectedIndex != index || generation != loadGeneration ||
                        currentScreen != SCREEN_EDITOR || editor == null) return;
                // Upgrade the already-visible quick image without a second slide.
                editor.animate().cancel();
                editor.setTranslationX(0f);
                editor.setAlpha(1f);
                editor.setBitmap(ready);
            });
            preloadNeighbors(expectedIndex);
        });
    }'''
m = m[:ls] + new_load + m[le:]

# Warm two images in the direction most users are likely to continue swiping while
# still keeping all decoding outside the UI thread.
ps, pe = function_bounds(m, "    private void preloadNeighbors(")
new_preload = r'''    private void preloadNeighbors(int center) {
        for (int p : new int[]{center + 1, center - 1, center + 2, center - 2}) {
            if (p < 0 || p >= editorImages.size() || previewCache.get(p) != null) continue;
            final int position = p;
            photoIoPool.execute(() -> decodePreviewPhoto(position));
        }
    }'''
m = m[:ps] + new_preload + m[pe:]

# Editor toolbar: add a dedicated delete action. Keep all six buttons equal width.
old_done = '''        ImageButton done = makeIconButton(R.drawable.ic_check, "Сохранить", 0xFF1F8A70, dp(13));\n        done.setOnClickListener(v -> saveAndStay());'''
new_done = '''        ImageButton done = makeIconButton(R.drawable.ic_check, "Сохранить", 0xFF1F8A70, dp(13));\n        done.setOnClickListener(v -> saveAndStay());\n        ImageButton delete = makeIconButton(android.R.drawable.ic_menu_delete, "Удалить файл", 0xFF8B2E2E, dp(13));\n        delete.setOnClickListener(v -> requestDeleteCurrent());'''
m = replace_once(m, old_done, new_done, "delete toolbar button")

m = replace_once(
    m,
    "        bar.addView(crop, p);\n        bar.addView(done, p);\n",
    "        bar.addView(crop, p);\n        bar.addView(done, p);\n        bar.addView(delete, p);\n",
    "delete toolbar placement",
)

# Insert delete helpers before writeBitmapDirect.
anchor = "    private boolean writeBitmapDirect(Uri uri, Bitmap bitmap) throws IOException {"
pos = m.find(anchor)
if pos < 0:
    raise RuntimeError("Missing writeBitmapDirect anchor")
delete_methods = r'''    private void requestDeleteCurrent() {
        if (index < 0 || index >= editorImages.size()) return;
        final Uri uri = editorImages.get(index);
        pendingDeleteUri = uri;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(
                        getContentResolver(), Collections.singletonList(uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                pendingDeleteUri = null;
                Toast.makeText(this, "Не удалось запросить удаление", Toast.LENGTH_LONG).show();
            }
            return;
        }
        try {
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) finishDelete(uri);
            else Toast.makeText(this, "Не удалось удалить файл", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось удалить файл", Toast.LENGTH_LONG).show();
        } finally {
            pendingDeleteUri = null;
        }
    }

    private void finishDelete(Uri uri) {
        int oldIndex = editorImages.indexOf(uri);
        if (oldIndex < 0) oldIndex = index;
        editorImages.remove(uri);
        previewCache.remove(oldIndex);
        fullPhotoCache.remove(oldIndex);
        thumbnailCache.remove(uri.toString() + "@quick");
        // Position-based preview cache keys after the deleted photo are no longer
        // valid, so clear them instead of risking mismatched images.
        previewCache.evictAll();
        fullPhotoCache.evictAll();
        displayingFullResolution = false;
        fullResolutionIndex = -1;
        pendingDeleteUri = null;

        if (editorImages.isEmpty()) {
            index = -1;
            galleryAnchorPosition = 0;
            galleryAnchorTop = 0;
            refreshAlbums();
            showGallery();
            Toast.makeText(this, "Файл удалён", Toast.LENGTH_SHORT).show();
            return;
        }

        index = Math.max(0, Math.min(oldIndex, editorImages.size() - 1));
        galleryAnchorPosition = index;
        loadCurrent(false, 0);
        Toast.makeText(this, "Файл удалён", Toast.LENGTH_SHORT).show();
    }

'''
m = m[:pos] + delete_methods + m[pos:]

# Handle the system delete confirmation result.
old_result = '''        } else if (requestCode == REQ_WRITE && resultCode == RESULT_OK && pendingSaveAfterPermission) {\n            pendingSaveAfterPermission = false;\n            saveAndStay();\n        }'''
new_result = '''        } else if (requestCode == REQ_WRITE && resultCode == RESULT_OK && pendingSaveAfterPermission) {\n            pendingSaveAfterPermission = false;\n            saveAndStay();\n        } else if (requestCode == REQ_DELETE) {\n            Uri deletedUri = pendingDeleteUri;\n            pendingDeleteUri = null;\n            if (resultCode == RESULT_OK && deletedUri != null) finishDelete(deletedUri);\n        }'''
m = replace_once(m, old_result, new_result, "delete activity result")

# Before returning from the editor, anchor the gallery around the photo currently
# being viewed after swiping, not only the photo that was originally opened.
m = replace_once(
    m,
    "        back.setOnClickListener(v -> showGallery());\n",
    "        back.setOnClickListener(v -> {\n            galleryAnchorPosition = Math.max(0, index);\n            showGallery();\n        });\n",
    "toolbar back gallery anchor",
)

m = replace_once(
    m,
    "        if (currentScreen == SCREEN_EDITOR) showGallery();\n",
    "        if (currentScreen == SCREEN_EDITOR) {\n            galleryAnchorPosition = Math.max(0, index);\n            showGallery();\n        }\n",
    "system back gallery anchor",
)

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 15', 'versionCode = 16', 'versionCode')
g = replace_once(g, 'versionName = "0.1.14"', 'versionName = "0.1.15"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.15 delete/return/smooth paging patch applied")
