package com.medfund.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class VariableFeeScheduleNotFoundException extends RuntimeException {
    public VariableFeeScheduleNotFoundException(UUID id) {
        super("Variable fee schedule row not found: " + id);
    }
}
