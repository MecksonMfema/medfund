package com.medfund.shared.testfixtures;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 callback that reads the {@link WithTenant} annotation (method first,
 * then class) and stashes the value in {@link TenantTestContext} for the
 * lifetime of the test method. Always clears the slot in {@code afterEach}
 * so a tenant leak from a missing annotation surfaces as a fast failure
 * instead of mysteriously polluting the next test.
 */
public class TenantContextExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        WithTenant annotation = context.getElement()
            .map(e -> e.getAnnotation(WithTenant.class))
            .orElse(null);
        if (annotation == null && context.getTestClass().isPresent()) {
            annotation = context.getTestClass().get().getAnnotation(WithTenant.class);
        }
        if (annotation != null) {
            TenantTestContext.set(annotation.value());
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TenantTestContext.clear();
    }
}
