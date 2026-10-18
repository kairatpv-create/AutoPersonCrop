# AutoPersonCrop 0.7.20 / 56 — manual composition calibration

Base: `android-dev`, `5f34f2ccce06051f9a90df7d4f23a84e64b3b5e7` (0.7.19 / 55).
The October 18 clarification excludes the older archives with spectators: input
contains one or two wanted people. `main` is outside this change.

## References and measurements

`VideoFrames.zip`: 29 original 1280×720 JPEG frames and 29 recompressed manual JPG
crops. All pairs were matched by image content, not file-name correspondence.
Normalized registration correlation: 0.99969–0.99993. No original was modified.

`video_frames_composition.csv` contains all source/manual associations, exact
left/top/right/bottom, pixel sizes, aspect, removed source margins, reviewed
people envelopes, people count, per-side empty space as a percentage of the
final crop, relative people centers, and old/new planner results.

Median manual headroom is 3.80%; footroom 2.36%; side space 5.80% / 6.25%.
Source-clipped heads or feet necessarily have less space. Single-figure balance
and a chair/support can explain some larger lateral space; there is no universal
fixed output aspect. Wide mixed standing/lying poses keep the whole group.

## Result and scope of validation

The same shipped YOLO TFLite asset was run on the 17 existing input views with
CPU LiteRT. The actual Kotlin multi-view aggregation, selector and planner were
replayed on those detections without optional neighbor motion focus. This is host regression analysis; Android Canvas,
GPU inference, SAF and real-device processing are not reproduced by it.

On this replay, 0.7.19 matched 14/29 manual crops within 3% of each source dimension.
The updated planned rectangles match 29/29: no per-file exceptions, side tolerance
3%, area tolerance 16%, relative aspect tolerance 14%. The observed maximum area
error is 13.52%; maximum aspect error 12.29%. Reviewed main people remain contained.

The JVM suite stores the measured manual geometry and captured detector-envelope
fixtures for all 29 pairs. It also checks mirrored scenes, people retention,
encoded-origin block allowance, duplicate observations, stationary partners,
reflection suppression and temporal behavior. Fixtures are test resources and
are not packaged in the APK. No reference image name or coordinate is used in
production crop decisions.

## Composition changes

- Final photographic margins are applied once: about 4% on the posture's main
  axis and 6% of the group extent on its other axis, consistent with this set.
- Unavailable source-edge padding is not transferred to the opposite side.
- Slender portrait balance is bounded by 1.5× actual detected width, with a
  smaller soft shape hint; wide groups do not inherit a portrait width floor.
- Landscape height expansion is capped at 1.25× actual body-group height.
  The side with less free source space anchors the frame; the opposite side is
  adjusted while keeping every selected person.
- Nested cross-view observations do not count as independent people. A real
  partner contributes new body coverage; partial extremities can complete an
  already selected envelope. Controlled input keeps a stationary second person
  even when only the other person supplies temporal motion.
- A recovery envelope already mostly explained by a whole-frame detection may
  not stretch far into a floor reflection. Substantially new lying-body evidence
  remains eligible. Detector padding is reduced so margins do not accumulate.

## Lossless JPEG

The native transform still copies JPEG coefficients with `TJXOP_NONE`. Only its
origin needs iMCU alignment. Right/bottom now retain the requested coordinates;
unnecessary rounding there previously added another strip of background.
Left/top align outward by at most one iMCU minus one pixel (15 px for these 4:2:0
sources). Only those two sides receive this explicit encoding allowance in the
JPEG geometry regression; planned-frame tolerance remains 3% on every side.

`tools/verify_native_crop.sh` builds the same pinned libjpeg-turbo source and the
actual JNI file on the CI host. It tests all 29 reference rectangle sizes plus
edge cases under 4:2:0, 4:2:2 and 4:4:4 sampling, exact far edges, unchanged source
bytes, identical decoded interior pixels and rejection of invalid rectangles.

## Repeat registration

Install development-only `numpy` and `opencv-python-headless`, then run:

```sh
python3 tools/register_manual_references.py /path/to/VideoFrames.zip /tmp/registered.json
```

JPEG recompression does not require binary equality. Resized manual references
fall back to local features and RANSAC. Registration is a development tool only.
The application remains offline, without INTERNET permission. Its existing
SAF/CROP traversal, original file names, ОБРАЗЕЦ exclusion, EXIF mapping and stable
debug signing key are retained.
