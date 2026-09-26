package com.github.guoyyit.contractabicodecjava.web;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps structured codec errors onto stable HTTP status codes. */
@RestControllerAdvice
public class CodecExceptionHandler {

    @ExceptionHandler(CodecException.class)
    public ResponseEntity<ErrorBody> handle(CodecException e) {
        HttpStatus status = switch (e.code()) {
            case FUNCTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SELECTOR_COLLISION -> HttpStatus.CONFLICT;
            case INPUT_TOO_LARGE, DEPTH_EXCEEDED -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorBody(
                e.code().name(), e.path(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("INVALID_SIGNATURE", null, e.getMessage()));
    }

    public record ErrorBody(String code, String path, String message) {}
}
