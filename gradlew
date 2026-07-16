#!/bin/sh
set -eu

GRADLE_VERSION="8.13"
DIST_NAME="gradle-${GRADLE_VERSION}-bin.zip"
DIST_URL="https://services.gradle.org/distributions/${DIST_NAME}"
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/arda-wrapper"
INSTALL_DIR="${CACHE_ROOT}/gradle-${GRADLE_VERSION}"
GRADLE_BIN="${INSTALL_DIR}/bin/gradle"
ZIP_PATH="${CACHE_ROOT}/${DIST_NAME}"

if [ ! -x "$GRADLE_BIN" ]; then
  command -v curl >/dev/null 2>&1 || {
    echo "curl is required to download Gradle." >&2
    exit 1
  }
  command -v unzip >/dev/null 2>&1 || {
    echo "unzip is required to unpack Gradle." >&2
    exit 1
  }

  mkdir -p "$CACHE_ROOT"
  echo "Downloading Gradle ${GRADLE_VERSION}..."
  curl -fL --retry 3 --connect-timeout 20 \
    -o "${ZIP_PATH}.tmp" "$DIST_URL"
  mv "${ZIP_PATH}.tmp" "$ZIP_PATH"

  rm -rf "$INSTALL_DIR"
  unzip -q "$ZIP_PATH" -d "$CACHE_ROOT"
fi

exec "$GRADLE_BIN" "$@"
