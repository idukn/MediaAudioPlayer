#!/usr/bin/env bash
# Build debug APK and install on a connected device/emulator.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MOBILE_DIR="$SCRIPT_DIR/mobile"
ANDROID_DIR="$MOBILE_DIR/android"
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="local.media.audio.finder"

java_meets_capacitor_requirement() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  local major
  major="$("$home/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  [[ "${major:-0}" -ge 21 ]]
}

find_java_home() {
  local candidate

  if [[ -n "${JAVA_HOME:-}" ]] && java_meets_capacitor_requirement "$JAVA_HOME"; then
    echo "$JAVA_HOME"
    return 0
  fi

  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if java_meets_capacitor_requirement "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done

  if command -v brew >/dev/null 2>&1; then
    for formula in openjdk@21 openjdk; do
      candidate="$(brew --prefix "$formula" 2>/dev/null || true)"
      if [[ -n "$candidate" ]]; then
        if java_meets_capacitor_requirement "$candidate"; then
          echo "$candidate"
          return 0
        fi
        if [[ -x "$candidate/libexec/openjdk.jdk/Contents/Home/bin/java" ]] \
          && java_meets_capacitor_requirement "$candidate/libexec/openjdk.jdk/Contents/Home"; then
          echo "$candidate/libexec/openjdk.jdk/Contents/Home"
          return 0
        fi
      fi
    done
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$candidate" ]] && java_meets_capacitor_requirement "$candidate"; then
      echo "$candidate"
      return 0
    fi
  fi

  return 1
}

JAVA_HOME_DETECTED="$(find_java_home || true)"
if [[ -z "$JAVA_HOME_DETECTED" ]]; then
  echo "Java 21+ is required (Capacitor 7 / @capacitor/filesystem)."
  echo ""
  echo "Install JDK 21, then re-run:"
  echo "  brew install openjdk@21"
  echo "  export JAVA_HOME=\"\$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home\""
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_DETECTED"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using JAVA_HOME=$JAVA_HOME"

find_android_sdk() {
  local candidate brew_prefix
  for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$HOME/Library/Android/sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platform-tools" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if command -v brew >/dev/null 2>&1; then
    brew_prefix="$(brew --prefix 2>/dev/null || true)"
    candidate="$brew_prefix/share/android-commandlinetools"
    if [[ -d "$candidate/platform-tools" ]]; then
      echo "$candidate"
      return 0
    fi
  fi
  return 1
}

write_local_properties() {
  local sdk_dir="$1"
  local props="$ANDROID_DIR/local.properties"
  sdk_dir="${sdk_dir//\\/\\\\}"
  printf 'sdk.dir=%s\n' "$sdk_dir" >"$props"
  echo "Wrote $props (sdk.dir=$sdk_dir)"
}

ANDROID_SDK="$(find_android_sdk || true)"
if [[ -z "$ANDROID_SDK" ]]; then
  SETUP_SDK_SCRIPT="$SCRIPT_DIR/scripts/setup-android-sdk.sh"
  if [[ -x "$SETUP_SDK_SCRIPT" ]]; then
    echo "Android SDK not found. Running scripts/setup-android-sdk.sh ..."
    "$SETUP_SDK_SCRIPT"
    ANDROID_SDK="$(find_android_sdk || true)"
  fi
fi
if [[ -z "$ANDROID_SDK" ]]; then
  echo "Android SDK not found."
  echo ""
  echo "Option A (CLI, no Android Studio):"
  echo "  ./scripts/setup-android-sdk.sh"
  echo "  ./build_and_install_android_app.sh"
  echo ""
  echo "Option B (Android Studio):"
  echo "  Install Android Studio > SDK Manager"
  echo "  export ANDROID_HOME=\"\$HOME/Library/Android/sdk\""
  exit 1
fi
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

SYNCTHING_SO="$MOBILE_DIR/plugins/media-audio-finder/android/src/main/jniLibs/arm64-v8a/libsyncthing.so"
APP_JNI_DIR="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"
APP_SYNCTHING_SO="$APP_JNI_DIR/libsyncthing.so"

if [[ ! -f "$SYNCTHING_SO" ]]; then
  echo "Fetching Android Syncthing binary (first time)..."
  "$SCRIPT_DIR/scripts/build-syncthing-android.sh"
fi

if [[ ! -f "$SYNCTHING_SO" ]]; then
  echo "Error: Syncthing binary missing at $SYNCTHING_SO"
  echo "Run: ./scripts/build-syncthing-android.sh"
  exit 1
fi

bundle_native_libs() {
  local plugin_jni="$MOBILE_DIR/plugins/media-audio-finder/android/src/main/jniLibs/arm64-v8a"
  mkdir -p "$APP_JNI_DIR" "$plugin_jni"
  shopt -s nullglob
  for lib in "$plugin_jni"/*.so; do
    base="$(basename "$lib")"
    [[ "$base" == "libsyncthing.so" ]] && continue
    rm -f "$lib"
  done
  for lib in "$APP_JNI_DIR"/*.so; do
    rm -f "$lib"
  done
  shopt -u nullglob
  if [[ "$SYNCTHING_SO" != "$plugin_jni/libsyncthing.so" ]]; then
    cp "$SYNCTHING_SO" "$plugin_jni/libsyncthing.so"
  fi
  cp "$SYNCTHING_SO" "$APP_SYNCTHING_SO"
  chmod +x "$APP_SYNCTHING_SO" "$plugin_jni/libsyncthing.so"
  echo "Bundled libsyncthing.so only (removed legacy ffmpeg jniLibs)"
}

if ! command -v node >/dev/null 2>&1; then
  echo "node is required. Install Node.js 20+ first."
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is required. Install Android SDK platform-tools (Android Studio)."
  exit 1
fi

cd "$MOBILE_DIR"

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

bundle_native_libs

if [[ ! -x "$ANDROID_DIR/gradlew" ]]; then
  echo "gradlew not found under $ANDROID_DIR"
  echo "Try: cd mobile && npx cap add android"
  exit 1
fi

write_local_properties "$ANDROID_HOME"

echo "[4/4] Gradle assembleDebug"
(cd "$ANDROID_DIR" && ./gradlew assembleDebug)

if [[ ! -f "$APK_PATH" ]]; then
  echo "Build failed: APK not found at $APK_PATH"
  exit 1
fi

apk_contains_native_libs() {
  local apk="$1"
  unzip -l "$apk" 2>/dev/null | grep -F 'lib/arm64-v8a/libsyncthing.so' >/dev/null
}

if ! apk_contains_native_libs "$APK_PATH"; then
  echo "Build failed: APK is missing libsyncthing.so."
  echo "Re-run: ./scripts/build-syncthing-android.sh && ./build_and_install_android_app.sh"
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
