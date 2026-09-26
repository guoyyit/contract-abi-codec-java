package com.github.guoyyit.contractabicodecjava.error;

/** Structured error codes for every failure mode. */
public enum ErrorCode {
    TYPE_MISMATCH,
    VALUE_OUT_OF_RANGE,
    INTEGER_OVERFLOW,
    INTEGER_TRUNCATION,
    NON_CANONICAL_ENCODING,
    OUT_OF_BOUNDS_READ,
    TRAILING_BYTES,
    DEPTH_EXCEEDED,
    INPUT_TOO_LARGE,
    FUNCTION_NOT_FOUND,
    SELECTOR_COLLISION,
    INVALID_SIGNATURE,
    LAYOUT_INVALID,
    PERSISTENCE_ERROR,
    CORRUPTED_STATE
}
