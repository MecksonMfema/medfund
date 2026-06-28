package com.medfund.user.exception;

import java.util.UUID;

public class FuneralPolicyNotFoundException extends RuntimeException {
    public FuneralPolicyNotFoundException(UUID id) {
        super("FuneralPolicy not found: " + id);
    }
    public FuneralPolicyNotFoundException(String policyNumber) {
        super("FuneralPolicy not found: " + policyNumber);
    }
}
