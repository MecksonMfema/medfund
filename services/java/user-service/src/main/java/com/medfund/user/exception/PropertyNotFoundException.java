package com.medfund.user.exception;

import java.util.UUID;

public class PropertyNotFoundException extends RuntimeException {
    public PropertyNotFoundException(UUID id) {
        super("Property not found: " + id);
    }
    public PropertyNotFoundException(String propertyName) {
        super("Property not found: " + propertyName);
    }
}
