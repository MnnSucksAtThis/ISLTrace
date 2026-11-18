#!/usr/bin/env python3
"""
Hand OUTLINE guide videos: black background, hollow red hand-shaped outline
(like a drawing) that a person can place their own hand inside.

Tracks up to 2 hands (--num-hands). Uses the same MediaPipe hand_landmarker.task as the GTk-Android app to track the
hand, builds a hand-shaped silhouette from the 21 landmarks, and draws only the
outer contour in red.

Usage:
  source ~/skel-env/bin/activate
  python3 make_outline_videos.py /path/to/20_medoids [-o OUT] [--fill] [--thickness 4]
"""
import argparse, sys
from pathlib import Path
import cv2
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

RED = (0, 0, 255)  # BGR
FINGERS = {            # chain, relative width (fraction of palm width)
    "thumb":  ([1, 2, 3, 4],     0.30),
    "index":  ([5, 6, 7, 8],     0.25),
    "middle": ([9, 10, 11, 12],  0.25),
    "ring":   ([13, 14, 15, 16], 0.24),
    "pinky":  ([17, 18, 19, 20], 0.21),
}
TAPER = [1.0, 0.92, 0.82]  # width multiplier per segment (base -> tip)


def hand_mask(lms, w, h, grow=1.0):
    """Filled hand silhouette (uint8 mask) built from the 21 landmarks."""
    pts = np.array([(l.x * w, l.y * h) for l in lms], dtype=np.float32)
    palm_w = float(np.linalg.norm(pts[5] - pts[17])) + 1e-6
    mask = np.zeros((h, w), np.uint8)

    # palm (+ a bit of wrist)
    palm = np.array([pts[0], pts[1], pts[2], pts[5], pts[9], pts[13], pts[17]], np.int32)
    cv2.fillPoly(mask, [palm], 255)
    cv2.circle(mask, tuple(pts[0].astype(int)), int(0.28 * palm_w * grow), 255, -1, cv2.LINE_AA)
    # fill knuckle gaps between fingers' bases so the palm top is smooth
    for a, b in [(5, 9), (9, 13), (13, 17)]:
        cv2.line(mask, tuple(pts[a].astype(int)), tuple(pts[b].astype(int)),
                 255, int(0.30 * palm_w * grow), cv2.LINE_AA)

    # fingers: tapered thick segments with round joints / tips
    for chain, rel in FINGERS.values():
        for i in range(3):
            a, b = pts[chain[i]], pts[chain[i + 1]]
            th = max(2, int(rel * palm_w * TAPER[i] * grow))
            cv2.line(mask, tuple(a.astype(int)), tuple(b.astype(int)), 255, th, cv2.LINE_AA)
            cv2.circle(mask, tuple(a.astype(int)), th // 2, 255, -1, cv2.LINE_AA)
        tip_r = max(2, int(rel * palm_w * TAPER[2] * grow) // 2)
        cv2.circle(mask, tuple(pts[chain[3]].astype(int)), tip_r, 255, -1, cv2.LINE_AA)

    # smooth the shape so it looks like a hand drawing
    k = max(3, int(0.06 * palm_w) | 1)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    mask = cv2.GaussianBlur(mask, (k, k), 0)
    _, mask = cv2.threshold(mask, 127, 255, cv2.THRESH_BINARY)
    return mask


def draw_outline(frame_shape, hands, thickness, fill):
    """hands: list of landmark lists (one per detected hand)."""
    h, w = frame_shape[:2]
    canvas = np.zeros((h, w, 3), np.uint8)  # black
    if not hands:
        return canvas
    mask = np.zeros((h, w), np.uint8)
    for lms in hands:
        mask = cv2.bitwise_or(mask, hand_mask(lms, w, h))
    if fill:
        canvas[mask > 0] = RED
        return canvas
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cv2.drawContours(canvas, contours, -1, RED, thickness, cv2.LINE_AA)  # hollow
    return canvas


def make_landmarker(model_path, num_hands):
    return vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model_path)),
        running_mode=vision.RunningMode.VIDEO,
        num_hands=num_hands,
        min_hand_detection_confidence=0.5,
        min_tracking_confidence=0.5,
        min_hand_presence_confidence=0.5))


def process(video_path, out_path, model_path, thickness, fill, num_hands):
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        print(f"  ! cannot open {video_path}"); return
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)); h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    th = thickness or max(3, w // 160)
    wr = cv2.VideoWriter(str(out_path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
    lm = make_landmarker(model_path, num_hands)
    i = det = 0
    while True:
        ok, frame = cap.read()
        if not ok: break
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        res = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), int(i * 1000.0 / fps))
        hands = list(res.hand_landmarks) if res.hand_landmarks else []
        det += bool(hands)
        wr.write(draw_outline(frame.shape, hands, th, fill))
        i += 1
    cap.release(); lm.close(); wr.release()
    print(f"  {video_path.name}: {i} frames, hand detected in {det}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path)
    ap.add_argument("-o", "--output", type=Path, default=None)
    ap.add_argument("--model", type=Path, default=Path(__file__).with_name("hand_landmarker.task"))
    ap.add_argument("--thickness", type=int, default=0, help="outline thickness in px (default: auto)")
    ap.add_argument("--num-hands", type=int, default=2, help="max hands to track (default 2)")
    ap.add_argument("--fill", action="store_true", help="filled red silhouette instead of hollow outline")
    a = ap.parse_args()
    if not a.model.exists():
        sys.exit(f"Model not found: {a.model}")
    out_root = a.output or a.root.with_name(a.root.name + "_outline")
    vids = sorted(a.root.rglob("*.mp4"))
    print(f"Found {len(vids)} videos -> {out_root}")
    for v in vids:
        od = out_root / v.parent.relative_to(a.root); od.mkdir(parents=True, exist_ok=True)
        print(f"[{v.parent.name}]")
        process(v, od / f"{v.stem}__outline.mp4", a.model, a.thickness, a.fill, a.num_hands)
    print("Done.")


if __name__ == "__main__":
    main()
