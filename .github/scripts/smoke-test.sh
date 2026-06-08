#!/usr/bin/env bash
# Install the freshly built wheel into a throwaway venv and exercise the
# `codajv` entry point JVM-free: it must run the bundled native binary and
# resolve JDK types from the bundled jmods (no JAVA_HOME required).
#
# Usage: smoke-test.sh [DIST_DIR]   (defaults to ./dist)
# Honors $PYTHON to pick the interpreter that creates the venv.
set -euo pipefail

PYTHON="${PYTHON:-python3}"
DIST_DIR="${1:-dist}"

wheel=$(ls "$DIST_DIR"/*.whl 2>/dev/null | head -n1 || true)
if [ -z "$wheel" ]; then
  echo "smoke-test: no wheel found in '$DIST_DIR'" >&2
  exit 1
fi
echo "smoke-test: testing $wheel"

venv="$(mktemp -d)/venv"
"$PYTHON" -m venv "$venv"
if [ -x "$venv/bin/python" ]; then
  vpy="$venv/bin/python"
  vcodajv="$venv/bin/codajv"
else
  vpy="$venv/Scripts/python.exe"
  vcodajv="$venv/Scripts/codajv.exe"
fi

"$vpy" -m pip install --upgrade pip >/dev/null
"$vpy" -m pip install "$wheel"

echo "smoke-test: codajv --version"
"$vcodajv" --version

echo "smoke-test: codajv -s (level-1 source analysis, exercises bundled jmods)"
out="$("$vcodajv" -s 'public class Smoke { public int answer() { return 42; } }')"
echo "$out" | head -c 2000
echo
if ! echo "$out" | grep -q 'Smoke'; then
  echo "smoke-test: FAILED — expected class 'Smoke' in analysis output" >&2
  exit 1
fi
echo "smoke-test: OK"
