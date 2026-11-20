package com.paysure.common.error;

import org.springframework.http.HttpStatus;

/** 422: the request is well-formed but violates a business rule (limits, inactive account, funds). */
public class BusinessRuleException extends ApiException {
    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
