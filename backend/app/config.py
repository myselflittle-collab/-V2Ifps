"""Runtime configuration (override with environment variables)."""
from __future__ import annotations

import os
import shutil
from pathlib import Path

FFMPEG = os.environ.get("FRAMEX_FFMPEG", shutil.which("ffmpeg") or "ffmpeg")
FFPROBE = os.environ.get("FRAMEX_FFPROBE", shutil.which("ffprobe") or "ffprobe")

# Where uploads, extracted frames and ZIPs live while a job exists.
WORK_DIR = Path(os.environ.get("FRAMEX_WORK_DIR", Path(__file__).resolve().parents[1] / "workspace"))

# Jobs (and every file they own) are deleted this many seconds after their last activity.
JOB_TTL_SECONDS = int(os.environ.get("FRAMEX_JOB_TTL", 2 * 60 * 60))

# Maximum upload size in bytes (default 20 GB). 0 disables the limit.
MAX_UPLOAD_BYTES = int(os.environ.get("FRAMEX_MAX_UPLOAD", 20 * 1024 ** 3))

# Safety margin required on disk beyond the estimated output (frames + ZIP copy).
DISK_SAFETY_FACTOR = float(os.environ.get("FRAMEX_DISK_FACTOR", 2.2))

# Built frontend (served by FastAPI in production mode, if present).
FRONTEND_DIST = Path(os.environ.get(
    "FRAMEX_FRONTEND_DIST", Path(__file__).resolve().parents[2] / "frontend" / "dist"))

ZIP_NAME = "FrameX_Extracted_Frames.zip"
THUMB_WIDTH = 320
