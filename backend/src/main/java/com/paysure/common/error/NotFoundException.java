package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/** 404. Also used for resources the caller does not own, so their existence is not leaked. */
public class NotFoundException extends ApiException {
    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
