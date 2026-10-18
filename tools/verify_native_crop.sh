#!/usr/bin/env bash
set -euo pipefail
TASK_NATIVE_DIR="${RUNNER_TEMP:-/tmp}/autocrop-native-regression"
mkdir -p "$TASK_NATIVE_DIR/source" "$TASK_NATIVE_DIR/classes"
python3 - "$TASK_NATIVE_DIR" <<'PY'
import hashlib, pathlib, re, sys, tarfile, urllib.request
root = pathlib.Path(sys.argv[1])
config = pathlib.Path('app/src/main/cpp/CMakeLists.txt').read_text()
url = re.search(r'^\s*URL (\S+)', config, re.M).group(1)
sha = re.search(r'URL_HASH SHA256=(\w+)', config).group(1)
archive = root / 'source.tar.gz'
if not archive.exists(): urllib.request.urlretrieve(url, archive)
assert hashlib.sha256(archive.read_bytes()).hexdigest() == sha, 'libjpeg-turbo source checksum mismatch'
with tarfile.open(archive) as src:
    src.extractall(root / 'unpacked', filter='data')
source = next((root / 'unpacked').iterdir())
(root / 'source-dir.txt').write_text(str(source))
PY
TASK_JPEG_SOURCE="$(cat "$TASK_NATIVE_DIR/source-dir.txt")"
cmake -S "$TASK_JPEG_SOURCE" -B "$TASK_NATIVE_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DENABLE_SHARED=ON -DENABLE_STATIC=OFF \
  -DWITH_SIMD=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF -DWITH_JAVA=OFF
cmake --build "$TASK_NATIVE_DIR/build" --target turbojpeg -j 2
c++ -std=c++17 -shared -fPIC \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$TASK_JPEG_SOURCE/src" \
  app/src/main/cpp/native_crop.cpp -L"$TASK_NATIVE_DIR/build" -lturbojpeg \
  -Wl,-rpath,"$TASK_NATIVE_DIR/build" -o "$TASK_NATIVE_DIR/libautocropjpeg.so"
javac -d "$TASK_NATIVE_DIR/classes" tools/native-test/kz/autopersoncrop/jpeg/LosslessJpegTransformer.java
java -Djava.awt.headless=true -cp "$TASK_NATIVE_DIR/classes" \
  kz.autopersoncrop.jpeg.LosslessJpegTransformer \
  "$TASK_NATIVE_DIR/libautocropjpeg.so" app/src/test/resources/manual/video_frames.tsv
