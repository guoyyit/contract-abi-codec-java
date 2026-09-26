package com.github.guoyyit.contractabicodecjava.model;

/** One named function parameter. */
public record Parameter(String name, AbiType type) {
    public Parameter {
        if (type == null) {
            throw new IllegalArgumentException("parameter type is required");
        }
        if (name == null) {
            name = "";
        }
    }
}
