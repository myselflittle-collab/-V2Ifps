@echo off
REM Build the frontend (if needed) and start FrameX on http://localhost:8000
cd /d "%~dp0"
where ffmpeg >nul 2>nul || (echo FFmpeg is not installed. See README. & exit /b 1)
if not exist frontend\dist (
  pushd frontend && call npm install && call npm run build && popd
)
cd backend
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
