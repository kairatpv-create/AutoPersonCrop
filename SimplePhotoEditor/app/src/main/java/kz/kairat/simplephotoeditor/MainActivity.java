package kz.kairat.simplephotoeditor;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 100;
    private static final int REQ_WRITE = 102;
    private static final int REQ_ALL_FILES = 103;
    private static final int MAX_FOLDER_ITEMS = 600;

    private static final int SCREEN_FOLDERS = 0;
    private static final int SCREEN_GALLERY = 1;
    private static final int SCREEN_EDITOR = 2;

    private static class Album {
        String key;
        String name;
        String relativePath;
        long bucketId;
        Uri cover;
        int count;
    }

    private final List<Album> albums = new ArrayList<>();
    private final List<Uri> editorImages = new ArrayList<>();

    private CropEditorView editor;
    private FrameLayout root;
    private Album selectedAlbum;
    private int index = -1;
    private int currentScreen = SCREEN_FOLDERS;
    private boolean pendingSaveAfterPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        ensureAccessThenShowFolders();
    }

    private void ensureAccessThenShowFolders() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQ_PERMISSION);
            return;
        }
        ensureAllFilesAccess();
    }

    private void ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this,
                    "Один раз разрешите Photo Editor доступ ко всем файлам",
                    Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_ALL_FILES);
            } catch (Exception e) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES);
            }
            return;
        }
        refreshAlbums();
        showFolders();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureAllFilesAccess();
            } else {
                Toast.makeText(this, "Нужен доступ к фотографиям", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ALL_FILES) {
            refreshAlbums();
            showFolders();
        } else if (requestCode == REQ_WRITE && resultCode == RESULT_OK && pendingSaveAfterPermission) {
            pendingSaveAfterPermission = false;
            saveAndNext();
        }
    }

    private void refreshAlbums() {
        albums.clear();
        Map<String, Album> map = new LinkedHashMap<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection;
        if (Build.VERSION.SDK_INT >= 29) {
            projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            };
        } else {
            projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            };
        }

        String order = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC, "
                + MediaStore.Images.Media._ID + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, order)) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int bucketIdCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int pathCol = Build.VERSION.SDK_INT >= 29
                    ? cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) : -1;

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long bucketId = bucketIdCol >= 0 ? cursor.getLong(bucketIdCol) : 0L;
                String bucketName = bucketNameCol >= 0 ? cursor.getString(bucketNameCol) : null;
                String path = pathCol >= 0 ? cursor.getString(pathCol) : null;
                String key = path != null && !path.isEmpty() ? "P:" + path : "B:" + bucketId;

                Album album = map.get(key);
                if (album == null) {
                    album = new Album();
                    album.key = key;
                    album.relativePath = path;
                    album.bucketId = bucketId;
                    album.name = folderName(path, bucketName);
                    album.cover = ContentUris.withAppendedId(collection, id);
                    map.put(key, album);
                }
                album.count++;
            }
        }
        albums.addAll(map.values());
    }

    private String folderName(String path, String fallback) {
        if (path != null && !path.isEmpty()) {
            String p = path;
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            int slash = p.lastIndexOf('/');
            String name = slash >= 0 ? p.substring(slash + 1) : p;
            if (!name.isEmpty()) return name;
        }
        return fallback != null && !fallback.isEmpty() ? fallback : "Фото";
    }

    private void showFolders() {
        currentScreen = SCREEN_FOLDERS;
        selectedAlbum = null;
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Выберите папку");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(12), dp(16), dp(12));
        page.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setBackgroundColor(Color.BLACK);
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cellWidth = screenWidth / 2;
        int imageHeight = Math.round(cellWidth * 0.78f);

        for (Album album : albums) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackgroundColor(0xFF111111);
            GridLayout.LayoutParams cellLp = new GridLayout.LayoutParams();
            cellLp.width = cellWidth;
            cellLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            cellLp.setMargins(dp(3), dp(3), dp(3), dp(3));
            cell.setLayoutParams(cellLp);

            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(0xFF202020);
            cell.addView(cover, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, imageHeight));

            TextView name = new TextView(this);
            name.setText(album.name + "  (" + album.count + ")");
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setPadding(dp(10), dp(8), dp(10), dp(10));
            cell.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            cell.setOnClickListener(v -> {
                selectedAlbum = album;
                loadSelectedAlbum();
                showGallery();
            });

            grid.addView(cell);
            loadThumbnail(cover, album.cover, cellWidth);
        }

        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPageInsets(page, null);
    }

    private void loadSelectedAlbum() {
        editorImages.clear();
        if (selectedAlbum == null) return;

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Images.Media._ID};
        String selection;
        String[] args;

        if (Build.VERSION.SDK_INT >= 29 && selectedAlbum.relativePath != null) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + "=?";
            args = new String[]{selectedAlbum.relativePath};
        } else {
            selection = MediaStore.Images.Media.BUCKET_ID + "=?";
            args = new String[]{Long.toString(selectedAlbum.bucketId)};
        }

        String order = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC, "
                + MediaStore.Images.Media._ID + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, selection, args, order)) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (cursor.moveToNext()) {
                editorImages.add(ContentUris.withAppendedId(collection, cursor.getLong(idCol)));
            }
        }
    }

    private void showGallery() {
        currentScreen = SCREEN_GALLERY;
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button folders = makeSmallButton("Папки");
        folders.setOnClickListener(v -> {
            refreshAlbums();
            showFolders();
        });
        header.addView(folders, new LinearLayout.LayoutParams(dp(90), dp(48)));

        TextView title = new TextView(this);
        title.setText(selectedAlbum != null ? selectedAlbum.name : "Фото");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, dp(8), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setBackgroundColor(Color.BLACK);
        grid.setPadding(dp(2), dp(2), dp(2), dp(2));

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int tile = screenWidth / 3;
        int count = Math.min(editorImages.size(), MAX_FOLDER_ITEMS);
        for (int i = 0; i < count; i++) {
            final int position = i;
            final Uri uri = editorImages.get(i);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF202020);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = tile;
            lp.height = tile;
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            image.setLayoutParams(lp);
            image.setOnClickListener(v -> {
                index = position;
                showEditor();
                loadCurrent();
            });
            grid.addView(image);
            loadThumbnail(image, uri, tile);
        }

        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPageInsets(page, null);
    }

    private void loadThumbnail(ImageView imageView, Uri uri, int size) {
        imageView.post(() -> new Thread(() -> {
            try {
                Bitmap thumb;
                if (Build.VERSION.SDK_INT >= 29) {
                    thumb = getContentResolver().loadThumbnail(uri, new Size(size, size), null);
                } else {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        thumb = BitmapFactory.decodeStream(in);
                    }
                }
                if (thumb != null) runOnUiThread(() -> imageView.setImageBitmap(thumb));
            } catch (Exception ignored) {
            }
        }).start());
    }

    private void showEditor() {
        currentScreen = SCREEN_EDITOR;
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);

        editor = new CropEditorView(this);
        editor.setBackgroundColor(Color.BLACK);
        editor.setSwipeListener(direction -> {
            if (direction < 0) showRelative(1);
            else showRelative(-1);
        });
        page.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Color.BLACK);

        Button back = makeButton("Назад");
        back.setOnClickListener(v -> showGallery());
        Button rotate = makeButton("Поворот");
        rotate.setOnClickListener(v -> editor.rotate90());
        Button done = makeButton("Готово");
        done.setOnClickListener(v -> saveAndNext());

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(back, p);
        bar.addView(rotate, p);
        bar.addView(done, p);
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPageInsets(page, bar);
    }

    private void applyPageInsets(LinearLayout page, LinearLayout bottomBar) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            page.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    bottomBar == null ? insets.getSystemWindowInsetBottom() : 0);
            if (bottomBar != null) {
                bottomBar.setPadding(dp(8), dp(6), dp(8),
                        insets.getSystemWindowInsetBottom() + dp(10));
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundColor(0xFF303030);
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    private Button makeSmallButton(String text) {
        Button b = makeButton(text);
        b.setTextSize(15);
        return b;
    }

    private void loadCurrent() {
        if (editor == null || index < 0 || index >= editorImages.size()) return;
        Uri uri = editorImages.get(index);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) editor.setBitmap(bitmap);
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRelative(int delta) {
        if (editorImages.isEmpty()) return;
        int next = index + delta;
        if (next < 0 || next >= editorImages.size()) return;
        index = next;
        loadCurrent();
    }

    private void saveAndNext() {
        if (editor == null || index < 0 || index >= editorImages.size()) return;
        Bitmap result = editor.createCroppedBitmap();
        if (result == null) return;
        Uri uri = editorImages.get(index);

        try {
            if (!writeBitmapDirect(uri, result)) writeBitmapViaResolver(uri, result);
            result.recycle();
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();

            int next = index + 1;
            if (next < editorImages.size()) {
                index = next;
                loadCurrent();
            } else {
                loadSelectedAlbum();
                showGallery();
            }
        } catch (SecurityException e) {
            result.recycle();
            requestWritePermission(uri);
        } catch (IOException e) {
            result.recycle();
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_LONG).show();
        }
    }

    private boolean writeBitmapDirect(Uri uri, Bitmap bitmap) throws IOException {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) return false;
        String path = queryDataPath(uri);
        if (path == null || path.isEmpty()) return false;

        String type = getContentResolver().getType(uri);
        Bitmap.CompressFormat format = (type != null && type.toLowerCase().contains("png"))
                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        File file = new File(path);
        try (OutputStream out = new FileOutputStream(file, false)) {
            if (!bitmap.compress(format, 96, out)) throw new IOException("Compression failed");
            out.flush();
        }
        return true;
    }

    private String queryDataPath(Uri uri) {
        String[] projection = {MediaStore.Images.Media.DATA};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                if (col >= 0) return cursor.getString(col);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void writeBitmapViaResolver(Uri uri, Bitmap bitmap) throws IOException {
        ContentResolver resolver = getContentResolver();
        String type = resolver.getType(uri);
        Bitmap.CompressFormat format = (type != null && type.toLowerCase().contains("png"))
                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        try (OutputStream out = resolver.openOutputStream(uri, "rwt")) {
            if (out == null) throw new IOException("No output stream");
            if (!bitmap.compress(format, 96, out)) throw new IOException("Compression failed");
            out.flush();
        }
    }

    private void requestWritePermission(Uri uri) {
        if (Build.VERSION.SDK_INT >= 30) {
            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(uri));
            pendingSaveAfterPermission = true;
            try {
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Toast.makeText(this, "Android не дал изменить оригинал", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == SCREEN_EDITOR) {
            showGallery();
        } else if (currentScreen == SCREEN_GALLERY) {
            refreshAlbums();
            showFolders();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
