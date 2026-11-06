import os
import subprocess
import tempfile
from pathlib import Path

import pytest

_WORK = tempfile.mkdtemp(prefix="framex_test_work_")
os.environ["FRAMEX_WORK_DIR"] = _WORK
os.environ["FRAMEX_FRONTEND_DIST"] = os.path.join(_WORK, "no-frontend")


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", *args], check=True)


def count_decoded(path: Path) -> int:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
         "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True, check=True).stdout.strip()
    return int(out.split(",")[0])


@pytest.fixture(scope="session")
def videos(tmp_path_factory):
    d = tmp_path_factory.mktemp("videos")
    v = {}

    def cfr(name, fps, seconds, size="1920x1080", codec=("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"), ext="mp4"):
        p = d / f"{name}.{ext}"
        ffmpeg("-f", "lavfi", "-i", f"testsrc2=size={size}:rate={fps}:duration={seconds}", *codec, str(p))
        v[name] = p

    cfr("a_30fps", 30, 10)                       # Test A
    cfr("b_60fps", 60, 10, size="1280x720")      # Test B
    cfr("c_120fps", 120, 10, size="640x360")     # Test C
    cfr("e_4k", 30, 1, size="3840x2160")         # Test E (short, to keep CI fast)
    cfr("f_long", 25, 180, size="320x180")       # Test F (3 min, 4500 frames)
    cfr("odd_23976", "24000/1001", 5, size="640x360")
    cfr("webm_vp9", 24, 3, size="640x360", codec=("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8"), ext="webm")

    # Test D: variable frame rate – keep a pseudo-random subset of 60 fps frames
    # and keep their original timestamps, so intervals between frames vary.
    p = d / "d_vfr.mp4"
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=60:duration=10",
           "-vf", "select='not(eq(mod(n\\,7)\\,3))*not(eq(mod(n\\,5)\\,1))'",
           "-fps_mode", "vfr", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", str(p))
    v["d_vfr"] = p

    # Rotation metadata: stored 640x360, displayed 360x640
    p = d / "rotated.mp4"
    ffmpeg("-display_rotation", "90", "-i", str(v["odd_23976"]), "-c", "copy", str(p))
    v["rotated"] = p
    return v
