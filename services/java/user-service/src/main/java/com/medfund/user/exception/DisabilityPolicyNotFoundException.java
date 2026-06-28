package com.medfund.user.exception;

import java.util.UUID;

public class DisabilityPolicyNotFoundException extends RuntimeException {
    public DisabilityPolicyNotFoundException(UUID id) {
        super("Disability policy not found: " + id);
    }
    public DisabilityPolicyNotFoundException(String policyNumber) {
        super("Disability policy not found: " + policyNumber);
    }
}
