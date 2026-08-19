package com.ibm.cldk.schema;

import lombok.Data;

/**
 * Which analyzer produced a payload, and at which version — part of the v2 envelope manifest so a
 * consumer can tell what wrote the file it is reading.
 */
@Data
public class JAnalyzerInfo {
    private String name = "codeanalyzer-java";
    private String version;
}
