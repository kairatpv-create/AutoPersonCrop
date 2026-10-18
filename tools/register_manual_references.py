#!/usr/bin/env python3
"""Register original/manual JPEG pairs by content; never used by the Android app.

Development dependencies: numpy, opencv-python-headless. Original .jpeg and
manual .jpg members are the archive convention, not production crop heuristics.
Recompression uses normalized template matching; rescaled references fall back
to local features and a RANSAC similarity transform.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile

import cv2
import numpy as np


def feature_match(original, manual):
    sift = cv2.SIFT_create(nfeatures=2500)
    km, dm = sift.detectAndCompute(manual, None)
    ko, do = sift.detectAndCompute(original, None)
    if dm is None or do is None or len(dm) < 8 or len(do) < 8:
        return None
    nearest = cv2.BFMatcher().knnMatch(dm, do, k=2)
    matches = [pair[0] for pair in nearest if len(pair) == 2 and pair[0].distance < pair[1].distance * 0.72]
    if len(matches) < 8:
        return None
    p = np.float32([km[m.queryIdx].pt for m in matches])
    q = np.float32([ko[m.trainIdx].pt for m in matches])
    matrix, inliers = cv2.estimateAffinePartial2D(p, q, method=cv2.RANSAC, ransacReprojThreshold=2.0)
    if matrix is None or inliers.sum() < 8:
        return None
    # A manual crop is a translated/scaled subimage, not an arbitrary panorama warp.
    angle = abs(np.degrees(np.arctan2(matrix[1, 0], matrix[0, 0])))
    if angle > 0.5:
        return None
    h, w = manual.shape
    points = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    mapped = points @ matrix[:, :2].T + matrix[:, 2]
    rect = [mapped[:, 0].min(), mapped[:, 1].min(), mapped[:, 0].max(), mapped[:, 1].max()]
    ih, iw = original.shape
    if rect[0] < -3 or rect[1] < -3 or rect[2] > iw + 3 or rect[3] > ih + 3:
        return None
    return dict(rect=[float(v) for v in rect], inliers=int(inliers.sum()),
                feature_fraction=float(inliers.mean()), method='SIFT/RANSAC')


def register(root):
    originals = sorted(root.rglob('*.jpeg')) + sorted(root.rglob('*.JPEG'))
    manuals = sorted(root.rglob('*.jpg')) + sorted(root.rglob('*.JPG'))
    if not originals or not manuals:
        raise ValueError('Expected original .jpeg and manual .jpg images in the archive')
    loaded = {p: cv2.imdecode(np.frombuffer(p.read_bytes(), dtype=np.uint8), cv2.IMREAD_GRAYSCALE) for p in originals}
    result = []
    for path in manuals:
        manual = cv2.imdecode(np.frombuffer(path.read_bytes(), dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
        h, w = manual.shape
        candidates = []
        for original, image in loaded.items():
            if image.shape[0] < h or image.shape[1] < w:
                continue
            match = cv2.matchTemplate(image, manual, cv2.TM_CCOEFF_NORMED)
            _, score, _, (left, top) = cv2.minMaxLoc(match)
            candidates.append((score, original, [left, top, left + w, top + h]))
        best = max(candidates, key=lambda x: x[0]) if candidates else None
        if best is not None and best[0] >= 0.97:
            score, original, rect = best
            details = dict(method='normalized template correlation', correlation=float(score))
        else:
            features = []
            for original, image in loaded.items():
                match = feature_match(image, manual)
                if match is not None:
                    features.append((match['inliers'] * match['feature_fraction'], original, match))
            if not features:
                raise ValueError(f'No reliable original/manual registration for {path.name}')
            _, original, details = max(features, key=lambda x: x[0])
            rect = details.pop('rect')
        ih, iw = loaded[original].shape
        left, top, right, bottom = rect
        result.append(dict(original=str(original.relative_to(root)), manual=str(path.relative_to(root)),
            source_sha256=hashlib.sha256(original.read_bytes()).hexdigest(),
            manual_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            image=[iw, ih], crop=rect, crop_width=right-left, crop_height=bottom-top,
            aspect=(right-left)/(bottom-top),
            removed_percent=[left/iw*100, top/ih*100, (iw-right)/iw*100, (ih-bottom)/ih*100], **details))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        with zipfile.ZipFile(args.archive) as archive:
            for member in archive.infolist():
                target = (root / member.filename).resolve()
                if not target.is_relative_to(root.resolve()):
                    raise ValueError('Archive member escapes its extraction directory')
            archive.extractall(root)
        rows = register(root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
    print(f'Registered {len(rows)} original/manual pairs')


if __name__ == '__main__':
    main()
