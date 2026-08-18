package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Cross-references out of a callable, nested per design decision D3: {@code types} referenced and
 * {@code fields} accessed in the body. At L1 these are best-effort syntactic names (no cross-module
 * resolution); they are refined to {@code can://} ids once resolution is available.
 */
@Data
public class JRefs {
    private List<String> types = new ArrayList<>();
    private List<String> fields = new ArrayList<>();
}
