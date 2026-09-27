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
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 100;
    private static final int REQ_PICK = 101;
    private static final int REQ_WRITE = 102;

    private final List<Uri> images = new ArrayList<>();
    private CropEditorView editor;
    private LinearLayout bottomBar;
    private int index = -1;
    private boolean pendingSaveAfterPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
        buildUi();
        ensurePermissionAndOpenGallery();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.BLACK);

        editor = new CropEditorView(this);
        editor.setBackgroundColor(Color.BLACK);
        editor.setSwipeListener(direction -> {
            if (direction < 0) showRelative(1);
            else showRelative(-1);
        });
        container.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(Color.BLACK);
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(12));

        Button back = makeButton("Назад");
        back.setOnClickListener(v -> openGallery());

        Button rotate = makeButton("Поворот");
        rotate.setOnClickListener(v -> editor.rotate90());

        Button done = makeButton("Готово");
        done.setOnClickListener(v -> saveAndNext());

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
        p.setMargins(dp(6), 0, dp(6), 0);
        bottomBar.addView(back, p);
        bottomBar.addView(rotate, p);
        bottomBar.addView(done, p);

        container.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            container.setPadding(0, top, 0, 0);
            bottomBar.setPadding(dp(12), dp(8), dp(12), bottom + dp(12));
            return insets;
        });
        root.requestApplyInsets();

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF2E2E2E);
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    private void ensurePermissionAndOpenGallery() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            refreshImageList();
            openGallery();
        } else {
            requestPermissions(new String[]{permission}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshImageList();
                openGallery();
            } else {
                Toast.makeText(this, "Нужен доступ к фотографиям", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri selected = data.getData();
                refreshImageList();
                index = findIndex(selected);
                if (index < 0) {
                    images.add(0, selected);
                    index = 0;
                }
                loadCurrent();
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

    private int findIndex(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (last.equals(images.get(i).getLastPathSegment())) return i;
        }
        return -1;
    }

    private void loadCurrent() {
        if (index < 0 || index >= images.size()) return;
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
        if (next < 0 || next >= images.size()) {
            Toast.makeText(this, "Больше фото нет", Toast.LENGTH_SHORT).show();
            return;
        }
        index = next;
        loadCurrent();
    }

    private void saveAndNext() {
        if (index < 0 || index >= images.size()) return;
        Bitmap result = editor.createCroppedBitmap();
        if (result == null) return;

        Uri uri = images.get(index);
        try {
            writeBitmap(uri, result);
            result.recycle();
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
            showRelative(1);
        } catch (SecurityException se) {
            result.recycle();
            requestWritePermission(uri);
        } catch (IOException e) {
            result.recycle();
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_LONG).show();
        }
    }

    private void writeBitmap(Uri uri, Bitmap bitmap) throws IOException {
        ContentResolver resolver = getContentResolver();
        String type = resolver.getType(uri);
        Bitmap.CompressFormat format = (type != null && type.toLowerCase().contains("png"))
                ? Bitmap.CompressFormat.PNG
                : Bitmap.CompressFormat.JPEG;
        try (OutputStream out = resolver.openOutputStream(uri, "rwt")) {
            if (out == null) throw new IOException("No output stream");
            if (!bitmap.compress(format, 96, out)) {
                throw new IOException("Compression failed");
            }
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
