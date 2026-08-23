package com.ibm.cldk.schema;

import lombok.Data;

/**
 * A callable outside the project that a call site targets, homed in {@code external_symbols} so no
 * edge dangles (§2, D19). The map key is its {@code @external} can-id — which carries the <em>binary</em>
 * type name ({@code java.util.Map$Entry}) for exactness and to join WALA natively — so the id is not
 * repeated here. The fields are the {@code kind} ({@code method}|{@code constructor}), the erased
 * {@code signature}, and the source-legible dotted {@code declaring_type} ({@code java.util.Map.Entry}).
 */
@Data
public class JExternalSymbol {
    private String kind;
    private String signature;
    private String declaringType;
}
