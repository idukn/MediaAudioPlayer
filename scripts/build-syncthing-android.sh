#!/usr/bin/env bash
# Fetch Android arm64 Syncthing binary (Termux build) and place in jniLibs as libsyncthing.so.
# Termux の VERNEED は Android Bionic と不一致のため、objcopy + 動的タグ除去が必要。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_DIR="$ROOT_DIR/mobile/plugins/media-audio-finder/android/src/main/jniLibs/arm64-v8a"
OUTPUT_NAME="libsyncthing.so"
TERMUX_VERSION="${TERMUX_SYNCTHING_VERSION:-2.1.0}"
DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/s/syncthing/syncthing_${TERMUX_VERSION}_aarch64.deb"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/syncthing-android-build.XXXXXX")"
STRIP_TAGS="$SCRIPT_DIR/strip-elf-version-tags.py"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

resolve_objcopy() {
  if command -v llvm-objcopy >/dev/null 2>&1; then
    command -v llvm-objcopy
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    local brew_llvm
    brew_llvm="$(brew --prefix llvm 2>/dev/null)/bin/llvm-objcopy"
    if [[ -x "$brew_llvm" ]]; then
      echo "$brew_llvm"
      return 0
    fi
  fi
  return 1
}

resolve_readelf() {
  if command -v llvm-readelf >/dev/null 2>&1; then
    command -v llvm-readelf
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    local brew_llvm
    brew_llvm="$(brew --prefix llvm 2>/dev/null)/bin/llvm-readelf"
    if [[ -x "$brew_llvm" ]]; then
      echo "$brew_llvm"
      return 0
    fi
  fi
  return 1
}

finalize_syncthing_binary() {
  local out="$1"
  local objcopy readelf dyn
  objcopy="$(resolve_objcopy || true)"
  if [[ -z "$objcopy" ]]; then
    echo "Warning: llvm-objcopy not found; Syncthing may fail VERNEED on Android. Install: brew install llvm"
    return 0
  fi
  "$objcopy" \
    --remove-section .gnu.version \
    --remove-section .gnu.version_r \
    "$out"
  python3 "$STRIP_TAGS" "$out"

  readelf="$(resolve_readelf || true)"
  if [[ -n "$readelf" ]]; then
    dyn="$("$readelf" -d "$out" 2>/dev/null || true)"
    if [[ "$dyn" == *"(VERNEED)"* ]] || [[ "$dyn" == *"(VERSYM)"* ]]; then
      echo "verify failed: $out still has VERNEED/VERSYM dynamic tags"
      return 1
    fi
  fi
  echo "Android linker finalize OK: $out"
}

mkdir -p "$JNI_DIR"

echo "Downloading Termux syncthing ${TERMUX_VERSION} (aarch64)..."
curl -fsSL -o "$WORK_DIR/syncthing.deb" "$DEB_URL"

echo "Extracting binary..."
(
  cd "$WORK_DIR"
  if command -v bsdtar >/dev/null 2>&1; then
    bsdtar -xf syncthing.deb
    bsdtar -xf data.tar.xz
  else
    ar x syncthing.deb
    tar -xf data.tar.xz
  fi
)

BIN_PATH="$WORK_DIR/data/data/com.termux/files/usr/bin/syncthing"
if [[ ! -f "$BIN_PATH" ]]; then
  echo "Syncthing binary not found in package (expected at data/.../usr/bin/syncthing)"
  exit 1
fi

cp "$BIN_PATH" "$JNI_DIR/$OUTPUT_NAME"
chmod +x "$JNI_DIR/$OUTPUT_NAME"
finalize_syncthing_binary "$JNI_DIR/$OUTPUT_NAME"
echo "Installed: $JNI_DIR/$OUTPUT_NAME"
ls -lh "$JNI_DIR/$OUTPUT_NAME"

APP_JNI_DIR="$ROOT_DIR/mobile/android/app/src/main/jniLibs/arm64-v8a"
if [[ -d "$ROOT_DIR/mobile/android" ]]; then
  mkdir -p "$APP_JNI_DIR"
  cp "$JNI_DIR/$OUTPUT_NAME" "$APP_JNI_DIR/$OUTPUT_NAME"
  chmod +x "$APP_JNI_DIR/$OUTPUT_NAME"
  echo "Installed: $APP_JNI_DIR/$OUTPUT_NAME"
fi

echo "Done. Rebuild APK: ./build_and_install_android_app.sh"
