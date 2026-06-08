"""Local hatchling plugins for the codeanalyzer-java wheel.

Two concerns live here:

1. **Version lockstep.** The wheel version is read from the Java project's
   ``gradle.properties`` so the Python package and the native binary it ships
   can never drift apart.

2. **Impure, platform-tagged wheels.** The wheel carries a prebuilt native
   binary plus the JDK ``.jmod`` files it needs at runtime. The build hook
   force-includes those assets, marks the wheel non-purelib, and stamps a
   concrete ``py3-none-<platform>`` tag so pip resolves the correct artifact
   per OS/arch instead of a universal ``py3-none-any`` wheel.
"""

from __future__ import annotations

import os
import sysconfig
from pathlib import Path

from hatchling.builders.hooks.plugin.interface import BuildHookInterface
from hatchling.metadata.plugin.interface import MetadataHookInterface

_BINARY_NAMES = ("codeanalyzer", "codeanalyzer.exe")

# Bundling every JDK jmod (~104 MB) pushes the wheel over PyPI's default 100 MB
# per-file limit, and the native binary does not compress further (~23.5 MB),
# leaving room for ~80 MB of jmods. We therefore drop only the largest modules
# that static type resolution never needs, keeping 79 of 83 (~90 MB wheel):
#   - jdk.localedata          locale resource *data*, not API types
#   - jdk.compiler            com.sun.tools.javac.* internals (annotation-
#                             processor sources are the only mild compromise)
#   - jdk.internal.vm.compiler  Graal compiler internals, never referenced
#   - jdk.hotspot.agent       Serviceability Agent internals, never referenced
# Set CODEANALYZER_BUNDLE_ALL_JMODS=1 to bundle all 83 (needs a PyPI size bump).
_EXCLUDED_JMODS = frozenset(
    {
        "jdk.localedata.jmod",
        "jdk.compiler.jmod",
        "jdk.internal.vm.compiler.jmod",
        "jdk.hotspot.agent.jmod",
    }
)


def _bundle_all_jmods() -> bool:
    return os.environ.get("CODEANALYZER_BUNDLE_ALL_JMODS", "").lower() in {"1", "true", "yes"}


def _select_jmods(jmod_files: list[Path]) -> list[Path]:
    if _bundle_all_jmods():
        return jmod_files
    return [jmod for jmod in jmod_files if jmod.name not in _EXCLUDED_JMODS]


def read_gradle_version(repo_root: Path) -> str:
    """Return the ``version=`` value from ``<repo_root>/gradle.properties``."""
    gradle_properties = repo_root / "gradle.properties"
    for line in gradle_properties.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError(f"no 'version=' entry found in {gradle_properties}")


def _wheel_platform_tag() -> str:
    """Concrete wheel tag for the current platform, e.g. ``py3-none-linux_x86_64``.

    Linux wheels are emitted with the plain ``linux_*`` platform; CI runs
    ``auditwheel repair`` to relabel them to a manylinux/musllinux policy.
    """
    platform = sysconfig.get_platform().replace("-", "_").replace(".", "_")
    return f"py3-none-{platform}"


def _resolve_binary(repo_root: Path) -> Path:
    override = os.environ.get("CODEANALYZER_NATIVE_BINARY")
    if override:
        candidate = Path(override)
        if candidate.is_file():
            return candidate
        raise RuntimeError(
            f"CODEANALYZER_NATIVE_BINARY is set to '{override}' but no file exists there."
        )
    native_dir = repo_root / "build" / "native" / "nativeCompile"
    for name in _BINARY_NAMES:
        candidate = native_dir / name
        if candidate.is_file():
            return candidate
    raise RuntimeError(
        "no prebuilt codeanalyzer native binary found for this platform/arch.\n"
        f"Looked for {_BINARY_NAMES} under {native_dir}.\n"
        "Build it first with `./gradlew nativeCompile`, or point "
        "CODEANALYZER_NATIVE_BINARY at an existing binary. "
        "codeanalyzer-java ships only prebuilt wheels; there is no from-source "
        "build path for unsupported platforms."
    )


def _resolve_jmods() -> Path:
    override = os.environ.get("CODEANALYZER_JMODS_DIR")
    if override:
        candidate = Path(override)
        if candidate.is_dir():
            return candidate
        raise RuntimeError(
            f"CODEANALYZER_JMODS_DIR is set to '{override}' but it is not a directory."
        )
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "jmods"
        if candidate.is_dir():
            return candidate
    raise RuntimeError(
        "could not locate JDK .jmod files to bundle. Set CODEANALYZER_JMODS_DIR "
        "to a directory of .jmod files, or JAVA_HOME to a JDK that has a jmods/ "
        "directory (a JDK 9+ image, not a JRE)."
    )


class CustomMetadataHook(MetadataHookInterface):
    """Inject the version read from gradle.properties."""

    def update(self, metadata: dict) -> None:
        metadata["version"] = read_gradle_version(Path(self.root).parent)


class CustomBuildHook(BuildHookInterface):
    """Bundle the native binary + jmods and force an impure platform wheel."""

    def initialize(self, version: str, build_data: dict) -> None:
        if self.target_name != "wheel":
            return

        repo_root = Path(self.root).parent
        binary = _resolve_binary(repo_root)
        jmods_dir = _resolve_jmods()
        jmod_files = sorted(jmods_dir.glob("*.jmod"))
        if not jmod_files:
            raise RuntimeError(f"no .jmod files found in {jmods_dir}")
        selected = _select_jmods(jmod_files)
        if not selected:
            raise RuntimeError(f"jmod selection is empty (from {jmods_dir})")

        force_include = build_data["force_include"]
        force_include[str(binary)] = f"codeanalyzer_java/_vendor/bin/{binary.name}"
        for jmod in selected:
            force_include[str(jmod)] = f"codeanalyzer_java/_vendor/jmods/{jmod.name}"

        build_data["pure_python"] = False
        build_data["infer_tag"] = False
        build_data["tag"] = _wheel_platform_tag()

        self.app.display_info(
            f"codeanalyzer-java: bundling {binary.name} + {len(selected)}/"
            f"{len(jmod_files)} jmods as {build_data['tag']}"
        )
