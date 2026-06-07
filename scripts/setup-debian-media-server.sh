#!/usr/bin/env bash
# Install media-audio-finder HTTP server into Debian VM (Android Linux development environment).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_SRC="$REPO_ROOT/shared/media-server"
INSTALL_DIR="${INSTALL_DIR:-$HOME/media-audio-finder-server}"
SERVICE_NAME="media-audio-finder.service"
SMOKE_LOG="$INSTALL_DIR/smoke.log"

# Android virtiofs: /mnt/shared/Download (Pixel) or /mnt/shared/0/Download
detect_shared_download() {
  if [[ -d /mnt/shared/Download ]]; then
    echo /mnt/shared/Download
  elif [[ -d /mnt/shared/0/Download ]]; then
    echo /mnt/shared/0/Download
  else
    echo ""
  fi
}

detect_library_root() {
  local shared_dl
  shared_dl="$(detect_shared_download)"
  local candidates=()
  if [[ -n "$shared_dl" ]]; then
    candidates+=(
      "/mnt/shared/0/Android/data/local.media.audio.finder/files/library"
      "/mnt/shared/Android/data/local.media.audio.finder/files/library"
      "${shared_dl%/}/../Android/data/local.media.audio.finder/files/library"
    )
  fi
  candidates+=("$HOME/library")
  local c dir
  for c in "${candidates[@]}"; do
    dir="$(cd "$(dirname "$c")" 2>/dev/null && pwd)/$(basename "$c")" || true
    [[ -z "$dir" ]] && dir="$c"
    if mkdir -p "$dir" 2>/dev/null; then
      echo "$dir"
      return 0
    fi
  done
  echo "$HOME/library"
}

SETUP_LOG="$(detect_shared_download)/yt_audio_setup.log"
if [[ -z "$SETUP_LOG" || "$SETUP_LOG" == "/yt_audio_setup.log" ]]; then
  SETUP_LOG="$HOME/yt_audio_setup.log"
fi

exec > >(tee -a "$SETUP_LOG") 2>&1
echo "=== setup $(date -Iseconds) ==="
echo "Log file: $SETUP_LOG  (Mac: adb pull /storage/emulated/0/Download/yt_audio_setup.log)"

if [[ ! -f "$SERVER_SRC/index.js" ]]; then
  echo "Error: shared/media-server not found at $SERVER_SRC"
  echo "Expected repo at: $(detect_shared_download)/yt_audio_app"
  exit 1
fi

find_node() {
  command -v node 2>/dev/null || command -v nodejs 2>/dev/null || true
}

echo "Installing media server to $INSTALL_DIR ..."
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp -a "$SERVER_SRC/." "$INSTALL_DIR/"
rm -rf "$INSTALL_DIR/node_modules" "$INSTALL_DIR/package-lock.json"

cd "$INSTALL_DIR"

if ! find_node >/dev/null; then
  echo "Installing nodejs ..."
  sudo apt-get install -y nodejs npm || sudo apt-get install -y nodejs
fi

NODE_BIN="$(find_node)"
if [[ -z "$NODE_BIN" ]]; then
  echo "Error: node not found after install"
  exit 1
fi
echo "Using node: $NODE_BIN ($("$NODE_BIN" -v))"

echo "Installing npm dependencies (linux arm64) ..."
if ! npm install --omit=dev 2>&1; then
  echo "npm install failed"
  exit 1
fi

echo "Verifying express ..."
if ! "$NODE_BIN" -e "require('express'); console.log('express ok')" 2>&1; then
  echo "express failed to load"
  exit 1
fi

if ! command -v yt-dlp >/dev/null 2>&1 || ! command -v ffmpeg >/dev/null 2>&1; then
  echo "Installing yt-dlp and ffmpeg ..."
  sudo apt-get install -y yt-dlp ffmpeg
fi

echo "Configuring yt-dlp for YouTube (Node.js + EJS) ..."
bash "$SCRIPT_DIR/setup-yt-dlp-ejs.sh"

LIBRARY_ROOT="${LIBRARY_ROOT:-$(detect_library_root)}"
mkdir -p "$LIBRARY_ROOT"
echo "Library root: $LIBRARY_ROOT"

ENV_FILE="$INSTALL_DIR/.env"
cat >"$ENV_FILE" <<EOF
MEDIA_SERVER_PORT=8765
MEDIA_SERVER_HOST=0.0.0.0
LIBRARY_ROOT=$LIBRARY_ROOT
EOF
echo "Wrote $ENV_FILE"

run_smoke_test() {
  : >"$SMOKE_LOG"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  "$NODE_BIN" bin/serve.js >>"$SMOKE_LOG" 2>&1 &
  SMOKE_PID=$!
  local i code body
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    if curl -sf "http://127.0.0.1:8765/health" >/tmp/health.json 2>/dev/null; then
      body="$(cat /tmp/health.json)"
      echo "Smoke test OK: $body"
      kill "$SMOKE_PID" 2>/dev/null || true
      wait "$SMOKE_PID" 2>/dev/null || true
      return 0
    fi
    if ! kill -0 "$SMOKE_PID" 2>/dev/null; then
      echo "Smoke test FAILED: node process exited early"
      echo "--- smoke.log ---"
      cat "$SMOKE_LOG"
      return 1
    fi
  done
  echo "Smoke test FAILED: no response on :8765 after 10s"
  echo "--- smoke.log ---"
  cat "$SMOKE_LOG"
  echo "--- ss / netstat ---"
  (ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null || true) | grep 8765 || true
  kill "$SMOKE_PID" 2>/dev/null || true
  wait "$SMOKE_PID" 2>/dev/null || true
  return 1
}

echo "Smoke test ..."
if ! run_smoke_test; then
  echo "Setup aborted. Pull log on Mac:"
  echo "  adb pull /storage/emulated/0/Download/yt_audio_setup.log"
  exit 1
fi

SYSTEMD_DIR="$HOME/.config/systemd/user"
mkdir -p "$SYSTEMD_DIR"
cat >"$SYSTEMD_DIR/$SERVICE_NAME" <<EOF
[Unit]
Description=Media Audio Finder HTTP server
After=network.target

[Service]
Type=simple
EnvironmentFile=$ENV_FILE
WorkingDirectory=$INSTALL_DIR
ExecStart=$NODE_BIN $INSTALL_DIR/bin/serve.js
StandardOutput=append:$INSTALL_DIR/server.log
StandardError=append:$INSTALL_DIR/server.log
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

if command -v loginctl >/dev/null 2>&1; then
  sudo loginctl enable-linger "$(whoami)" 2>/dev/null || loginctl enable-linger "$(whoami)" 2>/dev/null || true
fi

systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME"
systemctl --user restart "$SERVICE_NAME"
sleep 2

if systemctl --user is-active --quiet "$SERVICE_NAME"; then
  echo "Service is active."
else
  echo "Service failed:"
  systemctl --user status "$SERVICE_NAME" --no-pager || true
  journalctl --user -u "$SERVICE_NAME" -n 40 --no-pager || true
  [[ -f "$INSTALL_DIR/server.log" ]] && cat "$INSTALL_DIR/server.log"
  exit 1
fi

echo ""
echo "VM:      curl http://127.0.0.1:8765/health"
echo "Android: http://127.0.0.1:8765/health (requires port 8765 forwarding)"
echo "Library: $LIBRARY_ROOT"
echo ""
echo "If VM curl works but Android Chrome shows 'connection refused':"
echo "  1. Terminal app Settings -> add listening port 8765"
echo "  2. Restart server: systemctl --user restart media-audio-finder"
echo "  3. Accept the port-forward popup when the server binds"
echo "  4. Keep Terminal (Debian VM) running"
echo "  5. From Mac: adb shell ss -ltn | grep 8765  (must show LISTEN on 127.0.0.1:8765)"
