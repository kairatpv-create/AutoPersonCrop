#!/usr/bin/env python3
"""Expose small synthetic QA screenshots in CI logs when the local workspace is unavailable."""
from pathlib import Path
import base64, json, subprocess, sys

folder = Path(sys.argv[1]) / "named"
index = folder / "index.json"
if not index.exists():
    raise SystemExit(0)
rows = json.loads(index.read_text())
wanted = ["ru-light-normal-notes", "ru-dark-large-notes", "en-dark-large-settings", "ru-dark-large-money", "en-empty-backup-preview", "en-empty-backup-confirmation", "ru-empty-backup-restored"]
for label in wanted:
    row = next((r for r in rows if r["name"].startswith(label + "_")), None)
    if row is None:
        continue
    source = folder / row["file"]
    dest = folder / ("review-" + label + ".png")
    subprocess.run(["sips", "-Z", "750", str(source), "--out", str(dest)], check=True, stdout=subprocess.DEVNULL)
    print("ORGANIZER_QA_IMAGE " + json.dumps({"name": folder.parent.name + "/" + label, "data": base64.b64encode(dest.read_bytes()).decode()}), flush=True)
