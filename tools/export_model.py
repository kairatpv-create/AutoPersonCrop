from pathlib import Path
import hashlib
import shutil
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[1]
LOCAL_SOURCE = ROOT / "model-source" / "yolo11n.pt"
DEST = ROOT / "app" / "src" / "main" / "assets" / "person_detector.tflite"
EXPECTED_SHA256 = "0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


if LOCAL_SOURCE.exists():
    source = LOCAL_SOURCE
    model = YOLO(str(source))
else:
    # Same official COCO YOLO11n checkpoint supplied with the original Windows tool.
    model = YOLO("yolo11n.pt")
    source = Path("yolo11n.pt").resolve()

actual = sha256(source)
if actual != EXPECTED_SHA256:
    raise SystemExit(f"Unexpected yolo11n.pt SHA256: {actual}; expected {EXPECTED_SHA256}")

print(f"Exporting verified YOLO11n ({actual[:12]}...) -> Google LiteRT 640x640 float model")
result = model.export(format="litert", imgsz=640, int8=False, half=False, nms=False)

candidates = []
if result:
    rp = Path(str(result))
    if rp.is_file() and rp.suffix == ".tflite":
        candidates.append(rp)
    elif rp.is_dir():
        candidates += sorted(rp.rglob("*.tflite"))

if not candidates:
    candidates += [p for p in sorted(Path.cwd().rglob("*.tflite")) if p.resolve() != DEST.resolve()]
if not candidates:
    raise SystemExit("Ultralytics LiteRT export completed but no .tflite model was found")


def rank(p: Path):
    n = p.name.lower()
    return ("int8" in n or "uint8" in n, "float32" not in n, len(str(p)))


src = sorted(candidates, key=rank)[0].resolve()
DEST.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(src, DEST)
print(f"Model ready: {DEST} ({DEST.stat().st_size:,} bytes) from {src}")
