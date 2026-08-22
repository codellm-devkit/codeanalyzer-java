package com.ibm.cldk.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/** The root {@code application} node of the v2 CPG. */
@Data
public class JApplication {
    private String id;
    private String kind = "application";
    private Map<String, JModule> symbolTable = new LinkedHashMap<>();
}
