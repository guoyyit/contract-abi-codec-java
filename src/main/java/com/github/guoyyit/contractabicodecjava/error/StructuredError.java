package com.github.guoyyit.contractabicodecjava.error;

/** JSON/API friendly structured error body. */
public record StructuredError(String code, String path, String message) {
}
