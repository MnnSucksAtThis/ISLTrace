#!/usr/bin/env python3
"""
Reference videos -> small clips for the app's Video button (app/src/main/assets/videos/<word>.mp4).

Run this AFTER extract_reference_landmarks.py: it reads each word's JSON to find when the sign
happens and how far the hands reach. Needs only Python and ffmpeg.

Each clip is:
  - Trimmed to the sign, keeping 1 s before it and 0.8 s after, so the learner also sees the hands
    rise into place. It deliberately keeps more than the landmark data, which starts on a held pose.
  - Cropped to the signer, around the centre of the frame, always wide enough for both hands.
  - Mirrored, to match the selfie view of the rest of the app.
  - H.264 with no audio, the format every Android phone can play.

Usage:
  python3 make_reference_videos.py /path/to/20_medoids
  python3 make_reference_videos.py /path/to/20_medoids --only Absent Red
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

VIDEO_EXTS = {".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v"}
BEFORE_S = 1.0
AFTER_S = 0.8
OUTPUT_H = 640
CRF = 27
MIN_WIDTH_RATIO = 0.75      # never narrower than 3:4, so head and shoulders stay in view
HAND_MARGIN = 0.10          # space beside the farthest hand, in video heights


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def find_video(root, word, source_name):
    vids = [p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in VIDEO_EXTS]
    exact = [p for p in vids if p.name == source_name]
    if exact:
        return exact[0]
    named = [p for p in vids if norm(p.parent.name) == norm(word) or norm(p.stem).startswith(norm(word))]
    return sorted(named)[0] if named else None


def crop_width(data):
    """Crop width in video heights, centred on the frame, wide enough for every hand position."""
    aspect = data["aspect"]
    mid = aspect / 2
    farthest = 0.0
    for frame in data["frames"]:
        for slot in (0, 1):
            hand = frame[slot * 63: (slot + 1) * 63]
            if any(hand):
                farthest = max(farthest, max(abs(x - mid) for x in hand[0::3]))
    return min(max(MIN_WIDTH_RATIO, 2 * (farthest + HAND_MARGIN)), aspect)


def make_clip(video, data, out):
    q = data["quality"]
    start = max(0.0, q["core_start_s"] - BEFORE_S)
    length = min(q["clip_seconds"] - start, q["core_start_s"] + q["core_seconds"] + AFTER_S - start)
    width = crop_width(data)
    vf = (f"crop=trunc(ih*{width:.4f}/2)*2:ih:(iw-trunc(ih*{width:.4f}/2)*2)/2:0,"
          f"scale=-2:{OUTPUT_H},hflip,fps=30,format=yuv420p")
    cmd = ["ffmpeg", "-y", "-v", "error", "-ss", f"{start:.3f}", "-i", str(video), "-t", f"{length:.3f}",
           "-vf", vf, "-an", "-map_metadata", "-1", "-c:v", "libx264", "-profile:v", "main", "-preset", "slow",
           "-crf", str(CRF), "-g", "15", "-movflags", "+faststart", str(out)]
    subprocess.run(cmd, check=True)
    return length, width


def main():
    here = Path(__file__).resolve().parent
    assets = here.parent / "app/src/main/assets"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("videos_dir", type=Path)
    ap.add_argument("--reference", type=Path, default=assets / "reference")
    ap.add_argument("--out", type=Path, default=assets / "videos")
    ap.add_argument("--only", nargs="+", metavar="WORD")
    a = ap.parse_args()
    wanted = {norm(w) for w in a.only} if a.only else None
    a.out.mkdir(parents=True, exist_ok=True)
    total = 0
    print(f"{'word':10} {'seconds':>7} {'crop w/h':>8} {'KB':>6}")
    for path in sorted(a.reference.glob("*.json")):
        data = json.loads(path.read_text())
        word = data["word"]
        if wanted and norm(word) not in wanted:
            continue
        video = find_video(a.videos_dir, word, data["source_video"])
        if video is None:
            sys.exit(f"No source video found for {word}")
        out = a.out / f"{word.lower()}.mp4"
        length, width = make_clip(video, data, out)
        size = out.stat().st_size
        total += size
        print(f"{word:10} {length:7.2f} {width:8.2f} {size / 1000:6.0f}", flush=True)
    print(f"Total: {total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
