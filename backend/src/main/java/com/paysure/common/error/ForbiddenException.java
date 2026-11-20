package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/** 403: authenticated, but not allowed to act on this resource. */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
