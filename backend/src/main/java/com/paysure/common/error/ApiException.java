package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected, client-visible business errors. Each carries a stable
 * machine-readable {@code code} that is returned in the RFC 7807 problem body.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
