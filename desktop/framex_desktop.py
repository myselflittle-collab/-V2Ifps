"""FrameX desktop launcher (Windows .exe entry point).

Starts the FrameX engine on a free localhost port and shows it in a native
window (Microsoft Edge WebView2 via pywebview). FFmpeg ships inside the app, so
nothing else needs to be installed.

Command-line options:
  --selftest   Extract a generated 30 fps clip with the bundled FFmpeg, verify it,
               write the report to selftest.log and exit (0 = pass). Used by CI.
  --browser    Open in the default web browser instead of an app window.
"""
from __future__ import annotations

import os
import shutil
import socket
import sys
import threading
import time
import traceback
from pathlib import Path

APP_NAME = "FrameX"
FROZEN = getattr(sys, "frozen", False)
BASE = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
DATA = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".local" / "share")) / APP_NAME
DATA.mkdir(parents=True, exist_ok=True)
LOG = DATA / "framex.log"

# A windowed .exe has no console: send output to a log file so nothing crashes on print.
if sys.stdout is None or sys.stderr is None:
    _log = open(LOG, "a", buffering=1, encoding="utf-8")
    sys.stdout = sys.stdout or _log
    sys.stderr = sys.stderr or _log


def _configure_environment() -> None:
    """Point the engine at the bundled FFmpeg, UI and a per-user workspace.
    Must run before ``app.main`` is imported (config is read at import time)."""
    exe = ".exe" if os.name == "nt" else ""
    bundled = BASE / "ffmpeg"
    for tool, var in (("ffmpeg", "FRAMEX_FFMPEG"), ("ffprobe", "FRAMEX_FFPROBE")):
        candidate = bundled / f"{tool}{exe}"
        if candidate.exists():
            os.environ.setdefault(var, str(candidate))
    os.environ.setdefault("FRAMEX_FRONTEND_DIST", str(BASE / "frontend_dist" if FROZEN else BASE / "frontend" / "dist"))
    os.environ.setdefault("FRAMEX_WORK_DIR", str(DATA / "workspace"))
    if not FROZEN:
        sys.path.insert(0, str(BASE / "backend"))


def _free_port(preferred: int = 8765) -> int:
    for port in (preferred, 0):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return s.getsockname()[1]
            except OSError:
                continue
    raise RuntimeError("No free port available")


class Server:
    def __init__(self) -> None:
        import uvicorn
        from app.main import app

        self.port = _free_port()
        config = uvicorn.Config(app, host="127.0.0.1", port=self.port, log_config=None,
                                access_log=False, lifespan="on")
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, daemon=True, name="framex-server")

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/"

    def start(self, timeout: float = 30) -> None:
        self.thread.start()
        deadline = time.time() + timeout
        while not self.server.started:
            if not self.thread.is_alive() or time.time() > deadline:
                raise RuntimeError("The FrameX engine did not start.")
            time.sleep(0.05)

    def stop(self) -> None:
        self.server.should_exit = True
        self.thread.join(timeout=5)
        shutil.rmtree(os.environ["FRAMEX_WORK_DIR"], ignore_errors=True)


def _message(text: str, title: str = APP_NAME) -> None:
    if os.name == "nt":
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, text, title, 0x40)
    else:
        print(f"{title}: {text}")


def selftest() -> int:
    """End-to-end check of the packaged app: generate, upload, extract, verify, download."""
    import json
    import subprocess
    import urllib.request
    import zipfile

    report = DATA / "selftest.log"
    lines: list[str] = []

    def log(msg: str) -> None:
        lines.append(msg)
        report.write_text("\n".join(lines), encoding="utf-8")

    server = Server()
    server.start()
    try:
        from app.config import FFMPEG, NO_WINDOW
        log(f"ffmpeg: {FFMPEG}")
        clip = DATA / "selftest_30fps.mp4"
        subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-i",
                        "testsrc2=size=1280x720:rate=30:duration=10", "-c:v", "libx264",
                        "-pix_fmt", "yuv420p", str(clip)], check=True, **NO_WINDOW)

        def call(method: str, path: str, body: bytes | None = None, ctype: str = "application/json"):
            req = urllib.request.Request(server.url.rstrip("/") + path, data=body, method=method,
                                         headers={"Content-Type": ctype})
            with urllib.request.urlopen(req, timeout=300) as r:
                return r.read()

        job = json.loads(call("PUT", "/api/upload?filename=selftest.mp4", clip.read_bytes(), "application/octet-stream"))
        info = job["info"]
        log(f"probe: {info['frame_count']} frames, {info['fps']} fps, {info['display_width']}x{info['display_height']}")
        call("POST", f"/api/jobs/{job['id']}/extract", json.dumps({"format": "png"}).encode())
        for _ in range(1200):
            st = json.loads(call("GET", f"/api/jobs/{job['id']}"))
            if st["status"] in ("done", "error", "cancelled"):
                break
            time.sleep(0.25)
        log(f"status: {st['status']} {st.get('error') or ''}")
        assert st["status"] == "done", st.get("error")
        res = st["result"]
        assert res["total_images"] == 300, res
        assert (res["width"], res["height"]) == (1280, 720), res
        zpath = DATA / "selftest.zip"
        zpath.write_bytes(call("GET", f"/api/jobs/{job['id']}/download"))
        with zipfile.ZipFile(zpath) as z:
            names = z.namelist()
            assert names == [f"{i}.png" for i in range(1, 301)], names[:3]
            assert z.testzip() is None
        call("GET", f"/api/jobs/{job['id']}/thumbs/150.png")
        index = call("GET", "/").decode("utf-8", "replace")
        assert "<div id=\"root\">" in index, "frontend not bundled"
        log("SELFTEST PASSED: 300 frames, 1280x720, 1.png..300.png, ZIP CRC ok, UI served")
        zpath.unlink(missing_ok=True)
        clip.unlink(missing_ok=True)
        return 0
    except Exception:
        log("SELFTEST FAILED\n" + traceback.format_exc())
        return 1
    finally:
        server.stop()


def main() -> int:
    _configure_environment()
    args = set(sys.argv[1:])
    if "--selftest" in args:
        return selftest()

    try:
        server = Server()
        server.start()
    except Exception as exc:
        traceback.print_exc()
        _message(f"FrameX could not start.\n\n{exc}\n\nDetails were written to {LOG}")
        return 1

    try:
        if "--browser" not in args:
            try:
                import webview
                webview.settings["ALLOW_DOWNLOADS"] = True
                webview.create_window(APP_NAME, server.url, width=1320, height=900,
                                      min_size=(420, 600), background_color="#1C1F25")
                webview.start(private_mode=False, storage_path=str(DATA / "webview"))
                return 0
            except Exception:
                traceback.print_exc()  # fall back to the browser below
        import webbrowser
        webbrowser.open(server.url)
        _message(f"FrameX is running in your web browser at\n{server.url}\n\nClick OK to quit FrameX.")
        return 0
    finally:
        server.stop()


if __name__ == "__main__":
    sys.exit(main())
