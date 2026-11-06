#!/usr/bin/env bash
# Build the frontend (if needed) and start FrameX on http://localhost:8000
set -euo pipefail
cd "$(dirname "$0")"
command -v ffmpeg >/dev/null || { echo "FFmpeg is not installed. See README."; exit 1; }
if [ ! -d frontend/dist ]; then
  (cd frontend && npm install && npm run build)
fi
cd backend
exec python3 -m uvicorn app.main:app --host "${HOST:-127.0.0.1}" --port "${PORT:-8000}"
