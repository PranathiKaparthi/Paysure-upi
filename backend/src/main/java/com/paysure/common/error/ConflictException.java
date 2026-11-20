package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/** 409: the request conflicts with current state (duplicate e-mail, VPA already taken, ...). */
public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
