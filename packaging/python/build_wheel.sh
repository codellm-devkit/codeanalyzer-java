#!/usr/bin/env bash
#
# Build the codeanalyzer-java wheel: the fat JAR under _bin/ plus a `canjv` launcher that runs it
# on the jdk4py runtime. The JAR is platform-independent and the JVM comes from jdk4py's own
# platform wheels, so exactly one py3-none-any wheel is produced.
#
# Requirements on the build host:
#   - the fat JAR already built:  ./gradlew fatJar   (build/libs/codeanalyzer-<version>.jar)
#   - python -m pip install build hatchling
#
# Usage:
#   PKG_VERSION=3.0.2 ./build_wheel.sh     # writes ./dist/codeanalyzer_java-3.0.2-py3-none-any.whl
#   twine upload dist/*.whl                # publish
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
PKG_VERSION="${PKG_VERSION:-$(sed -n 's/^version=//p' "$REPO_ROOT/gradle.properties")}"
JAR="${JAR:-$REPO_ROOT/build/libs/codeanalyzer-${PKG_VERSION}.jar}"
BIN_DIR="$HERE/src/codeanalyzer_java/_bin"
INIT_PY="$HERE/src/codeanalyzer_java/__init__.py"

[[ -f "$JAR" ]] || { echo "error: $JAR not found; run ./gradlew fatJar first" >&2; exit 1; }

# Stamp $PKG_VERSION into __init__.py for the build and restore it on exit; likewise remove the
# copied JAR and README, so the working tree stays pristine.
ORIG_INIT="$(cat "$INIT_PY")"
cleanup() {
  printf '%s\n' "$ORIG_INIT" > "$INIT_PY"
  find "$BIN_DIR" -mindepth 1 ! -name '.gitignore' -delete
  rm -f "$HERE/README.md"
}
trap cleanup EXIT
python - "$INIT_PY" "$PKG_VERSION" <<'PY'
import re, sys
path, version = sys.argv[1], sys.argv[2]
text = open(path).read()
new, n = re.subn(r'__version__ = "[^"]*"', f'__version__ = "{version}"', text)
if n != 1:
    raise SystemExit(f"expected exactly one __version__ assignment in {path}, found {n}")
open(path, "w").write(new)
print(f">>> stamped __version__ = {version}")
PY

rm -rf "$HERE/dist"
mkdir -p "$HERE/dist" "$BIN_DIR"
cp "$JAR" "$BIN_DIR/codeanalyzer.jar"
cp "$REPO_ROOT/README.md" "$HERE/README.md"

python -m build --wheel --no-isolation -o "$HERE/dist" "$HERE"

echo
echo ">>> Built wheel:"
ls -lh "$HERE/dist"/*.whl
echo
echo "Publish with:  twine upload $HERE/dist/*.whl"
