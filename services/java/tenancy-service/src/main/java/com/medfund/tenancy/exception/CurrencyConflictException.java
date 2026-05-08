package com.medfund.tenancy.exception;

public class CurrencyConflictException extends RuntimeException {
    public CurrencyConflictException(String message) {
        super(message);
    }
}
