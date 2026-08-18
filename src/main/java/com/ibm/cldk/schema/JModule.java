package com.ibm.cldk.schema;

import com.google.gson.annotations.SerializedName;
import java.util.LinkedHashMap;
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

    /** {@code package} is a Java keyword, so the field is {@code packageName} but serializes as {@code package}. */
    @SerializedName("package")
    private String packageName;

    private String source;

    /** Top-level types declared in this file, keyed by simple name (nested types hang under them). */
    private Map<String, JType> types = new LinkedHashMap<>();
}
