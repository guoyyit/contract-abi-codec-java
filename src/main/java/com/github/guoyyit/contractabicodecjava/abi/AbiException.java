package com.github.guoyyit.contractabicodecjava.abi;

/**
 * 结构化编解码错误。携带错误码、字节偏移与可读消息，不携带部分结果。
 */
public class AbiException extends RuntimeException {

    public enum ErrorCode {
        TYPE_MISMATCH,
        OUT_OF_BOUNDS,
        TRAILING_BYTES,
        INTEGER_OVERFLOW,
        TRUNCATION,
        NON_CANONICAL,
        DEPTH_LIMIT_EXCEEDED,
        INPUT_LIMIT_EXCEEDED,
        SELECTOR_COLLISION,
        UNKNOWN_SELECTOR,
        INVALID_SIGNATURE,
        PERSISTENCE_CORRUPT
    }

    private final ErrorCode code;
    private final long offset;

    public AbiException(ErrorCode code, String message) {
        this(code, -1L, message);
    }

    public AbiException(ErrorCode code, long offset, String message) {
        super(message);
        this.code = code;
        this.offset = offset;
    }

    public ErrorCode code() {
        return code;
    }

    /** 出错位置的字节偏移，未知时为 -1 */
    public long offset() {
        return offset;
    }
}
