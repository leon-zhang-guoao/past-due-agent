#!/usr/bin/env bash
# Production Studio: execute an already compiled Builder artifact only.
set -euo pipefail

# Standard Linux bundles carry their own runtimes; standalone app packages
# continue to use the host runtime when these directories are absent.
RUNTIME_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$RUNTIME_ROOT/runtime/java/bin:$RUNTIME_ROOT/runtime/node/bin:$PATH"

# Direct use detaches; process managers explicitly request foreground mode.
if [ "${1:-}" = --foreground ]; then
  shift
else
  SERVICE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  exec bash "$SERVICE_ROOT/scripts/app-service.sh" start studio "${OPEN_ATLAS_STUDIO_HOME:-${OPEN_ATLAS_HOME:-$HOME/.open-atlas}/studio}" "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")" "$@"
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${OPEN_ATLAS_DIST_DIR:-$ROOT_DIR/dist}"
JAR="$DIST_DIR/studio/open-atlas-studio.jar"
DRIVERS_DIR="$DIST_DIR/studio/drivers"
if [ -f "$ROOT_DIR/lib/open-atlas-studio.jar" ]; then
  JAR="$ROOT_DIR/lib/open-atlas-studio.jar"
  DRIVERS_DIR="$ROOT_DIR/drivers"
fi
export OPEN_ATLAS_PACKAGE_ROOT="${OPEN_ATLAS_PACKAGE_ROOT:-$ROOT_DIR}"
ATLAS_HOME_DIR="${OPEN_ATLAS_HOME:-${HOME}/.open-atlas}"

[ -f "$JAR" ] || { echo "Missing compiled Studio artifact: $JAR" >&2; echo "Build it with: $ROOT_DIR/scripts/build-studio.sh" >&2; exit 1; }
## The seed model is OPTIONAL (8.16.1 ②): unset, the jar's own
## `demo/model.edn` resource answers, the same way the Server falls back to its
## bundled example. Set, it must exist — a typo silently seeding the packaged
## DVD model instead of the reviewed one is the failure worth keeping.
if [ -n "${OPEN_ATLAS_MODEL_PATH:-}" ] && [ ! -f "$OPEN_ATLAS_MODEL_PATH" ]; then
  echo "Model file does not exist: $OPEN_ATLAS_MODEL_PATH" >&2
  echo "Unset it to seed from the model bundled in the jar." >&2
  exit 1
fi

export OPEN_ATLAS_STUDIO_HOME="${OPEN_ATLAS_STUDIO_HOME:-$ATLAS_HOME_DIR/studio}"
mkdir -p "$OPEN_ATLAS_STUDIO_HOME"

## A deployed Builder opens the same sources a dev one does, and their
## credential keys are the same `${VAR}` references, so it reads the same
## file the same way. An orchestrator that exports the values itself still
## wins — the loader treats the environment as authoritative over the file.
## The image carries scripts/load-env.sh; a source checkout of the Studio repo has only the copy
## build-images.sh puts in build-info/ from the pinned open-atlas-release version (repo-split §3).
LOAD_ENV="$ROOT_DIR/scripts/load-env.sh"
[ -f "$LOAD_ENV" ] || LOAD_ENV="$ROOT_DIR/build-info/load-env.sh"
[ -f "$LOAD_ENV" ] || { echo "Missing load-env.sh: copy scripts/load-env.sh of open-atlas-release $(cat "$ROOT_DIR/tools.lock" 2>/dev/null) to $ROOT_DIR/build-info/" >&2; exit 1; }
. "$LOAD_ENV"
load_atlas_env "$OPEN_ATLAS_STUDIO_HOME" OPEN_ATLAS_STUDIO_HOME

## `-cp`, not `-jar`: `-jar` IGNORES the classpath, and the JDBC drivers live
## beside the jar rather than inside it (8.13). Dropping a jar into drivers/ is
## all it takes to add an engine; deleting one is all it takes to stop shipping
## it. A missing driver fails loudly at connect time ("No suitable driver found
## for jdbc:…"), never as an empty result.
[ -d "$DRIVERS_DIR" ] || {
  echo "Missing drivers directory: $DRIVERS_DIR" >&2
  echo "Build it with: $ROOT_DIR/scripts/build-studio.sh" >&2
  exit 1
}

## HTTPS by default. With no keystore configured, both listeners (Builder and
## data plane) use a self-signed certificate generated once under the Studio
## home. Clients must trust it or be told to skip verification; for a trusted
## certificate, set OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE/_PASSWORD (PKCS12) or
## put a TLS-terminating proxy in front. OPEN_ATLAS_STUDIO_TLS=off keeps plain HTTP.
if [ "${OPEN_ATLAS_STUDIO_TLS:-auto}" != off ] && [ -z "${OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE:-}" ]; then
  TLS_DIR="$OPEN_ATLAS_STUDIO_HOME/tls"
  if [ ! -f "$TLS_DIR/self-signed.p12" ]; then
    command -v keytool >/dev/null || { echo "keytool not found: cannot generate the self-signed certificate (set OPEN_ATLAS_STUDIO_TLS=off or configure a keystore)" >&2; exit 1; }
    mkdir -p "$TLS_DIR" && chmod 700 "$TLS_DIR"
    ( umask 077; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TLS_DIR/self-signed.password" )
    # Extra names: OPEN_ATLAS_STUDIO_TLS_SAN, e.g. "dns:studio.example.com,ip:192.0.2.10".
    SAN="dns:localhost,ip:127.0.0.1,dns:$(hostname)${OPEN_ATLAS_STUDIO_TLS_SAN:+,$OPEN_ATLAS_STUDIO_TLS_SAN}"
    keytool -genkeypair -alias studio -keyalg EC -groupname secp256r1 -validity 825 \
      -dname "CN=Open Atlas Studio" -ext "SAN=$SAN" -storetype PKCS12 \
      -keystore "$TLS_DIR/self-signed.p12" \
      -storepass "$(cat "$TLS_DIR/self-signed.password")" >/dev/null 2>&1 \
      || { echo "Failed to generate $TLS_DIR/self-signed.p12" >&2; exit 1; }
    chmod 600 "$TLS_DIR/self-signed.p12"
    echo "Generated a self-signed TLS certificate: $TLS_DIR/self-signed.p12 ($SAN)"
  fi
  export OPEN_ATLAS_STUDIO_BUILDER_TLS_KEYSTORE="$TLS_DIR/self-signed.p12"
  OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD="$(cat "$TLS_DIR/self-signed.password")"
  export OPEN_ATLAS_STUDIO_BUILDER_TLS_PASSWORD
fi

## Check the listeners before the JVM does (8.16.1 ②).
require_free_port "${OPEN_ATLAS_STUDIO_PORT:-9631}" "Builder UI" OPEN_ATLAS_STUDIO_PORT || exit 1
require_free_port "${OPEN_ATLAS_STUDIO_DATA_PORT:-9632}" "Builder data plane" OPEN_ATLAS_STUDIO_DATA_PORT || exit 1

# doc 110 §110.1: say which build this is before anything else is logged.
[ -f "${OPEN_ATLAS_PACKAGE_ROOT:-}/BUILD.json" ] && echo "open-atlas studio $(sed -nE 's/.*"version": *"([^"]+)".*/\1/p' "$OPEN_ATLAS_PACKAGE_ROOT/BUILD.json" | head -1)"
exec java ${JAVA_OPTS:-} -cp "$JAR:$DRIVERS_DIR/*" open.atlas.studio.builder_server
