"""Platform-tagged wheel build for the bundled codeanalyzer runtime.

Everything declarative lives in pyproject.toml. Two things must be done
imperatively, which PEP 621 cannot express:

1. Force a PLATFORM wheel. A wheel that ships a native runtime must be tagged for
   a specific platform (e.g. macosx_11_0_arm64) instead of the default pure-python
   `py3-none-any`. We override bdist_wheel:
     * root_is_pure = False  -> mark the wheel impure (platform-specific)
     * get_tag()             -> py3 / none / <plat>, where <plat> is supplied by
                                the build matrix via `--plat-name`.
   Build one wheel per target, e.g.:
     python setup.py bdist_wheel --plat-name macosx_11_0_arm64

2. Version from the release tag. CI sets CODEANALYZER_VERSION; fall back to a dev
   version for local builds.
"""
import os

from setuptools import setup

try:  # setuptools >= 70.1 vendors bdist_wheel; older installs use the wheel pkg
    from setuptools.command.bdist_wheel import bdist_wheel as _bdist_wheel
except ImportError:  # pragma: no cover
    from wheel.bdist_wheel import bdist_wheel as _bdist_wheel


class bdist_wheel(_bdist_wheel):
    def finalize_options(self):
        super().finalize_options()
        # Not a pure-python wheel: it carries a platform-specific runtime image.
        self.root_is_pure = False

    def get_tag(self):
        _python, _abi, plat = super().get_tag()
        # ABI-agnostic (no Python C-extension), platform-specific.
        return "py3", "none", plat


setup(
    version=os.environ.get("CODEANALYZER_VERSION", "0.0.0.dev0"),
    cmdclass={"bdist_wheel": bdist_wheel},
)
