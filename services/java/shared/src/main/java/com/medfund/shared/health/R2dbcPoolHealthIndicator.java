package com.medfund.shared.health;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.PoolMetrics;
import io.r2dbc.spi.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reports DOWN when the R2DBC connection pool is fully saturated — every
 * allocated connection is in use and the idle count is zero. During
 * full-suite IT runs a saturated pool surfaces as opaque
 * "Failed to obtain R2DBC Connection" failures; this indicator makes the
 * condition visible on {@code /actuator/health} so developers can correlate.
 *
 * <p>Only registered when the {@link ConnectionFactory} bean is a
 * {@link ConnectionPool} — services that opt out of pooling do not get
 * this indicator.
 */
@Slf4j
@Component
@ConditionalOnBean(ConnectionFactory.class)
@ConditionalOnEnabledHealthIndicator("r2dbcPool")
@RequiredArgsConstructor
public class R2dbcPoolHealthIndicator implements ReactiveHealthIndicator {

    private static final double SATURATION_THRESHOLD = 0.9;

    private final ConnectionFactory connectionFactory;

    @Override
    public Mono<Health> health() {
        if (!(connectionFactory instanceof ConnectionPool pool)) {
            return Mono.just(Health.unknown()
                    .withDetail("reason", "connectionFactory is not a ConnectionPool")
                    .build());
        }
        return pool.getMetrics()
                .map(this::assess)
                .map(Mono::just)
                .orElseGet(() -> Mono.just(Health.unknown()
                        .withDetail("reason", "pool metrics not available")
                        .build()));
    }

    private Health assess(PoolMetrics metrics) {
        int acquired = metrics.acquiredSize();
        int idle = metrics.idleSize();
        int allocated = metrics.allocatedSize();
        int maxAllocated = metrics.getMaxAllocatedSize();
        double utilisation = maxAllocated == 0
                ? 0.0
                : (double) acquired / maxAllocated;

        Health.Builder builder = utilisation > SATURATION_THRESHOLD && idle == 0
                ? Health.down()
                : Health.up();

        return builder
                .withDetail("acquired", acquired)
                .withDetail("idle", idle)
                .withDetail("allocated", allocated)
                .withDetail("maxAllocated", maxAllocated)
                .withDetail("utilisation", String.format("%.2f", utilisation))
                .build();
    }
}
