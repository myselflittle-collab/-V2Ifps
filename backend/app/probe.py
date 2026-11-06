"""Video metadata detection with FFprobe.

Frame count strategy (most to least trustworthy):
  1. ``decoded``   – the real number of decoded frames (known after extraction).
  2. ``packets``   – ``ffprobe -count_packets`` on the video stream. For almost every
                     codec one packet decodes to exactly one frame, so this matches
                     the decoded count without decoding the whole file.
  3. ``metadata``  – the container's ``nb_frames`` field.
  4. ``estimate``  – duration x average FPS (last resort, flagged as an estimate).
"""
from __future__ import annotations

import json
import subprocess
from dataclasses import asdict, dataclass
from fractions import Fraction
from pathlib import Path

from .config import FFPROBE, NO_WINDOW


class ProbeError(RuntimeError):
    pass


@dataclass
class VideoInfo:
    filename: str
    container: str
    codec: str
    codec_long: str
    pix_fmt: str
    width: int              # stored (coded) width
    height: int
    display_width: int      # after applying rotation metadata
    display_height: int
    rotation: int
    duration: float
    fps: float              # average FPS (frames / time)
    fps_fraction: str
    r_frame_rate: str       # FFmpeg's "real base" frame rate
    is_vfr: bool
    frame_count: int
    frame_count_method: str
    bit_rate: int | None
    size_bytes: int

    def to_dict(self) -> dict:
        return asdict(self)


def _run(args: list[str], timeout: int = 600) -> dict:
    proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout, **NO_WINDOW)
    if proc.returncode != 0:
        msg = proc.stderr.strip().splitlines()[-1:] or ["unknown error"]
        raise ProbeError(f"FFprobe could not read this file: {msg[0]}")
    try:
        return json.loads(proc.stdout or "{}")
    except json.JSONDecodeError as exc:  # pragma: no cover
        raise ProbeError("FFprobe returned unreadable output") from exc


def _frac(value: str | None) -> Fraction | None:
    if not value or value in ("0/0", "N/A"):
        return None
    try:
        f = Fraction(value)
    except (ValueError, ZeroDivisionError):
        return None
    return f if f > 0 else None


def _rotation(stream: dict) -> int:
    rot = 0
    for sd in stream.get("side_data_list", []) or []:
        if "rotation" in sd:
            rot = int(round(float(sd["rotation"])))
    if not rot:
        tag = (stream.get("tags") or {}).get("rotate")
        if tag:
            rot = -int(tag)  # legacy tag is clockwise; side data is counter-clockwise
    return rot % 360


def count_packets(path: Path) -> int | None:
    data = _run([
        FFPROBE, "-v", "error", "-select_streams", "v:0", "-count_packets",
        "-show_entries", "stream=nb_read_packets", "-of", "json", str(path),
    ])
    try:
        n = int(data["streams"][0]["nb_read_packets"])
        return n if n > 0 else None
    except (KeyError, IndexError, ValueError):
        return None


def probe(path: Path, original_name: str | None = None) -> VideoInfo:
    data = _run([
        FFPROBE, "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path),
    ])
    streams = [s for s in data.get("streams", []) if s.get("codec_type") == "video"
               and not (s.get("disposition") or {}).get("attached_pic")]
    if not streams:
        raise ProbeError("No video stream found in this file.")
    s = streams[0]
    fmt = data.get("format", {})

    width, height = int(s.get("width") or 0), int(s.get("height") or 0)
    if width <= 0 or height <= 0:
        raise ProbeError("Video stream has no valid resolution.")

    rotation = _rotation(s)
    dw, dh = (height, width) if rotation in (90, 270) else (width, height)

    duration = float(s.get("duration") or fmt.get("duration") or 0)
    avg = _frac(s.get("avg_frame_rate"))
    real = _frac(s.get("r_frame_rate"))

    # Frame count
    method, count = "packets", count_packets(path)
    if not count:
        nb = s.get("nb_frames")
        if nb and str(nb).isdigit() and int(nb) > 0:
            method, count = "metadata", int(nb)
        elif duration and (avg or real):
            method, count = "estimate", int(round(duration * float(avg or real)))
        else:
            method, count = "unknown", 0

    # Average FPS: prefer frames / duration when both are known (true for VFR too)
    if count and duration and method != "estimate":
        fps = count / duration
    else:
        fps = float(avg or real or 0)
    fps_fraction = f"{avg.numerator}/{avg.denominator}" if avg else (s.get("r_frame_rate") or "")

    # VFR heuristic: average rate differs noticeably from FFmpeg's base rate
    is_vfr = bool(avg and real and abs(float(avg) - float(real)) / float(real) > 0.01)

    return VideoInfo(
        filename=original_name or path.name,
        container=fmt.get("format_long_name") or fmt.get("format_name") or "",
        codec=s.get("codec_name", ""),
        codec_long=s.get("codec_long_name", ""),
        pix_fmt=s.get("pix_fmt", ""),
        width=width, height=height,
        display_width=dw, display_height=dh,
        rotation=rotation,
        duration=round(duration, 4),
        fps=round(fps, 4),
        fps_fraction=fps_fraction,
        r_frame_rate=s.get("r_frame_rate", ""),
        is_vfr=is_vfr,
        frame_count=count,
        frame_count_method=method,
        bit_rate=int(fmt["bit_rate"]) if str(fmt.get("bit_rate", "")).isdigit() else None,
        size_bytes=int(fmt.get("size") or path.stat().st_size),
    )
