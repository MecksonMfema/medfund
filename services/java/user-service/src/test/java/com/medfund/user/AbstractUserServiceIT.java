package com.medfund.user;

import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;

/**
 * Marker base for user-service integration tests. Extends the shared
 * Postgres-only fixture; user-service ITs today don't need Kafka. If a
 * future user-service IT needs Kafka round-trip (e.g. the Phase 12
 * PolicyIssuedPublisherIT), extend
 * {@link com.medfund.shared.testfixtures.AbstractIntegrationTest} directly
 * instead so the extra container is opt-in.
 *
 * <p>This is Phase 1 Deviation shape (2026-08-23). Original plan called
 * for a marker over {@link com.medfund.shared.testfixtures.AbstractIntegrationTest}
 * — moved to the split base so unused Kafka containers don't spin up.
 */
public abstract class AbstractUserServiceIT extends AbstractPostgresIntegrationTest {
}
