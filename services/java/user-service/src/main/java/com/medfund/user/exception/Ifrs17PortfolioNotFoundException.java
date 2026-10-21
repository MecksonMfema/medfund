package com.medfund.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class Ifrs17PortfolioNotFoundException extends RuntimeException {
    public Ifrs17PortfolioNotFoundException(UUID id) {
        super("IFRS 17 portfolio not found: " + id);
    }
}
