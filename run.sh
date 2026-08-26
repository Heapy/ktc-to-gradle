#!/usr/bin/env sh
set -eu

REPOSITORY="${KTC_TO_GRADLE_REPOSITORY:-Heapy/ktc-to-gradle}"
REQUESTED_VERSION="${KTC_TO_GRADLE_VERSION:-latest}"
DOWNLOAD_ROOT="${KTC_TO_GRADLE_DOWNLOAD_ROOT:-https://github.com/$REPOSITORY/releases}"

case "$REPOSITORY" in
  ""|/*|*/|*//*|*/*/*|*..*|*[!A-Za-z0-9._/-]*)
    echo "ktc-to-gradle: invalid repository '$REPOSITORY' (expected owner/name)" >&2
    exit 1
    ;;
  */*) ;;
  *)
    echo "ktc-to-gradle: invalid repository '$REPOSITORY' (expected owner/name)" >&2
    exit 1
    ;;
esac

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) ASSET="ktc-to-gradle-macos-arm64.tar.gz" ;;
  Darwin-x86_64)
    echo "ktc-to-gradle: macOS Intel is not supported by Kotlin Toolchain 0.12 native apps" >&2
    exit 1
    ;;
  Linux-x86_64) ASSET="ktc-to-gradle-linux-x64.tar.gz" ;;
  Linux-aarch64|Linux-arm64) ASSET="ktc-to-gradle-linux-arm64.tar.gz" ;;
  *)
    echo "ktc-to-gradle: unsupported platform $(uname -s)/$(uname -m)" >&2
    exit 1
    ;;
esac

if [ "$REQUESTED_VERSION" = "latest" ]; then
  TAG="$(
    curl -fsSL --retry 3 "https://api.github.com/repos/$REPOSITORY/releases/latest" |
      sed -n 's/.*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p' |
      head -n 1
  )"
else
  case "$REQUESTED_VERSION" in
    v*) TAG="$REQUESTED_VERSION" ;;
    *) TAG="v$REQUESTED_VERSION" ;;
  esac
fi

case "$TAG" in
  ""|*[!A-Za-z0-9._+-]*)
    echo "ktc-to-gradle: invalid or unavailable release tag '$TAG'" >&2
    exit 1
    ;;
esac

CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/ktc-to-gradle/$REPOSITORY/$TAG/${ASSET%.tar.gz}"
BINARY="$CACHE_ROOT/ktc-to-gradle"

if [ ! -x "$BINARY" ]; then
  BASE_URL="$DOWNLOAD_ROOT/download/$TAG"
  TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ktc-to-gradle-run.XXXXXX")"
  trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

  curl -fL --retry 3 -o "$TEMP_DIR/$ASSET" "$BASE_URL/$ASSET"
  curl -fL --retry 3 -o "$TEMP_DIR/$ASSET.sha256" "$BASE_URL/$ASSET.sha256"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$TEMP_DIR" && sha256sum -c "$ASSET.sha256")
  else
    (cd "$TEMP_DIR" && shasum -a 256 -c "$ASSET.sha256")
  fi

  tar -xzf "$TEMP_DIR/$ASSET" -C "$TEMP_DIR"
  mkdir -p "$CACHE_ROOT"
  cp "$TEMP_DIR/ktc-to-gradle" "$CACHE_ROOT/ktc-to-gradle.$$"
  chmod +x "$CACHE_ROOT/ktc-to-gradle.$$"
  mv "$CACHE_ROOT/ktc-to-gradle.$$" "$BINARY"
  rm -rf "$TEMP_DIR"
fi

exec "$BINARY" "$@"
