package com.ibm.cldk.schema;

import lombok.Data;

/**
 * The canonical schema v2 payload root (the envelope): manifest fields plus the {@code application}
 * tree node. Serialized with Gson's {@code LOWER_CASE_WITH_UNDERSCORES} policy, so
 * {@code schemaVersion} → {@code schema_version}, {@code maxLevel} → {@code max_level}, etc.
 */
@Data
public class Analysis {
    private String schemaVersion;
    private String language;
    private int maxLevel;
    private JAnalyzerInfo analyzer;
    private JApplication application;
}
