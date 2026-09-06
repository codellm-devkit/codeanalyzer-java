"""Console-script entry point: run the bundled analyzer on the bundled JVM.

``pip install codeanalyzer-java`` installs a ``canjv`` launcher (see ``[project.scripts]``
in ``pyproject.toml``) that calls :func:`main`, passing through every CLI argument and the
exit code unchanged.
"""

from __future__ import annotations

import os
import subprocess
import sys

from . import command


def main() -> "int | None":
    argv = command(*sys.argv[1:])
    if os.name == "posix":
        # Replace this process with the JVM: no extra Python process lingers, and
        # signals/exit codes are handled by the analyzer directly.
        os.execv(argv[0], argv)
    return subprocess.call(argv)


if __name__ == "__main__":
    sys.exit(main())
