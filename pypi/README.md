![logo](https://raw.githubusercontent.com/codellm-devkit/codeanalyzer-java/main/docs/assets/logo.png)

Native WALA implementation of source code analysis tool for Enterprise Java Applications.

## 1. Installing `codeanalyzer`

`codeanalyzer` ships as a self-contained, JVM-free native binary. No JDK, no
GraalVM, and no build step are required — just install from PyPI:

```bash
pip install codeanalyzer-java
```

This installs the `codajv` command, which runs the bundled native binary
(`pip install` automatically selects the wheel matching your OS/architecture).

## 2. Using `codeanalyzer`

```help
Usage: codajv [-hvV] [--no-build] [-a=<analysisLevel>] [-b=<build>]
              [-i=<input>] [-o=<output>] [-s=<sourceAnalysis>]
Convert java binary into a comprehensive system dependency graph.
  -i, --input=<input>       Path to the project root directory.
  -s, --source-analysis=<sourceAnalysis>
                            Analyze a single string of java source code instead
                              the project.
  -o, --output=<output>     Destination directory to save the output graphs. By
                              default, the SDG formatted as a JSON will be
                              printed to the console.
  -b, --build-cmd=<build>   Custom build command. Defaults to auto build.
      --no-build            Do not build your application. Use this option if
                              you have already built your application.
  -a, --analysis-level=<analysisLevel>
                            Level of analysis to perform. Options: 1 (for just
                              symbol table) or 2 (for call graph). Default: 1
  -v, --verbose             Print logs to console.
  -h, --help                Show this help message and exit.
  -V, --version             Print version information and exit.
  -t, --target-files        For each file user wants to perform source analysis on top of existing analysis.json

```

For example, to analyze a project and print the system dependency graph to the
console:

```sh
codajv -i /path/to/java/project
```

Pass `-o <dir>` to save the output as JSON. Explore the other flags above to
control the analysis level and build behavior.

## LICENSE

```LICENSE
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
