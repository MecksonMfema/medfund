package com.medfund.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class GroupLiaisonNotFoundException extends RuntimeException {
    public GroupLiaisonNotFoundException(UUID id) {
        super("Group liaison not found: " + id);
    }
}
