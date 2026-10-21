package com.medfund.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class FundNavHistoryNotFoundException extends RuntimeException {
    public FundNavHistoryNotFoundException(UUID id) {
        super("Fund NAV history row not found: " + id);
    }
}
