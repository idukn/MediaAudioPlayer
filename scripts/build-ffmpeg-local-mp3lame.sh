#!/usr/bin/env bash
# Rebuild /usr/local/bin/ffmpeg with libmp3lame (and existing codecs).
# Requires: Xcode CLT, Homebrew deps, sudo for "make install".
set -euo pipefail

PREFIX="/usr/local"
FFMPEG_SRC="${FFMPEG_SRC:-$HOME/src/ffmpeg}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

echo "==> Installing build dependencies via Homebrew..."
brew install lame x264 x265 snappy pkg-config nasm

BREW_PREFIX="$(brew --prefix)"
LAME_PREFIX="$(brew --prefix lame)"
X264_PREFIX="$(brew --prefix x264)"
X265_PREFIX="$(brew --prefix x265)"
SNAPPY_PREFIX="$(brew --prefix snappy)"

export PKG_CONFIG_PATH="${LAME_PREFIX}/lib/pkgconfig:${X264_PREFIX}/lib/pkgconfig:${X265_PREFIX}/lib/pkgconfig:${SNAPPY_PREFIX}/lib/pkgconfig:${BREW_PREFIX}/lib/pkgconfig"

mkdir -p "$(dirname "$FFMPEG_SRC")"
if [[ ! -d "${FFMPEG_SRC}/.git" ]]; then
  echo "==> Cloning FFmpeg source into ${FFMPEG_SRC}..."
  git clone --depth 1 https://git.ffmpeg.org/ffmpeg.git "$FFMPEG_SRC"
else
  echo "==> Updating FFmpeg source..."
  git -C "$FFMPEG_SRC" pull --ff-only || true
fi

cd "$FFMPEG_SRC"

echo "==> Configuring (prefix=${PREFIX}, +libmp3lame)..."
make distclean 2>/dev/null || true

./configure \
  --prefix="${PREFIX}" \
  --enable-gpl \
  --enable-libx264 \
  --enable-libx265 \
  --enable-libsnappy \
  --enable-libmp3lame \
  --enable-videotoolbox \
  --enable-neon \
  --extra-cflags="-I${BREW_PREFIX}/include -I${LAME_PREFIX}/include" \
  --extra-ldflags="-L${BREW_PREFIX}/lib -L${LAME_PREFIX}/lib"

echo "==> Building (${JOBS} jobs)..."
make -j"${JOBS}"

echo "==> Installing to ${PREFIX} (sudo required)..."
sudo make install

echo "==> Verifying libmp3lame encoder..."
if "${PREFIX}/bin/ffmpeg" -hide_banner -encoders 2>&1 | grep -q libmp3lame; then
  echo "OK: libmp3lame is available."
  "${PREFIX}/bin/ffmpeg" -version | head -3
else
  echo "ERROR: libmp3lame not found after install." >&2
  exit 1
fi
