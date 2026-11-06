"""End-to-end tests through the HTTP API: upload -> extract -> validate -> ZIP -> download."""
import io
import time
import zipfile

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.main import app

from .conftest import count_decoded

client = TestClient(app)


def upload(path):
    with open(path, "rb") as fh:
        r = client.put(f"/api/upload?filename={path.name}", content=fh.read())
    assert r.status_code == 200, r.text
    return r.json()


def run(job_id, timeout=300, **settings):
    r = client.post(f"/api/jobs/{job_id}/extract", json=settings)
    assert r.status_code == 200, r.text
    deadline = time.time() + timeout
    while time.time() < deadline:
        j = client.get(f"/api/jobs/{job_id}").json()
        if j["status"] in ("done", "error", "cancelled"):
            return j
        assert j["progress"] < 100
        time.sleep(0.1)
    raise AssertionError("extraction timed out")


def download_zip(job_id):
    r = client.get(f"/api/jobs/{job_id}/download")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/zip"
    assert "FrameX_Extracted_Frames.zip" in r.headers["content-disposition"]
    zf = zipfile.ZipFile(io.BytesIO(r.content))
    assert zf.testzip() is None
    return zf


def check(video, expected_frames, size, fmt="png", zero_pad=False, **extra):
    meta = upload(video)
    info = meta["info"]
    assert (info["display_width"], info["display_height"]) == size
    job = run(meta["id"], format=fmt, zero_pad=zero_pad, **extra)
    assert job["status"] == "done", job["error"]
    assert job["progress"] == 100
    res = job["result"]
    assert res["total_images"] == expected_frames
    assert (res["width"], res["height"]) == size

    zf = download_zip(meta["id"])
    names = zf.namelist()
    ext = {"png": "png", "jpeg": "jpg", "webp": "webp"}[fmt]
    if zero_pad:
        w = max(4, len(str(expected_frames)))
        assert names == [f"{i:0{w}d}.{ext}" for i in range(1, expected_frames + 1)]
    else:
        assert names == [f"{i}.{ext}" for i in range(1, expected_frames + 1)]
    # Images inside the ZIP are stored, not recompressed
    assert all(i.compress_type == zipfile.ZIP_STORED for i in zf.infolist())
    for n in (names[0], names[len(names) // 2], names[-1]):
        with Image.open(io.BytesIO(zf.read(n))) as im:
            assert im.size == size
    client.delete(f"/api/jobs/{meta['id']}")
    return meta, job


def test_health():
    assert client.get("/api/health").json()["ok"] is True


def test_a_30fps_10s_gives_300_frames(videos):
    meta, _ = check(videos["a_30fps"], 300, (1920, 1080))
    assert meta["info"]["frame_count"] == 300
    assert meta["info"]["fps"] == pytest.approx(30, abs=0.01)


def test_b_60fps_10s_gives_600_frames(videos):
    check(videos["b_60fps"], 600, (1280, 720))


def test_c_120fps_10s_gives_1200_frames(videos):
    meta, _ = check(videos["c_120fps"], 1200, (640, 360))
    assert meta["info"]["fps"] == pytest.approx(120, abs=0.01)


def test_d_variable_frame_rate_matches_true_decoded_count(videos):
    true_count = count_decoded(videos["d_vfr"])
    meta, job = check(videos["d_vfr"], true_count, (640, 360))
    assert meta["info"]["is_vfr"] is True
    # duration x nominal FPS would be wrong for this file
    assert true_count != round(meta["info"]["duration"] * 60)


def test_e_4k_preserves_resolution(videos):
    check(videos["e_4k"], 30, (3840, 2160))


def test_f_long_video(videos):
    check(videos["f_long"], 4500, (320, 180), fmt="jpeg", zero_pad=True)


def test_unusual_frame_rate_23976(videos):
    n = count_decoded(videos["odd_23976"])
    meta, _ = check(videos["odd_23976"], n, (640, 360))
    assert meta["info"]["fps"] == pytest.approx(23.976, abs=0.01)


def test_webm_vp9_to_webp(videos):
    check(videos["webm_vp9"], 72, (640, 360), fmt="webp", quality=90)


def test_rotation_metadata_is_applied(videos):
    meta, _ = check(videos["rotated"], count_decoded(videos["rotated"]), (360, 640))
    assert meta["info"]["rotation"] in (90, 270)


def test_frame_range_is_frame_exact(videos):
    # frames 31..60 of the 30 fps video -> 30 images named 1..30
    meta = upload(videos["a_30fps"])
    job = run(meta["id"], start_frame=31, end_frame=60)
    assert job["result"]["total_images"] == 30
    full = upload(videos["a_30fps"])
    run(full["id"])
    a = client.get(f"/api/jobs/{meta['id']}/frames/1.png").content
    b = client.get(f"/api/jobs/{full['id']}/frames/31.png").content
    assert a == b  # range frame 1 is exactly source frame 31


def test_png_is_lossless_against_decoded_source(videos):
    """Compare extracted PNG pixels with an independent FFmpeg rawvideo decode."""
    import subprocess
    src = videos["c_120fps"]
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(src), "-frames:v", "1", "-f", "rawvideo",
                          "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
    meta = upload(src)
    run(meta["id"], end_frame=1)
    png = client.get(f"/api/jobs/{meta['id']}/frames/1.png").content
    with Image.open(io.BytesIO(png)) as im:
        assert im.convert("RGB").tobytes() == raw


def test_gallery_endpoints(videos):
    meta = upload(videos["b_60fps"])
    run(meta["id"], end_frame=10)
    r = client.get(f"/api/jobs/{meta['id']}/frames?offset=0&limit=5").json()
    assert r["total"] == 10 and r["names"] == ["1.png", "2.png", "3.png", "4.png", "5.png"]
    t = client.get(f"/api/jobs/{meta['id']}/thumbs/3.png")
    assert t.status_code == 200 and t.headers["content-type"] == "image/jpeg"
    assert client.get(f"/api/jobs/{meta['id']}/frames/../../etc/passwd").status_code == 404


def test_estimate(videos):
    meta = upload(videos["b_60fps"])
    e = client.post(f"/api/jobs/{meta['id']}/estimate", json={"format": "png"}).json()
    assert e["images"] == 600 and e["total_bytes"] > 0


def test_rejects_non_video(tmp_path):
    p = tmp_path / "notes.mp4"
    p.write_text("definitely not a video")
    r = client.put(f"/api/upload?filename={p.name}", content=p.read_bytes())
    assert r.status_code == 422


def test_download_blocked_until_done(videos):
    meta = upload(videos["a_30fps"])
    assert client.get(f"/api/jobs/{meta['id']}/download").status_code == 409
