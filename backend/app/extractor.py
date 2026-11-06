"""Frame extraction engine.

Every decoded video frame is written exactly once, in presentation order:

* ``-fps_mode passthrough`` hands each decoded frame to the encoder with its own
  timestamp. No frame-rate conversion, no duplication, no dropping — this is what
  makes variable-frame-rate (VFR) sources come out with their true frame count.
* No ``fps=`` / ``scale=`` filters are used, so the frame rate and resolution of
  the source are preserved. FFmpeg's default auto-rotation is kept so frames come
  out upright, in the orientation the video is meant to be watched.
* An optional frame range uses ``select=between(n,a,b)`` which counts decoded
  frames (not timestamps), so ranges are frame-exact.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from PIL import Image

from .config import DISK_SAFETY_FACTOR, FFMPEG
from .jobs import ExtractSettings, Job

EXT = {"png": "png", "jpeg": "jpg", "webp": "webp"}


class ExtractionError(RuntimeError):
    pass


class Cancelled(RuntimeError):
    pass


# ----------------------------------------------------------------------------
# Encoder options
# ----------------------------------------------------------------------------
def encoder_args(fmt: str, quality: int) -> list[str]:
    quality = max(1, min(100, int(quality)))
    if fmt == "png":
        # Lossless. FFmpeg picks rgb24 for 8-bit sources and rgb48 for high bit
        # depth sources, so no colour precision is thrown away. The default
        # predictor is ~2.7x faster than "-pred mixed" for ~15 % larger files;
        # both are bit-exact lossless.
        return ["-c:v", "png"]
    if fmt == "jpeg":
        # MJPEG qscale: 1 = best ... 31 = worst. Quality 100 -> qscale 1.
        q = 1 + round((100 - quality) * 30 / 99)
        return ["-c:v", "mjpeg", "-pix_fmt", "yuvj444p", "-qmin", "1", "-q:v", str(q)]
    if fmt == "webp":
        if quality >= 100:
            return ["-c:v", "libwebp", "-lossless", "1", "-compression_level", "4"]
        return ["-c:v", "libwebp", "-quality", str(quality), "-compression_level", "4"]
    raise ExtractionError(f"Unsupported format: {fmt}")


def _range_filter(s: ExtractSettings) -> tuple[list[str], int | None]:
    """Return (filter args, number of frames to stop after)."""
    if s.start_frame is None and s.end_frame is None:
        return [], None
    start = max(1, s.start_frame or 1)
    if s.end_frame is not None:
        end = s.end_frame
        if end < start:
            raise ExtractionError("End frame must be greater than or equal to start frame.")
        return ["-vf", f"select=between(n\\,{start - 1}\\,{end - 1})"], end - start + 1
    return ["-vf", f"select=gte(n\\,{start - 1})"], None


def build_command(src: Path, out_pattern: str, s: ExtractSettings) -> list[str]:
    vf, limit = _range_filter(s)
    cmd = [
        FFMPEG, "-hide_banner", "-nostdin", "-v", "error", "-y",
        "-i", str(src),
        "-map", "0:v:0", "-an", "-sn", "-dn",
        *vf,
        "-fps_mode", "passthrough",
        *encoder_args(s.format, s.quality),
    ]
    if limit:
        cmd += ["-frames:v", str(limit)]
    cmd += ["-start_number", "1", "-progress", "pipe:1", "-nostats", out_pattern]
    return cmd


# ----------------------------------------------------------------------------
# Size estimate (encode a few real frames, measure, extrapolate)
# ----------------------------------------------------------------------------
def estimate_frame_bytes(src: Path, duration: float, fmt: str, quality: int) -> int:
    points = [0.2, 0.5, 0.8] if duration > 0.5 else [0.0]
    sizes: list[int] = []
    with tempfile.TemporaryDirectory(prefix="framex_est_") as tmp:
        for i, p in enumerate(points):
            out = Path(tmp) / f"{i}.{EXT[fmt]}"
            cmd = [FFMPEG, "-hide_banner", "-nostdin", "-v", "error", "-y",
                   "-ss", f"{duration * p:.3f}", "-i", str(src), "-map", "0:v:0",
                   "-frames:v", "1", *encoder_args(fmt, quality), str(out)]
            r = subprocess.run(cmd, capture_output=True, timeout=120)
            if r.returncode == 0 and out.exists():
                sizes.append(out.stat().st_size)
    if not sizes:
        raise ExtractionError("Could not decode a sample frame to estimate output size.")
    return int(sum(sizes) / len(sizes))


def check_disk(path: Path, needed_bytes: int) -> None:
    free = shutil.disk_usage(path).free
    if needed_bytes * DISK_SAFETY_FACTOR > free:
        raise ExtractionError(
            f"Not enough free disk space: about {needed_bytes * DISK_SAFETY_FACTOR / 1e9:.2f} GB "
            f"is needed for the frames and ZIP, {free / 1e9:.2f} GB is free.")


# ----------------------------------------------------------------------------
# Extraction
# ----------------------------------------------------------------------------
_PROGRESS_RE = re.compile(r"^(\w+)=(.*)$")


def extract(job: Job) -> None:
    """Run FFmpeg and write frames into ``job.frames_dir`` as 1..N."""
    s = job.settings
    assert s is not None and job.info is not None
    raw_dir = job.dir / "raw"
    shutil.rmtree(raw_dir, ignore_errors=True)
    shutil.rmtree(job.frames_dir, ignore_errors=True)
    raw_dir.mkdir(parents=True)

    ext = EXT[s.format]
    # FFmpeg writes fixed-width names first; final names are assigned once the
    # true frame count is known (needed for correct zero-padding width).
    cmd = build_command(job.video_path, str(raw_dir / f"%09d.{ext}"), s)

    stderr_file = (job.dir / "ffmpeg.log").open("w+")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=stderr_file, text=True, bufsize=1)
    job.process = proc
    reported = 0
    ended = False
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            if job.cancel_requested:
                proc.kill()
                raise Cancelled()
            m = _PROGRESS_RE.match(line.strip())
            if not m:
                continue
            key, val = m.groups()
            if key == "frame" and val.isdigit():
                reported = int(val)
                job.frames_done = reported
            elif key == "progress" and val == "end":
                ended = True
        proc.wait()
    finally:
        job.process = None
    if job.cancel_requested:
        raise Cancelled()
    stderr_file.seek(0)
    log = stderr_file.read().strip()
    stderr_file.close()
    if proc.returncode != 0 or not ended:
        tail = log.splitlines()[-3:] if log else ["FFmpeg exited unexpectedly"]
        raise ExtractionError("Frame extraction failed: " + " | ".join(tail))
    if log:
        # Non-fatal decoder messages (e.g. a corrupt packet) are surfaced to the user.
        job.warnings.append("FFmpeg reported: " + " | ".join(log.splitlines()[-3:]))

    files = sorted(raw_dir.iterdir(), key=lambda p: int(p.stem))
    if not files:
        raise ExtractionError("FFmpeg finished but produced no frames.")
    if len(files) != reported:
        raise ExtractionError(
            f"FFmpeg reported {reported} frames but {len(files)} images were written.")

    # Rename to the final sequential names.
    total = len(files)
    width = len(str(total)) if s.zero_pad else 0
    width = max(width, 4) if s.zero_pad else 0
    names: list[str] = []
    for i, f in enumerate(files, start=1):
        if int(f.stem) != i:
            raise ExtractionError(f"Frame sequence broken at image {i} (found {f.name}).")
        name = f"{i:0{width}d}.{ext}" if width else f"{i}.{ext}"
        names.append(name)
    raw_dir.rename(job.frames_dir)
    for f, name in zip(files, names):
        os.rename(job.frames_dir / f.name, job.frames_dir / name)

    job.frame_names = names
    job.frames_done = total


# ----------------------------------------------------------------------------
# Validation
# ----------------------------------------------------------------------------
def validate_frames(job: Job) -> dict:
    s, info = job.settings, job.info
    assert s is not None and info is not None
    names = job.frame_names
    ext = EXT[s.format]
    width = len(names[0]) - len(ext) - 1 if s.zero_pad else 0

    # 1. Sequential names 1..N, nothing missing, nothing extra
    on_disk = {p.name for p in job.frames_dir.iterdir()}
    expected = [f"{i:0{width}d}.{ext}" if width else f"{i}.{ext}" for i in range(1, len(names) + 1)]
    if names != expected or on_disk != set(expected):
        raise ExtractionError("Validation failed: filenames are not a complete 1..N sequence.")

    # 2. Every image opens and has the same native dimensions
    dims: set[tuple[int, int]] = set()
    total_bytes = 0
    for n in names:
        p = job.frames_dir / n
        total_bytes += p.stat().st_size
        try:
            with Image.open(p) as im:
                dims.add(im.size)
        except Exception as exc:
            raise ExtractionError(f"Validation failed: {n} is not a readable image ({exc}).") from exc
    if len(dims) != 1:
        job.warnings.append(f"Frames have differing sizes: {sorted(dims)} (the source changes resolution mid-stream).")
    w, h = max(dims, key=lambda d: d[0] * d[1])
    dims_match = (w, h) == (info.display_width, info.display_height)
    if not dims_match and len(dims) == 1:
        raise ExtractionError(
            f"Validation failed: frames are {w}x{h}, source is {info.display_width}x{info.display_height}.")

    # 3. Frame count vs. the source
    full = s.start_frame is None and s.end_frame is None
    count_note = None
    if full and info.frame_count and info.frame_count_method in ("packets", "metadata"):
        if len(names) != info.frame_count:
            count_note = (f"Decoded {len(names)} frames; the container index lists {info.frame_count}. "
                          "The decoded count is the true number of frames.")
            job.warnings.append(count_note)

    job.frame_width, job.frame_height = w, h
    job.frames_bytes = total_bytes
    return {
        "sequential_names": True,
        "first_image": names[0],
        "last_image": names[-1],
        "images_readable": True,
        "dimensions": f"{w}x{h}",
        "dimensions_match_source": dims_match,
        "decoded_frames": len(names),
        "source_frame_count": info.frame_count,
        "source_frame_count_method": info.frame_count_method,
        "count_note": count_note,
    }


def make_thumbnail(src: Path, dst: Path, width: int) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(src) as im:
        im.draft("RGB", (width, width))
        if im.mode not in ("RGB", "L"):
            im = im.convert("RGB")
        ratio = width / im.width
        im = im.resize((width, max(1, round(im.height * ratio))), Image.Resampling.LANCZOS) if ratio < 1 else im.copy()
        tmp = dst.with_suffix(".tmp")
        im.save(tmp, "JPEG", quality=82)
        os.replace(tmp, dst)


def now() -> float:
    return time.time()
