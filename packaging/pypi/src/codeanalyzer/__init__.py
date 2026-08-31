"""codeanalyzer-java: a self-contained Java static analyzer.

This Python package is a thin shell around a bundled, platform-specific runtime
image (a trimmed JVM + the analyzer jar, produced by jpackage). The real work is
done by the bundled binary; see codeanalyzer._launcher.
"""
