#!/usr/bin/env python3
"""Build one platform-tagged codeanalyzer wheel around a prebuilt jpackage image.

No compilation happens here -- we just stage the OS-specific runtime image into
the package tree and ask setuptools to emit a wheel tagged for that platform.
A single CI job can therefore build every platform's wheel from one machine.

Usage:
  python build_wheel.py --runtime <app-image-dir> --plat-name macosx_11_0_arm64
  python build_wheel.py --runtime build/jpackage    --plat-name linux_x86_64

`--runtime` is the directory jpackage produced (contains `codeanalyzer/` or
`codeanalyzer.app`). It is copied verbatim into src/codeanalyzer/_runtime/.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RUNTIME_DIR = ROOT / "src" / "codeanalyzer" / "_runtime"


def stage_runtime(src: Path) -> None:
    if not src.exists():
        sys.exit(f"runtime image not found: {src}")
    # Reset the staging dir (keep the .gitignore).
    for child in RUNTIME_DIR.iterdir():
        if child.name == ".gitignore":
            continue
        shutil.rmtree(child) if child.is_dir() else child.unlink()
    # Copy the app-image contents in.
    for child in src.iterdir():
        dest = RUNTIME_DIR / child.name
        shutil.copytree(child, dest) if child.is_dir() else shutil.copy2(child, dest)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--runtime", required=True, help="jpackage app-image directory")
    ap.add_argument("--plat-name", required=True, help="wheel platform tag, e.g. macosx_11_0_arm64")
    args = ap.parse_args()

    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    stage_runtime(Path(args.runtime))

    # `setup.py bdist_wheel` is the simplest way to pass --plat-name; the
    # bdist_wheel override in setup.py forces the impure platform tag.
    subprocess.check_call(
        [sys.executable, "setup.py", "bdist_wheel", "--plat-name", args.plat_name],
        cwd=ROOT,
        env={**os.environ},
    )
    print("\nbuilt:")
    for whl in sorted((ROOT / "dist").glob("*.whl")):
        print("  ", whl.relative_to(ROOT))


if __name__ == "__main__":
    main()
