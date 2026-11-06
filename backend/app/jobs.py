"""In-process job registry with automatic expiry of temporary files."""
from __future__ import annotations

import shutil
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from .config import JOB_TTL_SECONDS, WORK_DIR
from .probe import VideoInfo


@dataclass
class ExtractSettings:
    format: str = "png"           # png | jpeg | webp
    quality: int = 100            # jpeg / webp only
    zero_pad: bool = False
    start_frame: int | None = None  # 1-based, inclusive
    end_frame: int | None = None    # 1-based, inclusive


@dataclass
class Job:
    id: str
    dir: Path
    video_path: Path
    info: VideoInfo | None = None
    settings: ExtractSettings | None = None

    # uploaded | extracting | validating | zipping | done | error | cancelled
    status: str = "uploaded"
    stage_label: str = "Uploaded"
    error: str | None = None
    warnings: list[str] = field(default_factory=list)

    expected_frames: int = 0       # what we expect to extract (for progress)
    frames_done: int = 0           # frames written by FFmpeg so far
    zip_done: int = 0              # files added to the ZIP so far
    started_at: float | None = None
    extract_finished_at: float | None = None
    finished_at: float | None = None

    # results
    frame_names: list[str] = field(default_factory=list)
    frame_width: int = 0
    frame_height: int = 0
    frames_bytes: int = 0
    zip_path: Path | None = None
    zip_bytes: int = 0
    validation: dict = field(default_factory=dict)

    last_touch: float = field(default_factory=time.time)
    process: subprocess.Popen | None = None
    cancel_requested: bool = False
    lock: threading.Lock = field(default_factory=threading.Lock)

    @property
    def frames_dir(self) -> Path:
        return self.dir / "frames"

    @property
    def thumbs_dir(self) -> Path:
        return self.dir / "thumbs"

    def touch(self) -> None:
        self.last_touch = time.time()

    def progress(self) -> float:
        """0-100. Extraction is 0-90 %, validation 90-92 %, ZIP 92-100 %.
        100 is reported only when the job is truly done."""
        if self.status == "done":
            return 100.0
        if self.status == "extracting":
            if not self.expected_frames:
                return 0.0
            return min(self.frames_done / self.expected_frames, 1.0) * 90.0
        if self.status == "validating":
            return 90.0
        if self.status == "zipping":
            n = max(len(self.frame_names), 1)
            return 92.0 + min(self.zip_done / n, 1.0) * 7.9
        return 0.0

    def public(self) -> dict:
        elapsed = None
        speed = None
        eta = None
        if self.started_at:
            now = time.time()
            elapsed = (self.finished_at or now) - self.started_at
            extract_time = (self.extract_finished_at or now) - self.started_at
            if self.frames_done and extract_time > 0:
                speed = self.frames_done / extract_time
                if self.status == "extracting":
                    eta = max(self.expected_frames - self.frames_done, 0) / speed
        return {
            "id": self.id,
            "status": self.status,
            "stage": self.stage_label,
            "error": self.error,
            "warnings": self.warnings,
            "progress": round(self.progress(), 2),
            "expected_frames": self.expected_frames,
            "frames_done": self.frames_done,
            "current_frame": self.frames_done,
            "speed_fps": round(speed, 2) if speed else None,
            "eta_seconds": round(eta, 1) if eta is not None else None,
            "elapsed_seconds": round(elapsed, 2) if elapsed else None,
            "info": self.info.to_dict() if self.info else None,
            "settings": self.settings.__dict__ if self.settings else None,
            "result": {
                "total_images": len(self.frame_names),
                "first": self.frame_names[0] if self.frame_names else None,
                "last": self.frame_names[-1] if self.frame_names else None,
                "width": self.frame_width,
                "height": self.frame_height,
                "frames_bytes": self.frames_bytes,
                "zip_bytes": self.zip_bytes,
                "zip_ready": self.status == "done" and self.zip_path is not None,
                "validation": self.validation,
            } if self.status == "done" else None,
        }


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.Lock()
        WORK_DIR.mkdir(parents=True, exist_ok=True)
        # A fresh start owns nothing: remove folders left over by a previous run.
        for leftover in WORK_DIR.iterdir():
            if leftover.is_dir() and leftover.name.startswith("job_"):
                shutil.rmtree(leftover, ignore_errors=True)
        threading.Thread(target=self._reaper, daemon=True, name="framex-reaper").start()

    def create(self, suffix: str) -> Job:
        jid = uuid.uuid4().hex
        d = WORK_DIR / f"job_{jid}"
        d.mkdir(parents=True)
        job = Job(id=jid, dir=d, video_path=d / f"source{suffix}")
        with self._lock:
            self._jobs[jid] = job
        return job

    def get(self, jid: str) -> Job | None:
        with self._lock:
            job = self._jobs.get(jid)
        if job:
            job.touch()
        return job

    def delete(self, jid: str) -> bool:
        with self._lock:
            job = self._jobs.pop(jid, None)
        if not job:
            return False
        job.cancel_requested = True
        if job.process and job.process.poll() is None:
            job.process.kill()
        shutil.rmtree(job.dir, ignore_errors=True)
        return True

    def _reaper(self) -> None:
        while True:
            time.sleep(60)
            now = time.time()
            with self._lock:
                stale = [j.id for j in self._jobs.values()
                         if now - j.last_touch > JOB_TTL_SECONDS
                         and j.status not in ("extracting", "validating", "zipping")]
            for jid in stale:
                self.delete(jid)
