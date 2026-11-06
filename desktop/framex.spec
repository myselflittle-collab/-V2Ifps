# PyInstaller recipe for the FrameX Windows app.
# Build (from the repository root, on Windows):
#   pyinstaller desktop/framex.spec --noconfirm
# Expects the built UI in frontend/dist and FFmpeg's bin folder in desktop/ffmpeg_bin.
import os
from PyInstaller.utils.hooks import collect_submodules

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
FFMPEG_DIR = os.path.join(SPECPATH, "ffmpeg_bin")

datas = [(os.path.join(ROOT, "frontend", "dist"), "frontend_dist")]
for name in os.listdir(FFMPEG_DIR):
    if name.lower().endswith((".exe", ".dll")) and not name.lower().startswith("ffplay"):
        datas.append((os.path.join(FFMPEG_DIR, name), "ffmpeg"))

hidden = collect_submodules("app") + collect_submodules("uvicorn") + ["webview"]

a = Analysis(
    [os.path.join(SPECPATH, "framex_desktop.py")],
    pathex=[os.path.join(ROOT, "backend")],
    datas=datas,
    hiddenimports=hidden,
    excludes=["tkinter", "pytest", "IPython", "matplotlib", "numpy"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="FrameX",
    icon=os.path.join(SPECPATH, "assets", "framex.ico"),
    console=False,
    version=os.path.join(SPECPATH, "version_info.txt"),
)
coll = COLLECT(exe, a.binaries, a.datas, name="FrameX")
