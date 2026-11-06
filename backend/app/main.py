"""FrameX – Video Frame Extractor API."""
from __future__ import annotations

import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import extractor
from .config import FFMPEG, FRONTEND_DIST, MAX_UPLOAD_BYTES, THUMB_WIDTH, ZIP_NAME
from .extractor import Cancelled, ExtractionError
from .jobs import ExtractSettings, Job, JobStore
from .probe import ProbeError, probe
from .zipper import ZipError, build_zip

app = FastAPI(title="FrameX – Video Frame Extractor", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
store = JobStore()

VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".webm", ".flv", ".wmv", ".mpg", ".mpeg",
              ".ts", ".mts", ".m2ts", ".3gp", ".ogv", ".mxf", ".gif", ".y4m"}


class Settings(BaseModel):
    format: str = Field("png", pattern="^(png|jpeg|webp)$")
    quality: int = Field(100, ge=1, le=100)
    zero_pad: bool = False
    start_frame: int | None = Field(None, ge=1)
    end_frame: int | None = Field(None, ge=1)

    def to_settings(self) -> ExtractSettings:
        return ExtractSettings(**self.model_dump())


def _job(jid: str) -> Job:
    job = store.get(jid)
    if not job:
        raise HTTPException(404, "Job not found or expired. Please upload the video again.")
    return job


def _planned_frames(job: Job, s: Settings | ExtractSettings) -> int:
    total = job.info.frame_count if job.info else 0
    start = s.start_frame or 1
    end = s.end_frame or total
    if total:
        end = min(end, total)
    return max(end - start + 1, 0)


# ----------------------------------------------------------------------------
@app.get("/api/health")
def health() -> dict:
    try:
        v = subprocess.run([FFMPEG, "-version"], capture_output=True, text=True, timeout=10).stdout.splitlines()[0]
    except Exception as exc:  # pragma: no cover
        return {"ok": False, "ffmpeg": None, "error": str(exc)}
    return {"ok": True, "ffmpeg": v}


@app.put("/api/upload")
async def upload(request: Request, filename: str = Query(..., min_length=1, max_length=255)) -> dict:
    """Upload the raw video bytes as the request body (streamed straight to disk)."""
    safe = Path(filename).name
    suffix = Path(safe).suffix.lower()
    if suffix not in VIDEO_EXTS:
        suffix = suffix if re.fullmatch(r"\.[a-z0-9]{1,5}", suffix) else ".bin"

    length = request.headers.get("content-length")
    if MAX_UPLOAD_BYTES and length and int(length) > MAX_UPLOAD_BYTES:
        raise HTTPException(413, "File is larger than the configured upload limit.")
    if length and int(length) * 1.05 > shutil.disk_usage(store_dir()).free:
        raise HTTPException(507, "Not enough disk space to store this upload.")

    job = store.create(suffix)
    written = 0
    try:
        with job.video_path.open("wb") as fh:
            async for chunk in request.stream():
                written += len(chunk)
                if MAX_UPLOAD_BYTES and written > MAX_UPLOAD_BYTES:
                    raise HTTPException(413, "File is larger than the configured upload limit.")
                fh.write(chunk)
        if written == 0:
            raise HTTPException(400, "The uploaded file is empty.")
        job.info = probe(job.video_path, safe)
    except ProbeError as exc:
        store.delete(job.id)
        raise HTTPException(422, str(exc)) from exc
    except HTTPException:
        store.delete(job.id)
        raise
    except Exception as exc:
        store.delete(job.id)
        raise HTTPException(500, f"Upload failed: {exc}") from exc
    job.stage_label = "Ready"
    return job.public()


def store_dir() -> Path:
    from .config import WORK_DIR
    return WORK_DIR


@app.get("/api/jobs/{jid}")
def get_job(jid: str) -> dict:
    return _job(jid).public()


@app.post("/api/jobs/{jid}/estimate")
def estimate(jid: str, s: Settings) -> dict:
    job = _job(jid)
    assert job.info
    per = extractor.estimate_frame_bytes(job.video_path, job.info.duration, s.format, s.quality)
    n = _planned_frames(job, s)
    return {"images": n, "bytes_per_image": per, "total_bytes": per * n, "format": s.format}


@app.post("/api/jobs/{jid}/extract")
def start_extract(jid: str, s: Settings) -> dict:
    job = _job(jid)
    with job.lock:
        if job.status in ("extracting", "validating", "zipping"):
            raise HTTPException(409, "Extraction is already running for this video.")
        if s.end_frame and s.start_frame and s.end_frame < s.start_frame:
            raise HTTPException(400, "End frame must be greater than or equal to start frame.")
        if job.info and job.info.frame_count and s.start_frame and s.start_frame > job.info.frame_count:
            raise HTTPException(400, f"Start frame is beyond the last frame ({job.info.frame_count}).")
        job.settings = s.to_settings()
        job.expected_frames = _planned_frames(job, s)
        try:
            per = extractor.estimate_frame_bytes(job.video_path, job.info.duration, s.format, s.quality)
            extractor.check_disk(job.dir, per * max(job.expected_frames, 1))
        except ExtractionError as exc:
            raise HTTPException(507, str(exc)) from exc
        _reset(job)
        job.status, job.stage_label = "extracting", "Extracting frames"
        job.started_at = time.time()
    threading.Thread(target=_run_job, args=(job,), daemon=True, name=f"framex-{jid[:8]}").start()
    return job.public()


def _reset(job: Job) -> None:
    job.error = None
    job.warnings = []
    job.frames_done = job.zip_done = 0
    job.frame_names = []
    job.validation = {}
    job.zip_path = None
    job.zip_bytes = job.frames_bytes = 0
    job.started_at = job.extract_finished_at = job.finished_at = None
    job.cancel_requested = False
    for d in ("frames", "raw", "thumbs"):
        shutil.rmtree(job.dir / d, ignore_errors=True)
    (job.dir / ZIP_NAME).unlink(missing_ok=True)


def _run_job(job: Job) -> None:
    try:
        extractor.extract(job)
        job.extract_finished_at = time.time()
        job.status, job.stage_label = "validating", "Validating frames"
        job.validation = extractor.validate_frames(job)
        job.status, job.stage_label = "zipping", "Packaging ZIP"
        build_zip(job)
        job.finished_at = time.time()
        job.status, job.stage_label = "done", "Complete"
    except Cancelled:
        job.status, job.stage_label = "cancelled", "Cancelled"
        _cleanup_outputs(job)
    except (ExtractionError, ZipError) as exc:
        if job.cancel_requested:
            job.status, job.stage_label = "cancelled", "Cancelled"
        else:
            job.status, job.stage_label, job.error = "error", "Failed", str(exc)
        _cleanup_outputs(job)
    except Exception as exc:  # pragma: no cover - unexpected
        job.status, job.stage_label, job.error = "error", "Failed", f"Unexpected error: {exc}"
        _cleanup_outputs(job)
    finally:
        job.finished_at = job.finished_at or time.time()
        job.touch()


def _cleanup_outputs(job: Job) -> None:
    job.frame_names = []
    for d in ("frames", "raw", "thumbs"):
        shutil.rmtree(job.dir / d, ignore_errors=True)
    for f in (ZIP_NAME, ZIP_NAME + ".part"):
        (job.dir / f).unlink(missing_ok=True)


@app.post("/api/jobs/{jid}/cancel")
def cancel(jid: str) -> dict:
    job = _job(jid)
    job.cancel_requested = True
    if job.process and job.process.poll() is None:
        job.process.kill()
    return job.public()


@app.delete("/api/jobs/{jid}")
def delete(jid: str) -> dict:
    if not store.delete(jid):
        raise HTTPException(404, "Job not found.")
    return {"deleted": True}


# ---- gallery ----------------------------------------------------------------
def _frame_path(job: Job, name: str) -> Path:
    if job.status != "done" or name not in set(job.frame_names):
        raise HTTPException(404, "Frame not found.")
    return job.frames_dir / name


@app.get("/api/jobs/{jid}/frames")
def list_frames(jid: str, offset: int = Query(0, ge=0), limit: int = Query(120, ge=1, le=1000)) -> dict:
    job = _job(jid)
    if job.status != "done":
        raise HTTPException(409, "Frames are available once extraction is complete.")
    return {"total": len(job.frame_names), "offset": offset,
            "names": job.frame_names[offset:offset + limit]}


@app.get("/api/jobs/{jid}/frames/{name}")
def get_frame(jid: str, name: str) -> FileResponse:
    job = _job(jid)
    return FileResponse(_frame_path(job, name), headers={"Cache-Control": "private, max-age=3600"})


@app.get("/api/jobs/{jid}/thumbs/{name}")
def get_thumb(jid: str, name: str) -> FileResponse:
    job = _job(jid)
    src = _frame_path(job, name)
    dst = job.thumbs_dir / (Path(name).stem + ".jpg")
    if not dst.exists():
        extractor.make_thumbnail(src, dst, THUMB_WIDTH)
    return FileResponse(dst, media_type="image/jpeg", headers={"Cache-Control": "private, max-age=3600"})


@app.get("/api/jobs/{jid}/download")
def download(jid: str) -> FileResponse:
    job = _job(jid)
    if job.status != "done" or not job.zip_path or not job.zip_path.exists():
        raise HTTPException(409, "The ZIP archive is not ready.")
    return FileResponse(job.zip_path, media_type="application/zip", filename=ZIP_NAME)


@app.exception_handler(ProbeError)
def _probe_err(_, exc: ProbeError) -> JSONResponse:  # pragma: no cover
    return JSONResponse({"detail": str(exc)}, status_code=422)


# ---- frontend (production build) --------------------------------------------
if FRONTEND_DIST.exists():
    app.mount("/", StaticFiles(directory=FRONTEND_DIST, html=True), name="frontend")
