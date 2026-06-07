#!/usr/bin/env bash
# Push only the files needed for setup-debian-media-server.sh (no dist/, node_modules/, etc.)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REMOTE="${ANDROID_PUSH_DIR:-/storage/emulated/0/Download/yt_audio_app}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools."
  exit 1
fi

if ! adb devices 2>/dev/null | awk 'NR>1 && $2=="device" { found=1 } END { exit !found }'; then
  echo "No Android device connected."
  adb devices
  exit 1
fi

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

mkdir -p "$STAGING/yt_audio_app/shared" "$STAGING/yt_audio_app/scripts/debian"
cp -R "$REPO_ROOT/shared/media-server" "$STAGING/yt_audio_app/shared/"
rm -rf "$STAGING/yt_audio_app/shared/media-server/node_modules"
cp "$REPO_ROOT/scripts/setup-debian-media-server.sh" "$STAGING/yt_audio_app/scripts/"
cp "$REPO_ROOT/scripts/update-vm-media-server.sh" "$STAGING/yt_audio_app/scripts/"
cp "$REPO_ROOT/scripts/setup-yt-dlp-ejs.sh" "$STAGING/yt_audio_app/scripts/"
cp "$REPO_ROOT/scripts/debian/media-audio-finder.service" "$STAGING/yt_audio_app/scripts/debian/"
chmod +x "$STAGING/yt_audio_app/scripts/setup-debian-media-server.sh"
chmod +x "$STAGING/yt_audio_app/scripts/update-vm-media-server.sh"
chmod +x "$STAGING/yt_audio_app/scripts/setup-yt-dlp-ejs.sh"

ARCHIVE="$STAGING/yt_audio_app-setup.tar.gz"
tar -czf "$ARCHIVE" -C "$STAGING" yt_audio_app

echo "Pushing minimal setup bundle to $REMOTE ..."
adb shell "rm -rf '$REMOTE' && mkdir -p '$(dirname "$REMOTE")'"
adb push "$ARCHIVE" "/storage/emulated/0/Download/yt_audio_app-setup.tar.gz"
adb shell "cd /storage/emulated/0/Download && tar xzf yt_audio_app-setup.tar.gz && rm yt_audio_app-setup.tar.gz && chmod +x yt_audio_app/scripts/setup-debian-media-server.sh yt_audio_app/scripts/update-vm-media-server.sh yt_audio_app/scripts/setup-yt-dlp-ejs.sh"

echo ""
echo "Done. In Debian Terminal:"
echo "  ls /mnt/shared"
echo "  cd /mnt/shared/Download/yt_audio_app"
echo "  # or: cd /mnt/shared/0/Download/yt_audio_app"
echo "  ./scripts/setup-debian-media-server.sh"
echo "  # 更新のみ: ./scripts/update-vm-media-server.sh"
