#!/usr/bin/env bash
set -euo pipefail

APP_NAME="Media Audio Finder.app"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$SCRIPT_DIR/electron"
BUILT_APP_PATH=""

cd "$APP_DIR"

if ! command -v node >/dev/null 2>&1; then
  echo "node is required. Install it first: brew install node"
  exit 1
fi

rm -rf dist
npm install
npm run build:app

if [[ -d "$APP_DIR/dist/mac-arm64/$APP_NAME" ]]; then
  BUILT_APP_PATH="$APP_DIR/dist/mac-arm64/$APP_NAME"
elif [[ -d "$APP_DIR/dist/mac/$APP_NAME" ]]; then
  BUILT_APP_PATH="$APP_DIR/dist/mac/$APP_NAME"
else
  echo "Build failed: app bundle not found under $APP_DIR/dist"
  exit 1
fi

rm -rf "/Applications/$APP_NAME"
cp -R "$BUILT_APP_PATH" /Applications/

echo "Installed: /Applications/$APP_NAME"
