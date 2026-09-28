from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CROP = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/CropEditorView.java"
MAIN = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/MainActivity.java"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing anchor: {label}")
    return text.replace(old, new, 1)


# --- Crop/zoom gesture behavior ---
s = CROP.read_text(encoding="utf-8")

# Do not recycle the previously displayed bitmap immediately. Save is now done in
# the background; the worker may still be compressing the previous frame while the
# user already swipes to another photo. The old bitmap becomes GC-eligible safely.
s = replace_once(
    s,
    """    public void setBitmap(Bitmap source) {\n        if (bitmap != null && bitmap != source && !bitmap.isRecycled()) bitmap.recycle();\n        bitmap = source;""",
    """    public void setBitmap(Bitmap source) {\n        bitmap = source;""",
    "setBitmap background-save safety",
)

s = replace_once(
    s,
    """            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {\n                multiTouch = true;\n                return bitmap != null;\n            }""",
    """            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {\n                multiTouch = true;\n                if (editMode) mode = MODE_IMAGE;\n                return bitmap != null;\n            }""",
    "scale begin",
)

s = replace_once(
    s,
    """                float fx = detector.getFocusX();\n                float fy = detector.getFocusY();\n                tx = fx - (fx - tx) * factor;\n                ty = fy - (fy - ty) * factor;""",
    """                float fx = detector.getFocusX();\n                float fy = detector.getFocusY();\n                if (editMode) {\n                    fx = clamp(fx, crop.left, crop.right);\n                    fy = clamp(fy, crop.top, crop.bottom);\n                }\n                tx = fx - (fx - tx) * factor;\n                ty = fy - (fy - ty) * factor;""",
    "zoom focus inside crop",
)

s = replace_once(
    s,
    """            case MotionEvent.ACTION_POINTER_DOWN:\n                multiTouch = true;\n                return true;\n\n            case MotionEvent.ACTION_MOVE:\n                if (scaleDetector.isInProgress()) return true;""",
    """            case MotionEvent.ACTION_POINTER_DOWN:\n                multiTouch = true;\n                mode = MODE_IMAGE;\n                return true;\n\n            case MotionEvent.ACTION_POINTER_UP:\n                multiTouch = true;\n                int lifted = event.getActionIndex();\n                int remaining = lifted == 0 ? 1 : 0;\n                if (remaining < event.getPointerCount()) {\n                    lastX = event.getX(remaining);\n                    lastY = event.getY(remaining);\n                }\n                mode = MODE_IMAGE;\n                return true;\n\n            case MotionEvent.ACTION_MOVE:\n                if (scaleDetector.isInProgress() || event.getPointerCount() > 1) return true;""",
    "edit pointer transition after pinch",
)

CROP.write_text(s, encoding="utf-8")

# --- UI responsiveness after save/swipe ---
m = MAIN.read_text(encoding="utf-8")

m = replace_once(
    m,
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(4);\n\n    private CropEditorView editor;""",
    """    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(4);\n    private final ExecutorService photoIoPool = Executors.newFixedThreadPool(2);\n    private int loadGeneration = 0;\n\n    private CropEditorView editor;""",
    "photo IO pool",
)

old_load = """    private void loadCurrent() {\n        if (editor == null || index < 0 || index >= editorImages.size()) return;\n        Uri uri = editorImages.get(index);\n        try (InputStream in = getContentResolver().openInputStream(uri)) {\n            if (in == null) return;\n            Bitmap bitmap = BitmapFactory.decodeStream(in);\n            if (bitmap != null) editor.setBitmap(bitmap);\n        } catch (IOException e) {\n            Toast.makeText(this, \"Не удалось открыть фото\", Toast.LENGTH_SHORT).show();\n        }\n    }"""
new_load = """    private void loadCurrent() {\n        if (editor == null || index < 0 || index >= editorImages.size()) return;\n        final Uri uri = editorImages.get(index);\n        final int expectedIndex = index;\n        final int generation = ++loadGeneration;\n        photoIoPool.execute(() -> {\n            Bitmap bitmap = null;\n            try (InputStream in = getContentResolver().openInputStream(uri)) {\n                if (in != null) bitmap = BitmapFactory.decodeStream(in);\n            } catch (IOException ignored) { }\n            final Bitmap ready = bitmap;\n            runOnUiThread(() -> {\n                if (generation != loadGeneration || expectedIndex != index || currentScreen != SCREEN_EDITOR || editor == null) {\n                    if (ready != null && !ready.isRecycled()) ready.recycle();\n                    return;\n                }\n                if (ready != null) editor.setBitmap(ready);\n                else Toast.makeText(this, \"Не удалось открыть фото\", Toast.LENGTH_SHORT).show();\n            });\n        });\n    }"""
m = replace_once(m, old_load, new_load, "async full photo loading")

old_save = """    private void saveAndStay() {\n        if (editor == null || index < 0 || index >= editorImages.size()) return;\n        Bitmap result = editor.createCroppedBitmap();\n        if (result == null) return;\n        Uri uri = editorImages.get(index);\n        try {\n            if (!writeBitmapDirect(uri, result)) writeBitmapViaResolver(uri, result);\n            editor.setBitmap(result);\n            Toast.makeText(this, \"Сохранено\", Toast.LENGTH_SHORT).show();\n        } catch (SecurityException e) {\n            result.recycle();\n            requestWritePermission(uri);\n        } catch (IOException e) {\n            result.recycle();\n            Toast.makeText(this, \"Ошибка сохранения\", Toast.LENGTH_LONG).show();\n        }\n    }"""
new_save = """    private void saveAndStay() {\n        if (editor == null || index < 0 || index >= editorImages.size()) return;\n        final Bitmap result = editor.createCroppedBitmap();\n        if (result == null) return;\n        final Uri uri = editorImages.get(index);\n\n        // Show the exact crop result immediately. Editing switches off at once, so\n        // the user can inspect it and swipe without waiting for JPEG compression.\n        editor.setBitmap(result);\n\n        photoIoPool.execute(() -> {\n            try {\n                if (!writeBitmapDirect(uri, result)) writeBitmapViaResolver(uri, result);\n                runOnUiThread(() -> Toast.makeText(this, \"Сохранено\", Toast.LENGTH_SHORT).show());\n            } catch (SecurityException e) {\n                runOnUiThread(() -> requestWritePermission(uri));\n            } catch (IOException e) {\n                runOnUiThread(() -> Toast.makeText(this, \"Ошибка сохранения\", Toast.LENGTH_LONG).show());\n            }\n        });\n    }"""
m = replace_once(m, old_save, new_save, "background save and instant preview")

MAIN.write_text(m, encoding="utf-8")

# --- Version ---
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 10', 'versionCode = 11', 'versionCode')
g = replace_once(g, 'versionName = "0.1.9"', 'versionName = "0.1.10"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.10 patch applied")
