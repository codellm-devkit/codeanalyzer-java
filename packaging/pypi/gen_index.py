#!/usr/bin/env python3
"""Generate a static PEP 503 'simple' index for the codeanalyzer wheels.

The wheels live as GitHub release assets (no PyPI size limit); this index is what
lets `pip install codeanalyzer-java --extra-index-url <pages-url>/simple/`
auto-resolve the correct platform wheel. Publish the output to GitHub Pages.

Each link carries a `#sha256=` fragment so pip (and lockfiles) can hash-pin.

Usage:
  python gen_index.py --wheels dist \
    --base-url https://github.com/codellm-devkit/codeanalyzer-java/releases/download/v2.4.0 \
    --out public
"""
from __future__ import annotations

import argparse
import hashlib
import html
from pathlib import Path

PROJECT = "codeanalyzer-java"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--wheels", required=True, help="directory of .whl files")
    ap.add_argument("--base-url", required=True, help="GitHub release download base URL")
    ap.add_argument("--out", required=True, help="output dir (publish <out>/simple to Pages)")
    args = ap.parse_args()

    wheels = sorted(Path(args.wheels).glob("*.whl"))
    if not wheels:
        raise SystemExit(f"no wheels found in {args.wheels}")

    simple = Path(args.out) / "simple"
    proj = simple / PROJECT
    proj.mkdir(parents=True, exist_ok=True)

    # Root index: one normalized project link.
    (simple / "index.html").write_text(
        "<!DOCTYPE html>\n<html><body>\n"
        f'<a href="{PROJECT}/">{PROJECT}</a>\n'
        "</body></html>\n",
        encoding="utf-8",
    )

    # Project index: one link per wheel, with a sha256 fragment for hash pinning.
    rows = []
    base = args.base_url.rstrip("/")
    for whl in wheels:
        url = f"{base}/{whl.name}#sha256={sha256(whl)}"
        rows.append(f'    <a href="{html.escape(url)}">{html.escape(whl.name)}</a><br>')
    proj.joinpath("index.html").write_text(
        "<!DOCTYPE html>\n<html><body>\n" + "\n".join(rows) + "\n</body></html>\n",
        encoding="utf-8",
    )

    print(f"wrote PEP 503 index for {len(wheels)} wheel(s) under {simple}")


if __name__ == "__main__":
    main()
