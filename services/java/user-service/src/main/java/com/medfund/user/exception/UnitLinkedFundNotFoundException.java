package com.medfund.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnitLinkedFundNotFoundException extends RuntimeException {
    public UnitLinkedFundNotFoundException(UUID id) {
        super("Unit-linked fund not found: " + id);
    }
}
