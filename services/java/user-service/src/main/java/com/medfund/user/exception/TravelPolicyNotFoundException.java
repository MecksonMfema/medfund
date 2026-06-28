package com.medfund.user.exception;

import java.util.UUID;

public class TravelPolicyNotFoundException extends RuntimeException {
    public TravelPolicyNotFoundException(UUID id) {
        super("Travel policy not found: " + id);
    }
    public TravelPolicyNotFoundException(String policyNumber) {
        super("Travel policy not found: " + policyNumber);
    }
}
