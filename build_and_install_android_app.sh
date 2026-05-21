#!/usr/bin/env bash
# Build debug APK and install on a connected device/emulator.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MOBILE_DIR="$SCRIPT_DIR/mobile"
ANDROID_DIR="$MOBILE_DIR/android"
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="local.media.audio.finder"

# Default Android SDK location (macOS). Override with ANDROID_HOME if needed.
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [[ -n "${ANDROID_HOME:-}" ]]; then
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
fi

cd "$MOBILE_DIR"

if ! command -v node >/dev/null 2>&1; then
  echo "node is required. Install Node.js 20+ first."
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required. Install Android SDK platform-tools (Android Studio)."
  exit 1
fi

echo "[1/4] npm install"
npm install

if [[ ! -d "$ANDROID_DIR" ]]; then
  echo "[2/4] First run: adding Android platform (npx cap add android)"
  npx cap add android
else
  echo "[2/4] Android project already exists"
fi

echo "[3/4] Sync web assets and Capacitor"
npm run cap:sync

if [[ ! -x "$ANDROID_DIR/gradlew" ]]; then
  echo "gradlew not found under $ANDROID_DIR"
  echo "Try: cd mobile && npx cap add android"
  exit 1
fi

echo "[4/4] Gradle assembleDebug"
(cd "$ANDROID_DIR" && ./gradlew assembleDebug)

if [[ ! -f "$APK_PATH" ]]; then
  echo "Build failed: APK not found at $APK_PATH"
  exit 1
fi

if ! adb devices 2>/dev/null | awk 'NR>1 && $2=="device" { found=1 } END { exit !found }'; then
  echo "No Android device/emulator connected."
  echo "Enable USB debugging, start an emulator, or set ANDROID_SERIAL."
  adb devices
  exit 1
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb -s "$ANDROID_SERIAL" install -r "$APK_PATH"
else
  adb install -r "$APK_PATH"
fi

echo "Installed: $APP_ID"
echo "APK: $APK_PATH"
