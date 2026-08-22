package com.ibm.cldk.schema;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * A comment attached to a node — the declaration's own javadoc or leading line/block comment.
 *
 * <p>Unlike the v1 model (which collected <em>all contained</em> comments, so a type repeated every
 * comment inside every member), a node here carries only the comment attached to it; the module
 * carries the file-level/orphan comments. Text is also recoverable from
 * {@code module.source[span.bytes]}, but keeping comments addressable matters for doc-driven
 * consumers.
 */
@Data
public class JComment {
    private String content;
    private Span span;

    @SerializedName("is_javadoc")
    private boolean javadoc;
}
