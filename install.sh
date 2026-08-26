#!/usr/bin/env sh
set -eu

REPOSITORY="${KTC_TO_GRADLE_REPOSITORY:-Heapy/ktc-to-gradle}"
VERSION="${KTC_TO_GRADLE_VERSION:-latest}"
INSTALL_DIR="${KTC_TO_GRADLE_INSTALL_DIR:-$HOME/.local/bin}"
DOWNLOAD_ROOT="${KTC_TO_GRADLE_DOWNLOAD_ROOT:-https://github.com/$REPOSITORY/releases}"

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

if [ "$VERSION" = "latest" ]; then
  BASE_URL="$DOWNLOAD_ROOT/latest/download"
else
  case "$VERSION" in
    v*) TAG="$VERSION" ;;
    *) TAG="v$VERSION" ;;
  esac
  case "$TAG" in
    ""|*[!A-Za-z0-9._+-]*)
      echo "ktc-to-gradle: invalid release tag '$TAG'" >&2
      exit 1
      ;;
  esac
  BASE_URL="$DOWNLOAD_ROOT/download/$TAG"
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ktc-to-gradle-install.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM

curl -fL --retry 3 -o "$TEMP_DIR/$ASSET" "$BASE_URL/$ASSET"
curl -fL --retry 3 -o "$TEMP_DIR/$ASSET.sha256" "$BASE_URL/$ASSET.sha256"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$TEMP_DIR" && sha256sum -c "$ASSET.sha256")
else
  (cd "$TEMP_DIR" && shasum -a 256 -c "$ASSET.sha256")
fi

tar -xzf "$TEMP_DIR/$ASSET" -C "$TEMP_DIR"
mkdir -p "$INSTALL_DIR"
cp "$TEMP_DIR/ktc-to-gradle" "$INSTALL_DIR/.ktc-to-gradle.$$"
chmod +x "$INSTALL_DIR/.ktc-to-gradle.$$"
mv "$INSTALL_DIR/.ktc-to-gradle.$$" "$INSTALL_DIR/ktc-to-gradle"

echo "Installed ktc-to-gradle native binary to $INSTALL_DIR/ktc-to-gradle"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) echo "Add $INSTALL_DIR to PATH before running ktc-to-gradle." ;;
esac
