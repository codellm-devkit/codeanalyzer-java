package com.ibm.cldk.entities;

import lombok.Data;

/** Represents an import declaration in a Java compilation unit. */
@Data
public class Import {
    private String path;
    private boolean isStatic = false;
    private boolean isWildcard = false;
}
