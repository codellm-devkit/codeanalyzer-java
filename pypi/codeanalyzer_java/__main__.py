"""Console entry point (``codajv``) for the bundled codeanalyzer native binary.

The wheel ships a prebuilt, JVM-free GraalVM native image together with the JDK
``.jmod`` files it needs at runtime: both WALA's primordial scope (analysis
level 2) and the JavaParser bytecode symbol solver read the jmods straight off
disk, so they cannot be baked into the image. This module locates those bundled
assets, points ``CODEANALYZER_JMODS_DIR`` at the bundled jmods, and hands off to
the native binary.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

_PKG_DIR = Path(__file__).resolve().parent
_VENDOR = _PKG_DIR / "_vendor"
# pypi/codeanalyzer_java/ -> pypi/ -> repo root (dev/source-checkout fallback).
_REPO_ROOT = _PKG_DIR.parent.parent


def _find_binary() -> Path | None:
    candidates: list[Path] = []
    override = os.environ.get("CODEANALYZER_NATIVE_BINARY")
    if override:
        candidates.append(Path(override))
    candidates += [
        _VENDOR / "bin" / "codeanalyzer",
        _VENDOR / "bin" / "codeanalyzer.exe",
        _REPO_ROOT / "build" / "native" / "nativeCompile" / "codeanalyzer",
        _REPO_ROOT / "build" / "native" / "nativeCompile" / "codeanalyzer.exe",
    ]
    return next((c for c in candidates if c.is_file()), None)


def _find_jmods() -> Path | None:
    bundled = _VENDOR / "jmods"
    if bundled.is_dir() and any(bundled.glob("*.jmod")):
        return bundled
    override = os.environ.get("CODEANALYZER_JMODS_DIR")
    if override and Path(override).is_dir():
        return Path(override)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        jmods = Path(java_home) / "jmods"
        if jmods.is_dir():
            return jmods
    return None


def main() -> int:
    binary = _find_binary()
    if binary is None:
        sys.stderr.write(
            "codajv: could not find a codeanalyzer native binary.\n"
            "This usually means the installed wheel does not match your "
            "platform/architecture, or you are running from a source checkout "
            "without a built binary (run `./gradlew nativeCompile`).\n"
        )
        return 1

    env = dict(os.environ)
    jmods = _find_jmods()
    if jmods is not None:
        env["CODEANALYZER_JMODS_DIR"] = str(jmods)

    # Wheel installation does not reliably preserve the executable bit.
    try:
        os.chmod(binary, binary.stat().st_mode | 0o111)
    except OSError:
        pass

    argv = [str(binary), *sys.argv[1:]]
    if os.name == "posix":
        # Replace this process so signals and the exit code pass through cleanly.
        os.execve(str(binary), argv, env)
        return 127  # unreachable when execve succeeds

    import subprocess

    return subprocess.run(argv, env=env).returncode


if __name__ == "__main__":
    raise SystemExit(main())
