package com.ibm.cldk.entities;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class Field {
    private Comment comment;
    private String name;
    private String type;
    private Integer startLine;
    private Integer endLine;
    private List<String> variables;
    private List<String> modifiers;
    private List<String> annotations;
    private Map<String, String> variableInitializers;
}
