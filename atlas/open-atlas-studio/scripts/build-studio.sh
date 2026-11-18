#!/usr/bin/env bash
# Compile the Studio Builder into the production artifact directory.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/apps/studio"
DIST_DIR="${OPEN_ATLAS_DIST_DIR:-$ROOT_DIR/dist}"

command -v clojure >/dev/null || { echo "clojure CLI is required" >&2; exit 1; }
command -v npm >/dev/null || { echo "npm is required to compile the Studio graph UI" >&2; exit 1; }
(
  cd "$APP_DIR"
  [ -d node_modules ] || npm install --no-audit --no-fund
  # Keep the production bundle away from `watch builder`, which owns
  # resources/public/js during development and may rewrite it mid-package.
  PACKAGE_UI_DIR="$APP_DIR/.package-ui"
  PACKAGE_UI_CACHE="$APP_DIR/.shadow-cljs/builds/builder-package"
  cleanup_package_ui() {
    rm -rf "$PACKAGE_UI_DIR" "$PACKAGE_UI_CACHE"
  }
  trap cleanup_package_ui EXIT
  rm -rf "$PACKAGE_UI_DIR" "$PACKAGE_UI_CACHE"
  npx shadow-cljs release builder-package
  OPEN_ATLAS_STUDIO_PACKAGE_UI_DIR="$PACKAGE_UI_DIR" clojure -T:build uber
  # The JDBC drivers ship BESIDE the jar (8.13). The package still carries all
  # of them, so an unpacked release connects out of the box; what changed is
  # that an operator can swap or drop one without rebuilding.
  clojure -T:build drivers
)
mkdir -p "$DIST_DIR/studio/drivers"
install -m 0644 "$APP_DIR/target/open-atlas-studio.jar" "$DIST_DIR/studio/open-atlas-studio.jar"
rm -f "$DIST_DIR"/studio/drivers/*.jar
install -m 0644 "$APP_DIR"/target/drivers/*.jar "$DIST_DIR/studio/drivers/"
echo "Built: $DIST_DIR/studio/open-atlas-studio.jar"
echo "       $DIST_DIR/studio/drivers/ ($(ls "$DIST_DIR/studio/drivers" | wc -l | tr -d ' ') jars)"
