# Build notes

This branch is built automatically by `.github/workflows/build-apk.yml`.

The build verifies the original YOLO11n checkpoint SHA256, exports it to a 640x640 Google LiteRT `.tflite` asset, compiles the Android arm64 app with NDK 28.2, and uploads the debug APK as a workflow artifact.
