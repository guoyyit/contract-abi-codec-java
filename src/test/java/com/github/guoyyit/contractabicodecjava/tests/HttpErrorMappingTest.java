package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.web.CodecExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Storage faults are server errors; caller input problems remain 4xx. */
class HttpErrorMappingTest {

    private final CodecExceptionHandler handler = new CodecExceptionHandler();

    private HttpStatusCode status(ErrorCode code) {
        return handler.handle(new CodecException(code, "test")).getStatusCode();
    }

    @Test
    void persistenceAndCorruptionAreServerErrors() {
        assertEquals(HttpStatusCode.valueOf(500), status(ErrorCode.PERSISTENCE_ERROR));
        assertEquals(HttpStatusCode.valueOf(500), status(ErrorCode.CORRUPTED_STATE));
    }

    @Test
    void callerInputErrorsRemainClientErrors() {
        assertEquals(HttpStatusCode.valueOf(400), status(ErrorCode.INVALID_SIGNATURE));
        assertEquals(HttpStatusCode.valueOf(400), status(ErrorCode.OUT_OF_BOUNDS_READ));
        assertEquals(HttpStatusCode.valueOf(400), status(ErrorCode.NON_CANONICAL_ENCODING));
        assertEquals(HttpStatusCode.valueOf(404), status(ErrorCode.FUNCTION_NOT_FOUND));
        assertEquals(HttpStatusCode.valueOf(409), status(ErrorCode.SELECTOR_COLLISION));
        assertEquals(HttpStatusCode.valueOf(413), status(ErrorCode.INPUT_TOO_LARGE));
    }
}
