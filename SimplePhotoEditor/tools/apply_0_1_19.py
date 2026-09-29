from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
runpy.run_path(str(ROOT / "tools/apply_0_1_18.py"), run_name="__main__")

MAIN = ROOT / "app/src/main/java/kz/kairat/simplephotoeditor/MainActivity.java"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing anchor: {label}")
    return text.replace(old, new, 1)

m = MAIN.read_text(encoding="utf-8")

# Gallery selection visuals: video-style blue check badge at bottom-right,
# together with a subtle dim/blue veil on selected thumbnails.
old_get_view = r'''            @Override public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
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
                boolean selected = selectedGalleryUris.contains(uri.toString());
                image.setAlpha(selected ? 0.58f : 1f);
                image.setForeground(selected ? new android.graphics.drawable.ColorDrawable(0x5544CC77) : null);
                loadThumbnailCompat(image, uri, tile);
                return image;
            }'''
new_get_view = r'''            @Override public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
                FrameLayout cell;
                ImageView image;
                TextView check;
                if (convertView instanceof FrameLayout && ((FrameLayout) convertView).getChildCount() >= 2) {
                    cell = (FrameLayout) convertView;
                    image = (ImageView) cell.getChildAt(0);
                    check = (TextView) cell.getChildAt(1);
                } else {
                    cell = new FrameLayout(MainActivity.this);
                    cell.setLayoutParams(new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tile));

                    image = new ImageView(MainActivity.this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(0xFF202020);
                    cell.addView(image, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                    check = new TextView(MainActivity.this);
                    check.setText("✓");
                    check.setTextColor(Color.WHITE);
                    check.setTextSize(18);
                    check.setGravity(Gravity.CENTER);
                    check.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                    GradientDrawable checkBg = new GradientDrawable();
                    checkBg.setShape(GradientDrawable.OVAL);
                    checkBg.setColor(0xFF1976D2);
                    checkBg.setStroke(dp(2), Color.WHITE);
                    check.setBackground(checkBg);
                    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.RIGHT | Gravity.BOTTOM);
                    cp.setMargins(0, 0, dp(6), dp(6));
                    cell.addView(check, cp);
                }

                final Uri uri = editorImages.get(position);
                boolean selected = selectedGalleryUris.contains(uri.toString());
                image.setAlpha(selected ? 0.66f : 1f);
                image.setForeground(selected ? new android.graphics.drawable.ColorDrawable(0x332197F3) : null);
                check.setVisibility(selected ? android.view.View.VISIBLE : android.view.View.GONE);
                loadThumbnailCompat(image, uri, tile);
                return cell;
            }'''
m = replace_once(m, old_get_view, new_get_view, "video style selected thumbnail")

# Match the header wording used in the demonstrated selection UI.
m = replace_once(
    m,
    '            gallerySelectionCount.setText(count > 0 ? Integer.toString(count) : "");',
    '            gallerySelectionCount.setText(count > 0 ? "Выбрано " + count : "");',
    "selected count wording",
)

# Phone/system Back exits selection mode first instead of leaving the folder.
anchor = '''    @Override
    public void onBackPressed() {
'''
replacement = '''    @Override
    public void onBackPressed() {
        if (currentScreen == SCREEN_GALLERY && gallerySelectionMode) {
            selectedGalleryUris.clear();
            gallerySelectionMode = false;
            updateGallerySelectionUi();
            return;
        }
'''
m = replace_once(m, anchor, replacement, "back exits gallery selection")

MAIN.write_text(m, encoding="utf-8")

# Version bump.
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, 'versionCode = 19', 'versionCode = 20', 'versionCode')
g = replace_once(g, 'versionName = "0.1.18"', 'versionName = "0.1.19"', 'versionName')
GRADLE.write_text(g, encoding="utf-8")

print("Simple Photo Editor 0.1.19 video-style gallery selection patch applied")
