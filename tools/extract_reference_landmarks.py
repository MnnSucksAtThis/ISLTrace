#!/usr/bin/env python3
"""
Reference videos -> app/src/main/assets/reference/<word>.json (format v2).

What this does, and why it differs from the first version:

  * Decodes through ffmpeg at a constant 30 fps. The clips are variable frame rate and
    OpenCV silently drops frames from them.
  * Detects on the FULL mirrored frame. The old 3:4 centre crop cut off hands in the wide
    signs (Sports, Sofa, Laptop...).
  * Assigns hands by screen position and tracking, not MediaPipe's label. Measured on these
    clips, the label on a mirrored frame is the OPPOSITE of the signer's real hand.
  * Keeps only the sign itself. Raising the hands and lowering them again is cut, so the
    first frame is a clear held pose with every hand the sign uses in view.
  * Records which side of each hand faces the camera in every frame.

Coordinates are in "video heights" with x aspect-corrected: y in 0..1, x in 0..16/9, and z in
the same unit, so distances mean the same in every direction. The app fits them to the screen.

Usage:
  ~/skel-env/bin/python extract_reference_landmarks.py /path/to/20_medoids
  ... --only Absent Red          process some words
  ... --report-dir /tmp/sheets   also write a contact sheet per word for checking
  ... --replay-dir /tmp/replay   also write the whole clip as a "live user" test recording
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

WORDS = ["Absent", "Approve", "Red", "Green", "Today", "Yesterday", "Brain", "Heart", "Book", "Pen",
         "Chair", "Sofa", "Computer", "Laptop", "Cat", "Dog", "Game", "Sports", "Tomato", "Onion"]
VIDEO_EXTS = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v"}
FPS = 30.0
DETECT_H = 720
SLOT_LEFT, SLOT_RIGHT = 0, 1           # the signer's real hands; left = screen left in selfie view
SIDE_PALM, SIDE_BACK, SIDE_EDGE = 0, 1, 2
# Must match HandGeometry.EDGE_LIMIT in the app.
EDGE_LIMIT = 0.35
# Manual (start, end) seconds into the decoded clip, for clips the automatic cut gets wrong.
# Brain: the automatic cut needs both hands in view, which loses the hand-at-temple opening.
CORE_OVERRIDES = {"Brain": (1.2, 2.75)}
CONN = [(0, 1), (1, 2), (2, 3), (3, 4), (0, 5), (5, 6), (6, 7), (7, 8), (5, 9), (9, 10), (10, 11), (11, 12),
        (9, 13), (13, 14), (14, 15), (15, 16), (13, 17), (17, 18), (18, 19), (19, 20), (0, 17)]


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def find_video(root, word):
    w = norm(word)
    vids = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in VIDEO_EXTS)
    in_dir = [p for p in vids if norm(p.parent.name) == w]
    named = [p for p in vids if norm(p.stem).startswith(w)]
    for pool in (in_dir, named):
        pref = [p for p in pool if "reference" in p.stem.lower()]
        if pref or pool:
            return (pref or pool)[0]
    return None


def decode(path):
    """All frames at a constant FPS, scaled to DETECT_H and mirrored (selfie view, like the app)."""
    info = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                           "-of", "csv=p=0", str(path)], capture_output=True, text=True, check=True).stdout
    w, h = (int(v) for v in info.strip().split(",")[:2])
    out_w = int(round(w * DETECT_H / h / 2) * 2)
    cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-vf", f"fps={FPS},scale={out_w}:{DETECT_H},hflip",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, DETECT_H, out_w, 3)


def detect_pass(frames, model, order, min_conf):
    opts = vision.HandLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.VIDEO, num_hands=2, min_hand_detection_confidence=min_conf,
        min_hand_presence_confidence=min_conf, min_tracking_confidence=min_conf)
    aspect = frames.shape[2] / frames.shape[1]
    out = [[] for _ in frames]
    with vision.HandLandmarker.create_from_options(opts) as lm:
        for step, i in enumerate(order):
            img = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(frames[i]))
            r = lm.detect_for_video(img, int(step * 1000 / FPS))
            for k, hand in enumerate(r.hand_landmarks):
                pts = np.array([(p.x * aspect, p.y, p.z * aspect) for p in hand], np.float32)
                out[i].append({"pts": pts, "label": r.handedness[k][0].category_name,
                               "score": r.handedness[k][0].score})
    return out


def palm_len(p):
    return float(np.linalg.norm(p[9, :2] - p[0, :2]) + np.linalg.norm(p[17, :2] - p[5, :2])) / 2 + 1e-6


def merge_passes(a, b):
    """Union of forward and backward detections; the same hand seen twice is kept once."""
    merged = []
    for fa, fb in zip(a, b):
        hands = list(fa)
        for h in fb:
            d = [np.linalg.norm(h["pts"][:, :2] - x["pts"][:, :2], axis=1).mean() / palm_len(x["pts"]) for x in hands]
            if not d or min(d) > 0.6:
                hands.append(h)
        # one physical hand detected twice in a single pass
        hands.sort(key=lambda h: -h["score"])
        keep = []
        for h in hands:
            if all(np.linalg.norm(h["pts"][:, :2] - x["pts"][:, :2], axis=1).mean() / palm_len(x["pts"]) > 0.6 for x in keep):
                keep.append(h)
        merged.append(keep[:2])
    return merged


def build_tracks(dets, max_gap=8):
    """Links detections over time into continuous hands, ignoring labels."""
    tracks = []
    for t, hands in enumerate(dets):
        used = set()
        for h in sorted(hands, key=lambda h: -h["score"]):
            best, best_d = None, 1e9
            for k, tr in enumerate(tracks):
                if k in used or t - tr["last_t"] > max_gap or tr["last_t"] == t:
                    continue
                d = float(np.linalg.norm(h["pts"][0, :2] - tr["last"][0, :2])) / palm_len(tr["last"])
                limit = 1.2 + 0.5 * (t - tr["last_t"])
                if d < limit and d < best_d:
                    best, best_d = k, d
            if best is None:
                tracks.append({"items": {}, "votes": {"Left": 0.0, "Right": 0.0}, "last": None, "last_t": t})
                best = len(tracks) - 1
            tr = tracks[best]
            tr["items"][t] = h["pts"]
            tr["votes"][h["label"]] += h["score"]
            tr["last"], tr["last_t"] = h["pts"], t
            used.add(best)
    return tracks


def assign_slots(dets):
    """Per frame -> slot. Each whole track gets one hand, by vote: a mirrored frame's label is reversed."""
    n = len(dets)
    out = np.full((n, 2, 21, 3), np.nan, np.float32)
    disagreements = comparisons = 0
    for hands in dets:
        if len(hands) == 2:
            comparisons += 1
            left_on_screen = hands[int(np.argmin([h["pts"][:, 0].mean() for h in hands]))]
            if left_on_screen["label"] != "Left":
                disagreements += 1
    # longest tracks choose first, so a short false track cannot displace a real hand
    for tr in sorted(build_tracks(dets), key=lambda tr: -len(tr["items"])):
        slot = SLOT_RIGHT if tr["votes"]["Left"] >= tr["votes"]["Right"] else SLOT_LEFT
        for s in (slot, 1 - slot):
            if not any(not np.isnan(out[t, s, 0, 0]) for t in tr["items"]):
                for t, pts in tr["items"].items():
                    out[t, s] = pts
                break
    return out, disagreements, comparisons


def present_mask(seq, s):
    return ~np.isnan(seq[:, s, 0, 0])


def fill_and_smooth(seq, max_gap=8, sigma=1.2):
    seq = seq.copy()
    for s in (0, 1):
        pres = present_mask(seq, s)
        idx = np.flatnonzero(pres)
        for a, b in zip(idx[:-1], idx[1:]):
            if 1 < b - a <= max_gap + 1:
                for t in range(a + 1, b):
                    w = (t - a) / (b - a)
                    seq[t, s] = seq[a, s] * (1 - w) + seq[b, s] * w
        pres = present_mask(seq, s)
        idx = np.flatnonzero(pres)
        if idx.size == 0:
            continue
        # median then gaussian, inside each continuous run only
        runs = np.split(idx, np.flatnonzero(np.diff(idx) > 1) + 1)
        r = int(3 * sigma)
        kernel = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
        kernel /= kernel.sum()
        for run in runs:
            if run.size < 5:
                continue
            block = seq[run[0]: run[-1] + 1, s]
            med = block.copy()
            for t in range(2, len(block) - 2):
                med[t] = np.median(block[t - 2: t + 3], axis=0)
            pad = np.concatenate([np.repeat(med[:1], r, 0), med, np.repeat(med[-1:], r, 0)])
            seq[run[0]: run[-1] + 1, s] = np.apply_along_axis(lambda v: np.convolve(v, kernel, "valid"), 0, pad)
    return seq


def sign_slots(seq):
    pres = [present_mask(seq, s) for s in (0, 1)]
    any_hand = pres[0] | pres[1]
    n = max(int(any_hand.sum()), 1)
    slots = [s for s in (0, 1) if pres[s].sum() >= 0.30 * n]
    return slots or [int(np.argmax([p.sum() for p in pres]))]


def hand_speed(seq, slots):
    """Mean landmark speed per frame, in palm lengths per second. NaN where a needed hand is missing."""
    n = len(seq)
    sp = np.full(n, np.nan)
    for t in range(1, n):
        vals = []
        for s in slots:
            if np.isnan(seq[t, s, 0, 0]) or np.isnan(seq[t - 1, s, 0, 0]):
                vals = None
                break
            d = np.linalg.norm(seq[t, s, :, :2] - seq[t - 1, s, :, :2], axis=1).mean()
            vals.append(d / palm_len(seq[t, s]))
        if vals:
            sp[t] = float(np.mean(vals)) * FPS
    return sp


def find_core(seq, slots):
    """(start, end) of the sign proper: the longest stretch with all hands, minus the rise and the drop."""
    all_in = np.all([present_mask(seq, s) for s in slots], axis=0)
    idx = np.flatnonzero(all_in)
    if idx.size < 8:
        return None
    runs = np.split(idx, np.flatnonzero(np.diff(idx) > 1) + 1)
    run = max(runs, key=len)
    a, b = int(run[0]), int(run[-1])
    sp = hand_speed(seq, slots)
    seg = np.nan_to_num(sp[a: b + 1], nan=0.0)
    if len(seg) >= 5:
        seg = np.convolve(seg, np.ones(5) / 5, "same")
    peak = float(seg.max()) if seg.size else 0.0
    if peak <= 0:
        return a, b

    def settle(values, max_frac):
        """First slow point from one end, within max_frac of the run; the frame index in `values`."""
        limit = max(1, int(len(values) * max_frac))
        for i in range(1, limit):
            is_min = values[i] <= values[i - 1] and values[i] <= values[i + 1] if i + 1 < len(values) else False
            if values[i] < 0.30 * peak or (is_min and values[i] < 0.55 * peak):
                return i
        return 0

    start = a + settle(seg, 0.45)
    end = b - settle(seg[::-1], 0.35)
    if end - start < 12:          # never cut a sign down to under 0.4 s
        return a, b
    return start, end


def palm_side(p, slot):
    """Which side of the hand faces the camera. Same formula as HandGeometry.side in the app."""
    u, v = p[5] - p[0], p[17] - p[0]
    n = np.cross(u, v)
    length = float(np.linalg.norm(n)) + 1e-9
    nz = float(n[2]) / length
    if abs(nz) < EDGE_LIMIT:
        return SIDE_EDGE
    palm_positive = slot == SLOT_RIGHT      # right hand, selfie view, palm to camera: nz > 0
    return SIDE_PALM if (nz > 0) == palm_positive else SIDE_BACK


def side_track(core, slot, min_hold=5):
    """Side per frame, with blips shorter than min_hold frames removed."""
    raw = [SIDE_EDGE if np.isnan(f[slot, 0, 0]) else palm_side(f[slot], slot) for f in core]
    out = list(raw)
    t = 0
    while t < len(out):
        e = t
        while e + 1 < len(out) and out[e + 1] == out[t]:
            e += 1
        if e - t + 1 < min_hold and (t > 0 or e < len(out) - 1):
            fill = out[t - 1] if t > 0 else out[e + 1]
            out[t: e + 1] = [fill] * (e - t + 1)
        t = e + 1
    return out


def to_126(frame, slots):
    row = np.zeros(126)
    for s in slots:
        if not np.isnan(frame[s, 0, 0]):
            row[s * 63: (s + 1) * 63] = frame[s].reshape(-1)
    return [round(float(v), 4) for v in row]


def quality(seq, core, slots, start, end, aspect, n_total):
    c = core
    pts = c[:, slots, :, :2].reshape(-1, 2)
    pts = pts[~np.isnan(pts[:, 0])]
    off = ((pts[:, 0] < 0) | (pts[:, 0] > aspect) | (pts[:, 1] < 0) | (pts[:, 1] > 1)).mean()
    first = c[0, slots, :, :2].reshape(-1, 2)
    first = first[~np.isnan(first[:, 0])]
    first_off = ((first[:, 0] < 0) | (first[:, 0] > aspect) | (first[:, 1] < 0) | (first[:, 1] > 1)).mean()
    palm = np.mean([palm_len(f[s]) for f in c for s in slots if not np.isnan(f[s, 0, 0])])
    sp = hand_speed(seq, slots)[start: end + 1]
    return {
        "core_seconds": round((end - start + 1) / FPS, 2),
        "palm_height_pct": round(float(palm) * 100, 1),
        "offscreen_pct": round(float(off) * 100, 1),
        "first_offscreen_pct": round(float(first_off) * 100, 1),
        "peak_speed_palms_s": round(float(np.nanmax(sp)), 1) if np.isfinite(sp).any() else 0.0,
        "lowest_y": round(float(pts[:, 1].max()), 3),
    }


def write_sheet(path, frames, core_slice, seq, slots, sides, word):
    start, end = core_slice
    aspect = frames.shape[2] / frames.shape[1]
    names = ["PALM", "BACK", "EDGE"]
    tiles = []
    for t in np.linspace(start, end, 8).astype(int):
        img = cv2.cvtColor(frames[t], cv2.COLOR_RGB2BGR).copy()
        for s in slots:
            p = seq[t, s]
            if np.isnan(p[0, 0]):
                continue
            px = np.stack([p[:, 0] / aspect * img.shape[1], p[:, 1] * img.shape[0]], 1).astype(int)
            col = (0, 140, 255) if s == SLOT_RIGHT else (255, 200, 0)
            for a, b in CONN:
                cv2.line(img, tuple(px[a]), tuple(px[b]), col, 2, cv2.LINE_AA)
            label = f"{'R' if s == SLOT_RIGHT else 'L'} {names[sides[s][t - start]]}"
            org = (int(np.clip(px[:, 0].min(), 5, img.shape[1] - 330)), int(np.clip(px[:, 1].min() - 12, 50, img.shape[0] - 10)))
            cv2.putText(img, label, org, 0, 1.6, (0, 0, 0), 9, cv2.LINE_AA)
            cv2.putText(img, label, org, 0, 1.6, (255, 255, 255), 4, cv2.LINE_AA)
        cv2.putText(img, f"{word} {t - start + 1}/{end - start + 1}", (12, 44), 0, 1.4, (0, 0, 0), 8, cv2.LINE_AA)
        cv2.putText(img, f"{word} {t - start + 1}/{end - start + 1}", (12, 44), 0, 1.4, (255, 255, 255), 3, cv2.LINE_AA)
        tiles.append(cv2.resize(img[:, img.shape[1] // 2 - int(img.shape[0] * .75): img.shape[1] // 2 + int(img.shape[0] * .75)], (480, 360), cv2.INTER_AREA))
    cv2.imwrite(str(path), np.vstack([np.hstack(tiles[:4]), np.hstack(tiles[4:])]), [cv2.IMWRITE_JPEG_QUALITY, 82])


def process(word, video, a):
    frames = decode(video)
    n = len(frames)
    aspect = frames.shape[2] / frames.shape[1]
    fwd = detect_pass(frames, a.model, list(range(n)), a.min_conf)
    bwd = detect_pass(frames, a.model, list(range(n - 1, -1, -1)), a.min_conf)
    seq, disagree, compared = assign_slots(merge_passes(fwd, bwd))
    seq = fill_and_smooth(seq)
    slots = sign_slots(seq)
    if a.replay_dir:
        a.replay_dir.mkdir(parents=True, exist_ok=True)
        rows = [to_126(f, [0, 1]) for f in seq]
        (a.replay_dir / f"{video.stem}.json").write_text(json.dumps(
            {"word": word, "aspect": aspect, "fps": FPS, "frames": rows}, separators=(",", ":")))
    if word in CORE_OVERRIDES:
        s0, s1 = CORE_OVERRIDES[word]
        core = (int(s0 * FPS), min(n - 1, int(s1 * FPS)))
    else:
        core = find_core(seq, slots)
    if core is None:
        print(f"  !! {word}: hands not visible long enough")
        return None
    start, end = core
    c = seq[start: end + 1]
    sides = {s: side_track(c, s) for s in slots}
    q = quality(seq, c, slots, start, end, aspect, n)
    q.update({"label_disagree": disagree, "label_compared": compared, "clip_seconds": round(n / FPS, 2),
              "core_start_s": round(start / FPS, 2), "hands": len(slots)})
    data = {
        "version": 2, "word": word, "source_video": video.name, "fps": FPS,
        "aspect": round(aspect, 4), "slots": slots,
        "layout": "126 floats per frame: signer's left hand 21x(x,y,z), then right hand; "
                  "video-height units, selfie view; 0 = hand not used",
        "side": {str(s): sides[s] for s in slots}, "side_codes": "0 palm, 1 back, 2 edge",
        "quality": q, "frames": [to_126(f, slots) for f in c],
    }
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / f"{word.lower()}.json").write_text(json.dumps(data, separators=(",", ":")))
    if a.report_dir:
        a.report_dir.mkdir(parents=True, exist_ok=True)
        write_sheet(a.report_dir / f"{word.lower()}.jpg", frames, core, seq, slots, sides, word)
    return q


def main():
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("videos_dir", type=Path)
    ap.add_argument("--out", type=Path, default=here.parent / "app/src/main/assets/reference")
    ap.add_argument("--model", type=Path, default=here / "hand_landmarker.task")
    ap.add_argument("--only", nargs="+", metavar="WORD")
    ap.add_argument("--min-conf", type=float, default=0.4)
    ap.add_argument("--report-dir", type=Path)
    ap.add_argument("--replay-dir", type=Path)
    a = ap.parse_args()
    if not a.model.is_file():
        sys.exit(f"Model not found: {a.model}")
    wanted = {norm(w) for w in a.only} if a.only else None
    print(f"{'word':10} {'hands':>5} {'clip':>5} {'sign':>5} {'palm%':>6} {'off%':>5} {'off0%':>5} {'peak':>5} {'lowY':>5} {'labelWrong':>11}")
    for word in WORDS:
        if wanted and norm(word) not in wanted:
            continue
        video = find_video(a.videos_dir, word)
        if video is None:
            print(f"  -- {word}: no video")
            continue
        q = process(word, video, a)
        if q:
            print(f"{word:10} {q['hands']:5} {q['clip_seconds']:5.1f} {q['core_seconds']:5.2f} {q['palm_height_pct']:6.1f} "
                  f"{q['offscreen_pct']:5.1f} {q['first_offscreen_pct']:5.1f} {q['peak_speed_palms_s']:5.1f} {q['lowest_y']:5.2f} "
                  f"{q['label_disagree']:5}/{q['label_compared']:<5}", flush=True)


if __name__ == "__main__":
    main()
