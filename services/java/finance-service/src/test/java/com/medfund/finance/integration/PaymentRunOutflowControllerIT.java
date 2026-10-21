package com.medfund.finance.integration;

import com.medfund.shared.testfixtures.AbstractPostgresIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
 * Integration test for the Phase 8 outflows feed — the narrow
 * service-to-service aggregate the 13-week cash-flow forecast consumes
 * (D8-5). Runs on Testcontainers Postgres against the test-migration schema
 * (V005 payment_runs + V006 payment_run_items) and pins the filter rule set:
 * only draft/approved runs with pending/scheduled items whose created_at
 * falls in the window come back; executed runs and withheld/paid items never
 * do.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(PaymentRunOutflowControllerIT.SecurityStub.class)
@WithTenant("11111111-1111-1111-1111-111111111111")
class PaymentRunOutflowControllerIT extends AbstractPostgresIntegrationTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private DatabaseClient db;

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final UUID RUN_IN_WINDOW = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RUN_OUTSIDE   = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID RUN_EXECUTED  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

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

    private void seed() {
        insert("DELETE FROM payment_run_items");
        insert("DELETE FROM payment_runs");
        insert("INSERT INTO payment_runs (id, run_number, status, total_amount, currency_code, "
                    + "payment_count, description, created_at) "
                    + "VALUES (:id, :runNumber, 'draft', 0, 'USD', 0, 'test', :createdAt)",
                Map.of("id", RUN_IN_WINDOW, "runNumber", "RUN-1",
                        "createdAt", Instant.parse("2026-07-20T10:00:00Z")));
        insert("INSERT INTO payment_runs (id, run_number, status, total_amount, currency_code, "
                    + "payment_count, description, created_at) "
                    + "VALUES (:id, :runNumber, 'approved', 0, 'USD', 0, 'test', :createdAt)",
                Map.of("id", RUN_OUTSIDE, "runNumber", "RUN-2",
                        "createdAt", Instant.parse("2026-06-01T10:00:00Z")));
        insert("INSERT INTO payment_runs (id, run_number, status, total_amount, currency_code, "
                    + "payment_count, description, created_at) "
                    + "VALUES (:id, :runNumber, 'executed', 0, 'USD', 0, 'test', :createdAt)",
                Map.of("id", RUN_EXECUTED, "runNumber", "RUN-3",
                        "createdAt", Instant.parse("2026-07-21T10:00:00Z")));
        insert("INSERT INTO payment_run_items (id, payment_run_id, amount, currency_code, status, created_at) "
                    + "VALUES (:id, :runId, 100.00, 'USD', 'pending', :createdAt)",
                Map.of("id", UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"), "runId", RUN_IN_WINDOW,
                        "createdAt", Instant.parse("2026-07-20T10:00:00Z")));
        insert("INSERT INTO payment_run_items (id, payment_run_id, amount, currency_code, status, created_at) "
                    + "VALUES (:id, :runId, 250.00, 'USD', 'withheld', :createdAt)",
                Map.of("id", UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"), "runId", RUN_IN_WINDOW,
                        "createdAt", Instant.parse("2026-07-20T10:00:00Z")));
        insert("INSERT INTO payment_run_items (id, payment_run_id, amount, currency_code, status, created_at) "
                    + "VALUES (:id, :runId, 300.00, 'USD', 'scheduled', :createdAt)",
                Map.of("id", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), "runId", RUN_OUTSIDE,
                        "createdAt", Instant.parse("2026-06-01T10:00:00Z")));
        insert("INSERT INTO payment_run_items (id, payment_run_id, amount, currency_code, status, created_at) "
                    + "VALUES (:id, :runId, 400.00, 'USD', 'paid', :createdAt)",
                Map.of("id", UUID.fromString("11111111-2222-3333-4444-555555555555"), "runId", RUN_EXECUTED,
                        "createdAt", Instant.parse("2026-07-21T10:00:00Z")));
    }

    private void insert(String sql, Map<String, Object> params) {
        var spec = db.sql(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        spec.fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    private void insert(String sql) {
        db.sql(sql).fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }

    @Test
    void plannedOutflows_returnsOnlyPayableItemsInWindow() {
        seed();

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/outflows")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("CASH_FLOW_FORECAST_13W")
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].runId").isEqualTo(RUN_IN_WINDOW.toString())
                .jsonPath("$.data[0].runNumber").isEqualTo("RUN-1")
                .jsonPath("$.data[0].currencyCode").isEqualTo("USD")
                .jsonPath("$.data[0].amount").isEqualTo(100.00)
                .jsonPath("$.data[0].runStatus").isEqualTo("draft")
                .jsonPath("$.data[0].itemStatus").isEqualTo("pending");
    }

    @Test
    void plannedOutflows_emptyWindow_returnsEmptyArray() {
        seed();

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/outflows")
                        .queryParam("periodStart", "2026-05-01")
                        .queryParam("periodEnd",   "2026-05-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("CASH_FLOW_FORECAST_13W")
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void plannedOutflows_windowBoundary_isInclusiveStartExclusiveEnd() {
        seed();

        // Start exactly the run's created_at day → item included.
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/outflows")
                        .queryParam("periodStart", "2026-07-20")
                        .queryParam("periodEnd",   "2026-07-20")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1);

        // End exactly the day before → run excluded (created 07-20).
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/aggregate/outflows")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-07-19")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);
    }
}
