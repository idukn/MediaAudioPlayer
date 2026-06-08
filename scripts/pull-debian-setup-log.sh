#!/usr/bin/env bash
# Pull Debian setup logs from Android Download (written by setup-debian-media-server.sh).
set -euo pipefail

DEST="${1:-./yt_audio_setup.log}"
for remote in \
  /storage/emulated/0/Download/yt_audio_setup.log \
  /sdcard/Download/yt_audio_setup.log; do
  if adb pull "$remote" "$DEST" 2>/dev/null; then
    echo "Pulled: $remote -> $DEST"
    tail -80 "$DEST"
    exit 0
  fi
done
echo "Log not found on device. Run setup in Debian Terminal first."
exit 1
