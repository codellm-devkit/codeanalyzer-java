"""Python wrapper that ships and runs the codeanalyzer native binary."""

from __future__ import annotations

try:
    from importlib.metadata import PackageNotFoundError, version

    try:
        __version__ = version("codeanalyzer-java")
    except PackageNotFoundError:  # running from a source checkout
        __version__ = "0.0.0"
except Exception:  # pragma: no cover - importlib.metadata always present on 3.9+
    __version__ = "0.0.0"

__all__ = ["__version__"]
