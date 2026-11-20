package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/** 400 for semantic validation performed in the service layer (e.g. VPA syntax). */
public class BadRequestException extends ApiException {
    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
