package com.medfund.user.exception;

import java.util.UUID;

public class LifePolicyNotFoundException extends RuntimeException {
    public LifePolicyNotFoundException(UUID id) {
        super("Life policy not found: " + id);
    }
    public LifePolicyNotFoundException(String policyNumber) {
        super("Life policy not found: " + policyNumber);
    }
}
