package com.ibm.cldk.entities;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class JavaCompilationUnit {
    private String filePath;
    private String packageName;
    private List<Comment> comments = new ArrayList<>();
    private List<String> imports;
    private Map<String, Type> typeDeclarations;
    private boolean isModified;
}
