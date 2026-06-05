#!/usr/bin/env bash
# Install Android SDK (platform-tools, platform 35, build-tools) without Android Studio.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
COMPILE_SDK="${COMPILE_SDK:-35}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"

java_meets_requirement() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  local major
  major="$("$home/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
  [[ "${major:-0}" -ge 21 ]]
}

find_java_home() {
  local candidate prefix
  if [[ -n "${JAVA_HOME:-}" ]] && java_meets_requirement "$JAVA_HOME"; then
    echo "$JAVA_HOME"
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    prefix="$(brew --prefix openjdk@21 2>/dev/null || true)"
    if [[ -x "$prefix/libexec/openjdk.jdk/Contents/Home/bin/java" ]] \
      && java_meets_requirement "$prefix/libexec/openjdk.jdk/Contents/Home"; then
      echo "$prefix/libexec/openjdk.jdk/Contents/Home"
      return 0
    fi
    if java_meets_requirement "$prefix"; then
      echo "$prefix"
      return 0
    fi
  fi
  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$candidate" ]] && java_meets_requirement "$candidate"; then
      echo "$candidate"
      return 0
    fi
  fi
  return 1
}

JAVA_HOME_DETECTED="$(find_java_home || true)"
if [[ -z "$JAVA_HOME_DETECTED" ]]; then
  echo "JDK 21+ is required. Run: brew install openjdk@21"
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_DETECTED"
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required for this setup script."
  echo "Alternatively install Android Studio and use ~/Library/Android/sdk"
  exit 1
fi

if ! brew list --cask android-commandlinetools >/dev/null 2>&1; then
  echo "Installing android-commandlinetools (Homebrew cask)..."
  brew install --cask android-commandlinetools
fi

CMDLINE_ROOT="$(brew --prefix)/share/android-commandlinetools"
SDKMANAGER="$CMDLINE_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "sdkmanager not found at $SDKMANAGER"
  exit 1
fi

mkdir -p "$SDK_ROOT"

echo "Installing SDK packages into: $SDK_ROOT"
echo "  - platform-tools"
echo "  - platforms;android-$COMPILE_SDK"
echo "  - build-tools;$BUILD_TOOLS"

yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-${COMPILE_SDK}" \
  "build-tools;${BUILD_TOOLS}"

if [[ ! -d "$SDK_ROOT/platform-tools" ]]; then
  echo "SDK setup failed: $SDK_ROOT/platform-tools missing"
  exit 1
fi

echo ""
echo "Done. Android SDK is ready at:"
echo "  $SDK_ROOT"
echo ""
echo "Add to ~/.zshrc (recommended):"
echo "  export ANDROID_HOME=\"$SDK_ROOT\""
echo "  export PATH=\"\$ANDROID_HOME/platform-tools:\$PATH\""
