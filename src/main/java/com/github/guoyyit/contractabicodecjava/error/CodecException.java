package com.github.guoyyit.contractabicodecjava.error;

/** Structured codec/registry error. No partial result ever accompanies this exception. */
public class CodecException extends RuntimeException {

    private final ErrorCode code;
    private final String path;

    public CodecException(ErrorCode code, String message) {
        this(code, null, message);
    }

    public CodecException(ErrorCode code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public CodecException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.path = null;
    }

    public ErrorCode code() { return code; }
    public String path() { return path; }

    public StructuredError toStructuredError() {
        return new StructuredError(code.name(), path, getMessage());
    }
}
