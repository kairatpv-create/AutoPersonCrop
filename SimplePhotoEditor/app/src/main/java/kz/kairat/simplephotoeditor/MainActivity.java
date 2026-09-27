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
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 100;
    private static final int REQ_WRITE = 102;
    private static final int REQ_ALL_FILES = 103;
    private static final int MAX_GALLERY_ITEMS = 300;

    private final List<Uri> images = new ArrayList<>();
    private CropEditorView editor;
    private FrameLayout root;
    private int index = -1;
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
        ensureAccessThenShowGallery();
    }

    private void ensureAccessThenShowGallery() {
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
                    "Один раз разрешите Photo Editor доступ ко всем файлам — после этого сохранение будет без вопросов",
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
        refreshImageList();
        showGallery();
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
            if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                refreshImageList();
                showGallery();
            } else {
                Toast.makeText(this, "Без этого доступа Android будет спрашивать разрешение при каждом сохранении", Toast.LENGTH_LONG).show();
                refreshImageList();
                showGallery();
            }
        } else if (requestCode == REQ_WRITE && resultCode == RESULT_OK && pendingSaveAfterPermission) {
            pendingSaveAfterPermission = false;
            saveAndNext();
        }
    }

    private void refreshImageList() {
        images.clear();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Images.Media._ID};
        String order = MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, order)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                images.add(ContentUris.withAppendedId(collection, id));
            }
        }
    }

    private void showGallery() {
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.BLACK);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setBackgroundColor(Color.BLACK);
        grid.setPadding(dp(2), dp(2), dp(2), dp(2));

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int tile = screenWidth / 3;
        int count = Math.min(images.size(), MAX_GALLERY_ITEMS);
        for (int i = 0; i < count; i++) {
            final int position = i;
            final Uri uri = images.get(i);
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

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            page.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    private void loadThumbnail(ImageView imageView, Uri uri, int tile) {
        imageView.post(() -> new Thread(() -> {
            try {
                Bitmap thumb;
                if (Build.VERSION.SDK_INT >= 29) {
                    thumb = getContentResolver().loadThumbnail(uri, new Size(tile, tile), null);
                } else {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        thumb = BitmapFactory.decodeStream(in);
                    }
                }
                if (thumb != null) {
                    runOnUiThread(() -> imageView.setImageBitmap(thumb));
                }
            } catch (Exception ignored) {
            }
        }).start());
    }

    private void showEditor() {
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
        bar.setPadding(dp(8), dp(6), dp(8), dp(10));

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

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            page.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            bar.setPadding(dp(8), dp(6), dp(8), insets.getSystemWindowInsetBottom() + dp(10));
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

    private void loadCurrent() {
        if (editor == null || index < 0 || index >= images.size()) return;
        Uri uri = images.get(index);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) editor.setBitmap(bitmap);
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRelative(int delta) {
        if (images.isEmpty()) return;
        int next = index + delta;
        if (next < 0 || next >= images.size()) return;
        index = next;
        loadCurrent();
    }

    private void saveAndNext() {
        if (editor == null || index < 0 || index >= images.size()) return;
        Bitmap result = editor.createCroppedBitmap();
        if (result == null) return;
        Uri uri = images.get(index);

        try {
            if (!writeBitmapDirect(uri, result)) {
                writeBitmapViaResolver(uri, result);
            }
            result.recycle();
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
            int next = index + 1;
            if (next < images.size()) {
                index = next;
                loadCurrent();
            } else {
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
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
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
        } else {
            Toast.makeText(this, "Android не дал изменить оригинал", Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
