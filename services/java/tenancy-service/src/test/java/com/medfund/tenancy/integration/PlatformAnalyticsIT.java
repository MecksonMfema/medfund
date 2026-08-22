package com.medfund.tenancy.integration;

import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.tenancy.TenancyServiceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration test for the Phase 9 tenant-growth raw-row feed. Boots the full
 * tenancy-service context against Testcontainers Postgres with the shared
 * {@code db/test-migration} baseline (V001 provisions a {@code tenants}
 * table), seeds a few tenant rows, and asserts that {@code /tenant-growth}
 * returns the raw {@code created_at} timestamps ready for the gateway
 * bucketing engine.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = TenancyServiceApplication.class)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.out-of-order=false"
})
@Import(PlatformAnalyticsIT.SecurityStub.class)
class PlatformAnalyticsIT extends AbstractPostgresIntegrationTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private DatabaseClient db;

    private static final UUID TENANT_1 = UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID TENANT_2 = UUID.fromString("00000000-0000-4000-8000-000000000011");

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test",
                            "realm_access", Map.of("roles", List.of("super_admin")))));
        }
    }

    @BeforeEach
    void seed() {
        // Wipe extras from prior test methods; V001 seeds one row we leave alone.
        run("DELETE FROM tenants WHERE id IN (:a, :b)",
                Map.of("a", TENANT_1, "b", TENANT_2));

        run("INSERT INTO tenants (id, slug, schema_name, created_at) " +
            "VALUES (:id, :slug, :schema, :createdAt)",
                Map.of("id", TENANT_1, "slug", "alpha", "schema", "tenant_alpha",
                        "createdAt", Instant.parse("2026-01-15T10:00:00Z")));
        run("INSERT INTO tenants (id, slug, schema_name, created_at) " +
            "VALUES (:id, :slug, :schema, :createdAt)",
                Map.of("id", TENANT_2, "slug", "beta", "schema", "tenant_beta",
                        "createdAt", Instant.parse("2026-03-20T10:00:00Z")));
    }

    private void run(String sql, Map<String, Object> params) {
        var spec = db.sql(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        spec.fetch().rowsUpdated().block(Duration.ofSeconds(5));
    }

    @Test
    void tenantGrowth_returnsCreatedAtTimestamps() {
        // V001 seed row + the 2 rows above → at minimum 3 rows come back.
        webTestClient.get().uri("/api/v1/platform/tenant-growth")
                .header("Authorization", "Bearer analytics-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").value(v -> {
                    int size = (Integer) v;
                    if (size < 3) {
                        throw new AssertionError("expected ≥3 tenant rows, got " + size);
                    }
                })
                .jsonPath("$[?(@.ts == '2026-01-15T10:00:00Z')]").exists()
                .jsonPath("$[?(@.ts == '2026-03-20T10:00:00Z')]").exists();
    }
}
