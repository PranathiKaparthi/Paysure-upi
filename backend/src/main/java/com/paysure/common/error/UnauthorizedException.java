package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/** 401 raised by the application (e.g. bad login). Messages must never reveal which field was wrong. */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
