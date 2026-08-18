package com.ibm.cldk.schema;

import lombok.Data;

/**
 * An import declaration on a {@code module}. {@code path} is the imported name as written
 * ({@code java.util.List}, or the package for a wildcard import); {@code name} is its last segment.
 * {@code is_static} / {@code is_wildcard} are Java-specific additions to the keystone's import shape.
 */
@Data
public class JImport {
    private String name;
    private String path;
    private Span span;
    private boolean isStatic;
    private boolean isWildcard;
}
