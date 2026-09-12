#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
USER_HOME=${HOME:?HOME is not set}
RUSTROVER_CONFIG_DIR=${RUSTROVER_CONFIG_DIR:-}
BAZEL_DISTDIR=${BAZEL_DISTDIR:-/private/tmp/git4idea-bazel-distdir}
TARGET=//plugins/lore4idea:vcs-lore
ARTIFACT="$REPOSITORY_ROOT/out/bazel-bin/plugins/lore4idea/vcs-lore.jar"

if [[ ${1:-} == "--help" ]]; then
  cat <<'EOF'
Build and install the Lore plugin for RustRover.

Usage:
  plugins/lore4idea/build-install-rustrover.sh

Environment overrides:
  RUSTROVER_CONFIG_DIR  RustRover configuration directory
  BAZEL_DISTDIR         Bazel dependency distribution directory
EOF
  exit 0
fi

if [[ -z "$RUSTROVER_CONFIG_DIR" ]]; then
  shopt -s nullglob
  RUSTROVER_CONFIG_CANDIDATES=("$USER_HOME/Library/Application Support/JetBrains"/RustRover*)
  shopt -u nullglob
  for candidate in "${RUSTROVER_CONFIG_CANDIDATES[@]}"; do
    if [[ -d "$candidate" && (-z "$RUSTROVER_CONFIG_DIR" || "$candidate" -nt "$RUSTROVER_CONFIG_DIR") ]]; then
      RUSTROVER_CONFIG_DIR=$candidate
    fi
  done
fi

PLUGINS_DIR="$RUSTROVER_CONFIG_DIR/plugins"
INSTALLED_JAR="$PLUGINS_DIR/vcs-lore.jar"
STAGED_JAR="$PLUGINS_DIR/.vcs-lore.jar.new"

if [[ -z "$RUSTROVER_CONFIG_DIR" ]]; then
  echo "No RustRover configuration directory was found." >&2
  echo "Set RUSTROVER_CONFIG_DIR to the RustRover configuration directory." >&2
  exit 1
fi

if [[ $# -ne 0 ]]; then
  echo "Unknown argument: $1" >&2
  echo "Use --help for usage." >&2
  exit 2
fi

set +e
pgrep -f '/RustRover.app/Contents/MacOS/rustrover' >/dev/null 2>&1
RUSTROVER_PROCESS_STATUS=$?
set -e

if [[ $RUSTROVER_PROCESS_STATUS -eq 0 ]]; then
  echo "RustRover is running. Quit RustRover before replacing the Lore plugin." >&2
  exit 1
fi

if [[ $RUSTROVER_PROCESS_STATUS -ne 1 ]]; then
  echo "Unable to check whether RustRover is running. Plugin installation was stopped." >&2
  exit 1
fi

if [[ ! -d "$RUSTROVER_CONFIG_DIR" ]]; then
  echo "RustRover configuration directory does not exist: $RUSTROVER_CONFIG_DIR" >&2
  echo "Set RUSTROVER_CONFIG_DIR to the correct RustRover configuration directory." >&2
  exit 1
fi

mkdir -p "$BAZEL_DISTDIR"

echo "Building $TARGET"
cd "$REPOSITORY_ROOT"
/bin/bash bazel.cmd build \
  --remote_download_outputs=all \
  --distdir="$BAZEL_DISTDIR" \
  "$TARGET"

if [[ ! -f "$ARTIFACT" ]]; then
  echo "Built plugin JAR was not found: $ARTIFACT" >&2
  exit 1
fi

mkdir -p "$PLUGINS_DIR"

if [[ -e "$INSTALLED_JAR" ]]; then
  echo "Uninstalling existing Lore plugin: $INSTALLED_JAR"
  rm -f -- "$INSTALLED_JAR"
else
  echo "No existing Lore plugin JAR found."
fi

rm -f -- "$STAGED_JAR"
cp -- "$ARTIFACT" "$STAGED_JAR"
chmod 0644 "$STAGED_JAR"
mv -f -- "$STAGED_JAR" "$INSTALLED_JAR"

if ! cmp -s -- "$ARTIFACT" "$INSTALLED_JAR"; then
  echo "Installed JAR verification failed: $INSTALLED_JAR" >&2
  exit 1
fi

echo "Installed Lore plugin: $INSTALLED_JAR"
echo "Start RustRover to load the new plugin."
