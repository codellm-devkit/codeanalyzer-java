#!/bin/sh
# codeanalyzer installer — downloads the prebuilt codeanalyzer-java fat JAR for the requested
# release from the GitHub Release and installs a `codeanalyzer` launcher on your PATH. Mirrors the
# cargo-dist installer pattern (see codeanalyzer-typescript's cants-installer.sh).
#
# Usage:
#   curl --proto '=https' --tlsv1.2 -LsSf https://github.com/codellm-devkit/codeanalyzer-java/releases/latest/download/codeanalyzer-installer.sh | sh
#   # or with wget:
#   wget -qO- https://github.com/codellm-devkit/codeanalyzer-java/releases/latest/download/codeanalyzer-installer.sh | sh
#
# Environment overrides:
#   CODEANALYZER_INSTALL_DIR   install location           (default: ~/.local/bin)
#   CODEANALYZER_VERSION       release tag, e.g. v2.4.0   (default: latest)
set -eu

REPO="codellm-devkit/codeanalyzer-java"
INSTALL_DIR="${CODEANALYZER_INSTALL_DIR:-$HOME/.local/bin}"
VERSION="${CODEANALYZER_VERSION:-latest}"

# codeanalyzer-java ships as a single self-contained fat JAR; it needs a Java 11+ runtime.
if ! command -v java >/dev/null 2>&1; then
  echo "codeanalyzer: a Java 11+ runtime is required but 'java' was not found on PATH." >&2
  echo "codeanalyzer: install a JDK (e.g. 'sdk install java 17.0.10-sem') and re-run." >&2
  exit 1
fi

asset="codeanalyzer.jar"
if [ "$VERSION" = "latest" ]; then
  url="https://github.com/$REPO/releases/latest/download/$asset"
else
  url="https://github.com/$REPO/releases/download/$VERSION/$asset"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "codeanalyzer: downloading $asset ($VERSION)..."
if command -v curl >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -fLsS "$url" -o "$tmp/codeanalyzer.jar"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$url" -O "$tmp/codeanalyzer.jar"
else
  echo "codeanalyzer: need curl or wget to download" >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR"
mv "$tmp/codeanalyzer.jar" "$INSTALL_DIR/codeanalyzer.jar"

# Write a tiny launcher so users can call `codeanalyzer ...` directly.
cat > "$INSTALL_DIR/codeanalyzer" <<EOF
#!/bin/sh
exec java -jar "$INSTALL_DIR/codeanalyzer.jar" "\$@"
EOF
chmod +x "$INSTALL_DIR/codeanalyzer"
echo "codeanalyzer: installed to $INSTALL_DIR/codeanalyzer (jar at $INSTALL_DIR/codeanalyzer.jar)"

# PATH hint when the install dir isn't already on PATH.
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) echo "codeanalyzer: add it to your PATH:  export PATH=\"$INSTALL_DIR:\$PATH\"" ;;
esac
