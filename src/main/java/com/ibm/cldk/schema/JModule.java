package com.ibm.cldk.schema;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A per-file {@code module} (compilation unit) node. Holds the whole file's text once as
 * {@code source}; every descendant node's text is a byte-slice of it.
 */
@Data
public class JModule {
    private String id;
    private String kind = "module";
    private Span span;

    /** {@code package} is a Java keyword, so the field is {@code packageName} but serializes as {@code package}. */
    @SerializedName("package")
    private String packageName;

    private String source;

    private List<JImport> imports = new ArrayList<>();

    /** Top-level types declared in this file, keyed by simple name (nested types hang under them). */
    private Map<String, JType> types = new LinkedHashMap<>();

    /**
     * Content hash of {@code source} — used for incremental caching and the Neo4j writer's
     * per-module diffing. Not identity (the {@code id} is).
     */
    private String contentHash;
}
