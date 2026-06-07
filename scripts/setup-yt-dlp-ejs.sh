#!/usr/bin/env bash
# Enable yt-dlp YouTube downloads (requires Node.js + EJS challenge solver).
set -euo pipefail

if ! command -v node >/dev/null 2>&1; then
  echo "Installing nodejs (required for yt-dlp YouTube) ..."
  sudo apt-get install -y nodejs
fi

YTDLP_CFG="${HOME}/.config/yt-dlp"
mkdir -p "$YTDLP_CFG"
cat >"$YTDLP_CFG/config" <<'EOF'
--js-runtimes node
--remote-components ejs:github
EOF
echo "Wrote $YTDLP_CFG/config"

if command -v yt-dlp >/dev/null 2>&1; then
  echo "Testing yt-dlp YouTube (simulate) ..."
  if yt-dlp --simulate --no-warnings "https://www.youtube.com/watch?v=jNQXAC9IVRw" >/dev/null 2>&1; then
    echo "yt-dlp YouTube test OK"
  else
    echo "WARNING: yt-dlp YouTube test failed."
    echo "  Try: yt-dlp --verbose --simulate 'https://www.youtube.com/watch?v=jNQXAC9IVRw'"
  fi
fi
