from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_15.py"), run_name="__main__")

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

# Direct file deletion needs MediaScanner cleanup for the gallery database.
m = replace_once(
    m,
    "import android.provider.MediaStore;\n",
    "import android.provider.MediaStore;\nimport android.media.MediaScannerConnection;\n",
    "media scanner import",
)

# Keep the actual gallery view alive while the editor is open. Re-adding the same
# GridView on Back preserves the exact scroll offset, even hundreds of photos down.
m = replace_once(
    m,
    "    private CropEditorView editor;\n",
    "    private CropEditorView editor;\n    private LinearLayout cachedGalleryPage = null;\n    private GridView cachedGalleryGrid = null;\n    private Album cachedGalleryAlbum = null;\n",
    "cached gallery fields",
)

# When a different folder is chosen the old cached grid must be discarded.
m = replace_once(
    m,
    "                selectedAlbum = album;\n                galleryFirstVisible = 0;\n",
    "                selectedAlbum = album;\n                cachedGalleryPage = null;\n                cachedGalleryGrid = null;\n                cachedGalleryAlbum = null;\n                galleryFirstVisible = 0;\n",
    "clear gallery cache on folder switch",
)

# Replace showGallery with a persistent-view implementation.
gs, ge = function_bounds(m, "    private void showGallery()")
new_gallery = r'''    private void showGallery() {
        currentScreen = SCREEN_GALLERY;
        root.removeAllViews();

        if (cachedGalleryPage != null && cachedGalleryGrid != null && cachedGalleryAlbum == selectedAlbum) {
            android.view.ViewParent parent = cachedGalleryPage.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(cachedGalleryPage);
            root.addView(cachedGalleryPage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.requestApplyInsets();
            return;
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(5), dp(10), dp(5));
        ImageButton folders = makeIconButton(R.drawable.ic_folder, "Выбрать папку", 0xFF273449, dp(11));
        folders.setOnClickListener(v -> {
            cachedGalleryPage = null;
            cachedGalleryGrid = null;
            cachedGalleryAlbum = null;
            refreshAlbums();
            showFolders();
        });
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
            @Override public int getCount() { return Math.min(editorImages.size(), MAX_FOLDER_ITEMS); }
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
            galleryAnchorPosition = position;
            galleryAnchorTop = view != null ? view.getTop() : 0;
            index = position;
            showEditor();
            loadCurrent();
        });

        page.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        cachedGalleryPage = page;
        cachedGalleryGrid = grid;
        cachedGalleryAlbum = selectedAlbum;

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPageInsets(page, null);

        // Only a freshly created gallery needs restoration. Once cached, Android keeps
        // the real GridView scroll position exactly, including pixel offset.
        grid.post(() -> grid.setSelectionFromTop(Math.max(0, galleryFirstVisible), galleryTop));
    }'''
m = m[:gs] + new_gallery + m[ge:]

# Direct delete: no app dialog and no Android MediaStore createDeleteRequest.
# MANAGE_EXTERNAL_STORAGE access is already requested by this app. We delete the
# underlying file first, then clean/refresh MediaStore. If no filesystem path is
# available, try resolver.delete directly; on a denied file we report failure rather
# than showing a confirmation dialog.
rs, re_ = function_bounds(m, "    private void requestDeleteCurrent()")
new_request_delete = r'''    private void requestDeleteCurrent() {
        if (index < 0 || index >= editorImages.size()) return;
        final Uri uri = editorImages.get(index);
        final String path = queryDataPath(uri);
        saveIoPool.execute(() -> {
            boolean deleted = false;
            try {
                if (path != null && !path.isEmpty()) {
                    File file = new File(path);
                    deleted = !file.exists() || file.delete();
                    if (deleted) {
                        try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                        MediaScannerConnection.scanFile(MainActivity.this,
                                new String[]{path}, null, null);
                    }
                }
                if (!deleted) {
                    try {
                        deleted = getContentResolver().delete(uri, null, null) > 0;
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            final boolean ok = deleted;
            runOnUiThread(() -> {
                if (ok) finishDelete(uri);
                else Toast.makeText(MainActivity.this,
                        "Android не разрешил удалить этот файл напрямую",
                        Toast.LENGTH_LONG).show();
            });
        });
    }'''
m = m[:rs] + new_request_delete + m[re_:]

# After deletion, keep the same gallery instance but notify its adapter so the
# deleted cell disappears without losing scroll position.
fs, fe = function_bounds(m, "    private void finishDelete(Uri uri)")
old_finish = m[fs:fe]
old_finish = old_finish.replace(
    "        pendingDeleteUri = null;\n",
    "        pendingDeleteUri = null;\n        if (cachedGalleryGrid != null && cachedGalleryGrid.getAdapter() instanceof BaseAdapter) {\n            ((BaseAdapter) cachedGalleryGrid.getAdapter()).notifyDataSetChanged();\n        }\n",
    1,
)
# If folder becomes empty, discard stale cached grid before recreating.
old_finish = old_finish.replace(
    "            refreshAlbums();\n            showGallery();",
    "            cachedGalleryPage = null;\n            cachedGalleryGrid = null;\n            cachedGalleryAlbum = null;\n            refreshAlbums();\n            showGallery();",
    1,
)
m = m[:fs] + old_finish + m[fe:]

# REQ_DELETE flow is no longer used; leave the constant harmlessly present for
# upgrade compatibility but remove the system-result branch so no confirmation is
# ever expected.
old_result = '''        } else if (requestCode == REQ_DELETE) {\n            Uri deletedUri = pendingDeleteUri;\n            pendingDeleteUri = null;\n            if (resultCode == RESULT_OK && deletedUri != null) finishDelete(deletedUri);\n        }'''
m = replace_once(m, old_result, "        }", "remove delete confirmation result")

# IMPORTANT: returning from editor must simply reattach cached gallery. Do not alter
# the GridView selection based on the current editor index; the scroll position is
# already preserved exactly where the user left it.
m = replace_once(
    m,
    "        back.setOnClickListener(v -> {\n            galleryAnchorPosition = Math.max(0, index);\n            showGallery();\n        });\n",
    "        back.setOnClickListener(v -> showGallery());\n",
    "toolbar back persistent gallery",
)
m = replace_once(
    m,
    "        if (currentScreen == SCREEN_EDITOR) {\n            galleryAnchorPosition = Math.max(0, index);\n            showGallery();\n        }\n",
    "        if (currentScreen == SCREEN_EDITOR) showGallery();\n",
    "system back persistent gallery",
)

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 16', 'versionCode = 17', 'versionCode')
g = replace_once(g, 'versionName = "0.1.15"', 'versionName = "0.1.16"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.16 persistent gallery/direct delete patch applied")
