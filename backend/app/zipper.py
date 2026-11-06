"""ZIP packaging and integrity validation.

Images are stored with ``ZIP_STORED``: the bytes inside the archive are exactly
the bytes FFmpeg wrote, so nothing is recompressed or degraded (PNG/JPEG/WebP are
already compressed, so deflating them again would only cost time). Files are
streamed from disk one at a time, never loaded into RAM together.

The archive is written to ``*.part`` and only renamed to its final name after it
has passed validation, so an incomplete ZIP can never be downloaded.
"""
from __future__ import annotations

import os
import zipfile
from pathlib import Path

from .config import ZIP_NAME
from .jobs import Job


class ZipError(RuntimeError):
    pass


def build_zip(job: Job) -> Path:
    final = job.dir / ZIP_NAME
    part = job.dir / (ZIP_NAME + ".part")
    final.unlink(missing_ok=True)
    part.unlink(missing_ok=True)

    job.zip_done = 0
    with zipfile.ZipFile(part, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as zf:
        for name in job.frame_names:  # already in numeric (chronological) order
            if job.cancel_requested:
                raise ZipError("cancelled")
            zf.write(job.frames_dir / name, arcname=name)
            job.zip_done += 1

    validate_zip(part, job)
    os.replace(part, final)
    job.zip_path = final
    job.zip_bytes = final.stat().st_size
    return final


def validate_zip(path: Path, job: Job) -> None:
    try:
        with zipfile.ZipFile(path) as zf:
            infos = zf.infolist()
            names = [i.filename for i in infos]
            if names != job.frame_names:
                raise ZipError("ZIP contents do not match the extracted frame list.")
            for info in infos:
                src = job.frames_dir / info.filename
                if info.file_size != src.stat().st_size:
                    raise ZipError(f"{info.filename} has the wrong size inside the ZIP.")
            bad = zf.testzip()  # reads every entry and verifies its CRC-32
            if bad is not None:
                raise ZipError(f"CRC check failed for {bad}.")
    except zipfile.BadZipFile as exc:
        raise ZipError(f"ZIP archive is corrupt: {exc}") from exc
    job.validation["zip_entries"] = len(names)
    job.validation["zip_crc_ok"] = True
    job.validation["zip_first"] = names[0]
    job.validation["zip_last"] = names[-1]
