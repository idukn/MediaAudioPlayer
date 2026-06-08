#!/usr/bin/env bash
# Update media-server code in Debian VM and restart (run inside Debian Terminal).
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/media-audio-finder-server}"
REPO_ROOT="${REPO_ROOT:-/mnt/shared/Download/yt_audio_app}"
if [[ ! -f "$REPO_ROOT/shared/media-server/index.js" ]]; then
  REPO_ROOT="/mnt/shared/0/Download/yt_audio_app"
fi
SRC="$REPO_ROOT/shared/media-server/index.js"

if [[ ! -f "$SRC" ]]; then
  echo "Error: $SRC not found. Run push-debian-setup-to-android.sh on Mac first."
  exit 1
fi
if [[ ! -d "$INSTALL_DIR" ]]; then
  echo "Error: $INSTALL_DIR missing. Run setup-debian-media-server.sh first."
  exit 1
fi

cp "$SRC" "$INSTALL_DIR/index.js"
bash "$REPO_ROOT/scripts/setup-yt-dlp-ejs.sh"
systemctl --user restart media-audio-finder
sleep 2
curl -sf "http://127.0.0.1:8765/health"
echo ""
echo "Media server updated."
