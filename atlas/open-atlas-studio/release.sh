#!/usr/bin/env bash
# release.sh for an app repository (copy to the app repo root, set APP; repo-split §7.1).
# It only pins the release-tools version and calls the shared implementation; upgrading the tools
# is a one-line change to tools.lock.
set -euo pipefail
APP=studio   # studio | workbench | dossier
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_VERSION="$(cat "$ROOT/tools.lock")"                      # e.g. 1.0.0
TOOLS="${OPEN_ATLAS_RELEASE_TOOLS:-$HOME/.cache/open-atlas-release/$TOOLS_VERSION}"
# HTTPS, not SSH (SSH to GitHub is not available everywhere). The repo is private: an authenticated gh
# clones with its token; otherwise git uses whatever HTTPS credentials are configured.
REPO_URL="https://github.com/gaojie197710-tech/open-atlas-release.git"
if [ ! -d "$TOOLS" ]; then
  mkdir -p "$(dirname "$TOOLS")"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh repo clone gaojie197710-tech/open-atlas-release "$TOOLS" -- -q --depth 1 --branch "release-tools-$TOOLS_VERSION"
  else
    git clone -q --depth 1 --branch "release-tools-$TOOLS_VERSION" "$REPO_URL" "$TOOLS"
  fi
fi
exec "$TOOLS/scripts/release/app-release.sh" --app "$APP" --repo "$ROOT" "$@"
