package com.ibm.cldk.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class JavaCompilationUnit {
    private String filePath;
    private String packageName;
    private List<Comment> comments = new ArrayList<>();
    private List<Import> imports;
    private Map<String, Type> typeDeclarations;
    private boolean isModified;
}
