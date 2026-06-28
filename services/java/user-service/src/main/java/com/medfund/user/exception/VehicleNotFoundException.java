package com.medfund.user.exception;

import java.util.UUID;

public class VehicleNotFoundException extends RuntimeException {
    public VehicleNotFoundException(UUID id) {
        super("Vehicle not found: " + id);
    }
    public VehicleNotFoundException(String registrationNumber) {
        super("Vehicle not found: " + registrationNumber);
    }
}
