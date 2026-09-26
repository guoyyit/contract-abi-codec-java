package com.github.guoyyit.contractabicodecjava.model;

import java.util.List;

/**
 * Resolved encoding layout for one parameter list: whether the list is dynamic and
 * the size of its static head section (in 32-byte slots/bytes).
 * Cached so layout math is computed once per distinct type list.
 */
public record TypeLayout(List<String> canonicalTypes, boolean dynamic, int headSizeBytes) {

    public TypeLayout(List<AbiType> types) {
        this(types.stream().map(AbiType::canonicalName).toList(),
                types.stream().anyMatch(AbiType::isDynamic),
                headSize(types));
    }

    private static int headSize(List<AbiType> types) {
        int size = 0;
        for (AbiType t : types) {
            size += t.isDynamic() ? 32 : AbiStaticSize.of(t);
        }
        return size;
    }
}
