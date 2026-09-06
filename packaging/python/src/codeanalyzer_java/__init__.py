"""Prebuilt ``codeanalyzer-java`` backend for CLDK, with a bundled JVM.

This package carries the self-contained analyzer JAR and depends on ``jdk4py`` for a
Temurin runtime, so ``pip install codeanalyzer-java`` needs no system Java. CLDK's Python
SDK depends on this package and calls :func:`command` to run the analyzer, exactly as it
imports ``codeanalyzer-python`` and ``codeanalyzer-typescript`` for the other backends.

The analyzer reads its Java standard-library scope from the JVM it runs on (``jrt:/``),
so no ``JAVA_HOME`` and no ``jmods/`` are required.
"""

from __future__ import annotations

from importlib import resources
from pathlib import Path
from typing import List

__version__ = "0.0.0"

__all__ = ["jar_path", "java_path", "command", "__version__"]

_JAR_NAME = "codeanalyzer.jar"


def jar_path() -> Path:
    """Return the absolute path to the bundled ``codeanalyzer.jar``.

    Raises:
        FileNotFoundError: if the wheel did not include the JAR (a source/dev install
            with no build run).
    """
    resource = resources.files("codeanalyzer_java") / "_bin" / _JAR_NAME
    with resources.as_file(resource) as extracted:
        path = Path(extracted)
    if not path.exists():
        raise FileNotFoundError(
            f"Bundled {_JAR_NAME} not found at {path}. Build it with `./gradlew fatJar` and "
            "copy it there, or install the published wheel."
        )
    return path


def java_path() -> Path:
    """Return the ``java`` executable of the bundled runtime."""
    import jdk4py

    return Path(jdk4py.JAVA)


def command(*args: str) -> List[str]:
    """Return ``[java, -jar, codeanalyzer.jar, *args]`` ready for ``subprocess``."""
    return [str(java_path()), "-jar", str(jar_path()), *args]
