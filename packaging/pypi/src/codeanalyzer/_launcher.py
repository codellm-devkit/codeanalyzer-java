"""Entry point: locate the bundled codeanalyzer runtime and hand off to it.

The wheel is platform-specific, so it contains exactly one runtime image under
``codeanalyzer/_runtime/`` (a jpackage app-image: a trimmed JVM + the analyzer
jar). We find the launcher that jpackage produced for this OS and exec it,
forwarding all CLI args and the exit code. No network, no download -- the binary
ships inside the wheel.

Override with the ``CODEANALYZER_BINARY`` environment variable to point at an
external binary (useful for testing, air-gapped staging, or a system install).
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

_PKG_DIR = Path(__file__).resolve().parent
_RUNTIME = _PKG_DIR / "_runtime"


def _candidates() -> list[Path]:
    """Possible jpackage launcher locations, across the OS-specific layouts."""
    exe = "codeanalyzer.exe" if os.name == "nt" else "codeanalyzer"
    return [
        _RUNTIME / "codeanalyzer" / "bin" / exe,                       # Linux app-image
        _RUNTIME / "codeanalyzer.app" / "Contents" / "MacOS" / "codeanalyzer",  # macOS .app
        _RUNTIME / "codeanalyzer" / exe,                               # Windows app-image
        _RUNTIME / "bin" / exe,                                        # flattened / single-file
        _PKG_DIR / "bin" / exe,                                        # single-file wrapper layout
    ]


def binary_path() -> Path:
    override = os.environ.get("CODEANALYZER_BINARY")
    if override:
        p = Path(override).expanduser()
        if not p.exists():
            sys.exit(f"codeanalyzer: CODEANALYZER_BINARY={override!r} does not exist")
        return p

    for c in _candidates():
        if c.exists():
            return c

    sys.exit(
        "codeanalyzer: no bundled runtime found inside this wheel.\n"
        "This usually means the installed wheel does not match your platform.\n"
        f"  os/arch: {sys.platform} / {os.uname().machine if hasattr(os, 'uname') else 'unknown'}\n"
        "Reinstall the wheel built for your OS/arch, or set CODEANALYZER_BINARY."
    )


def main() -> None:
    exe = binary_path()
    argv = [str(exe), *sys.argv[1:]]

    if os.name == "nt":
        # execv on Windows detaches console semantics oddly; spawn + propagate.
        import subprocess

        raise SystemExit(subprocess.call(argv))

    try:
        os.chmod(exe, 0o755)  # wheels may not preserve the exec bit
    except OSError:
        pass
    os.execv(str(exe), argv)  # replace this process: clean signals + exit code


if __name__ == "__main__":
    main()
