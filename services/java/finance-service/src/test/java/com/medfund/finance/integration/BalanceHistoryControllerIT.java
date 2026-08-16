package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Phase 6 balance-history report. Extends
 * {@link AbstractIntegrationTest} so it runs on Testcontainers Postgres +
 * Kafka with the test-migration schema (V004 snapshots, V005 providers +
 * payment_runs). Snapshot rows are seeded directly — the "execute writes
 * snapshots" semantics live in {@code PaymentRunServiceTest} (deterministic,
 * no containers); here we prove the read path: join to payment_runs for run
 * context, newest-first ordering, asAtRun / currency filters, per-currency
 * latest-frozen totals, payee-name lookup, and the audited XLSX export.
 *
 * <p>The {@code RequiresReport} aspect queries {@code public.tenant_report_config};
 * the test schema has no such table, so it falls back to enabled.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(BalanceHistoryControllerIT.SecurityStub.class)
@WithTenant("11111111-1111-1111-1111-111111111111")
class BalanceHistoryControllerIT extends AbstractIntegrationTest {

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

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROVIDER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROVIDER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MEMBER_A   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID RUN_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID RUN_B = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final UUID RUN_C = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

    @Autowired private WebTestClient webTestClient;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void seed() {
        run("DELETE FROM provider_balance_snapshot");
        run("DELETE FROM member_balance_snapshot");
        run("DELETE FROM payment_runs");
        run("DELETE FROM providers");
        run("DELETE FROM members");

        insert("INSERT INTO providers (id, name) VALUES (:id, :name)",
                Map.of("id", PROVIDER_A, "name", "Sunrise Clinic"));
        insert("INSERT INTO providers (id, name) VALUES (:id, :name)",
                Map.of("id", PROVIDER_B, "name", "Lakeside Surgical"));
        insert("INSERT INTO members (id, first_name, last_name, member_number) VALUES (:id, :fn, :ln, :mn)",
                Map.of("id", MEMBER_A, "fn", "Ada", "ln", "Lovelace", "mn", "M-0001"));

        insert("INSERT INTO payment_runs (id, run_number, status, currency_code, executed_at) "
                        + "VALUES (:id, :runNumber, 'executed', 'USD', :executedAt)",
                Map.of("id", RUN_A, "runNumber", "PR-2026-001",
                        "executedAt", Instant.parse("2026-07-01T00:00:00Z")));
        insert("INSERT INTO payment_runs (id, run_number, status, currency_code, executed_at) "
                        + "VALUES (:id, :runNumber, 'executed', 'USD', :executedAt)",
                Map.of("id", RUN_B, "runNumber", "PR-2026-002",
                        "executedAt", Instant.parse("2026-08-01T00:00:00Z")));
        insert("INSERT INTO payment_runs (id, run_number, status, currency_code, executed_at) "
                        + "VALUES (:id, :runNumber, 'executed', 'USD', :executedAt)",
                Map.of("id", RUN_C, "runNumber", "PR-2026-003",
                        "executedAt", Instant.parse("2026-08-15T00:00:00Z")));

        insert("INSERT INTO provider_balance_snapshot (payment_run_id, provider_id, currency_code, "
                        + "opening_balance, closing_balance, total_claimed, total_approved, total_paid, "
                        + "net_due, taken_at) VALUES (:runId, :payeeId, :ccy, :open, :close, :claimed, "
                        + ":approved, :paid, :netDue, :takenAt)",
                Map.of("runId", RUN_A, "payeeId", PROVIDER_A, "ccy", "USD",
                        "open", new java.math.BigDecimal("500.00"),
                        "close", new java.math.BigDecimal("500.00"),
                        "claimed", new java.math.BigDecimal("1000.00"),
                        "approved", new java.math.BigDecimal("800.00"),
                        "paid", new java.math.BigDecimal("300.00"),
                        "netDue", new java.math.BigDecimal("300.00"),
                        "takenAt", Instant.parse("2026-07-01T00:00:00Z")));
        insert("INSERT INTO provider_balance_snapshot (payment_run_id, provider_id, currency_code, "
                        + "opening_balance, closing_balance, total_claimed, total_approved, total_paid, "
                        + "net_due, taken_at) VALUES (:runId, :payeeId, :ccy, :open, :close, :claimed, "
                        + ":approved, :paid, :netDue, :takenAt)",
                Map.of("runId", RUN_A, "payeeId", PROVIDER_A, "ccy", "ZAR",
                        "open", new java.math.BigDecimal("1000.00"),
                        "close", new java.math.BigDecimal("1000.00"),
                        "claimed", new java.math.BigDecimal("1500.00"),
                        "approved", new java.math.BigDecimal("1200.00"),
                        "paid", new java.math.BigDecimal("200.00"),
                        "netDue", new java.math.BigDecimal("200.00"),
                        "takenAt", Instant.parse("2026-07-01T00:00:00Z")));
        insert("INSERT INTO provider_balance_snapshot (payment_run_id, provider_id, currency_code, "
                        + "opening_balance, closing_balance, total_claimed, total_approved, total_paid, "
                        + "net_due, taken_at) VALUES (:runId, :payeeId, :ccy, :open, :close, :claimed, "
                        + ":approved, :paid, :netDue, :takenAt)",
                Map.of("runId", RUN_B, "payeeId", PROVIDER_A, "ccy", "USD",
                        "open", new java.math.BigDecimal("800.00"),
                        "close", new java.math.BigDecimal("800.00"),
                        "claimed", new java.math.BigDecimal("1200.00"),
                        "approved", new java.math.BigDecimal("1000.00"),
                        "paid", new java.math.BigDecimal("200.00"),
                        "netDue", new java.math.BigDecimal("250.00"),
                        "takenAt", Instant.parse("2026-08-01T00:00:00Z")));
        insert("INSERT INTO provider_balance_snapshot (payment_run_id, provider_id, currency_code, "
                        + "opening_balance, closing_balance, total_claimed, total_approved, total_paid, "
                        + "net_due, taken_at) VALUES (:runId, :payeeId, :ccy, :open, :close, :claimed, "
                        + ":approved, :paid, :netDue, :takenAt)",
                Map.of("runId", RUN_A, "payeeId", PROVIDER_B, "ccy", "USD",
                        "open", new java.math.BigDecimal("900.00"),
                        "close", new java.math.BigDecimal("900.00"),
                        "claimed", new java.math.BigDecimal("1500.00"),
                        "approved", new java.math.BigDecimal("1300.00"),
                        "paid", new java.math.BigDecimal("400.00"),
                        "netDue", new java.math.BigDecimal("450.00"),
                        "takenAt", Instant.parse("2026-07-01T00:00:00Z")));
        insert("INSERT INTO member_balance_snapshot (payment_run_id, member_id, currency_code, "
                        + "opening_balance, closing_balance, total_claimed, total_approved, total_paid, "
                        + "net_due, taken_at) VALUES (:runId, :payeeId, :ccy, :open, :close, :claimed, "
                        + ":approved, :paid, :netDue, :takenAt)",
                Map.of("runId", RUN_C, "payeeId", MEMBER_A, "ccy", "USD",
                        "open", new java.math.BigDecimal("420.00"),
                        "close", new java.math.BigDecimal("420.00"),
                        "claimed", new java.math.BigDecimal("600.00"),
                        "approved", new java.math.BigDecimal("500.00"),
                        "paid", new java.math.BigDecimal("80.00"),
                        "netDue", new java.math.BigDecimal("150.00"),
                        "takenAt", Instant.parse("2026-08-15T00:00:00Z")));
    }

    @Test
    void providerHistory_fullHistory_orderedNewestFirst_withRunContextAndPerCurrency() {
        webTestClient
                .get().uri("/api/v1/reports/balance-history/provider/" + PROVIDER_A)
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase6-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("PROVIDER_BALANCE_HISTORY")
                .jsonPath("$.data.payeeId").isEqualTo(PROVIDER_A.toString())
                .jsonPath("$.data.payeeName").isEqualTo("Sunrise Clinic")
                .jsonPath("$.data.rows.length()").isEqualTo(3)
                .jsonPath("$.data.rows[0].runNumber").isEqualTo("PR-2026-002")
                .jsonPath("$.data.rows[0].executedAt").isEqualTo("2026-08-01T00:00:00Z")
                .jsonPath("$.data.rows[0].closingBalance").isEqualTo(800.00)
                .jsonPath("$.data.rows[0].netDue").isEqualTo(250.00)
                .jsonPath("$.perCurrency.USD.totalAmount").isEqualTo(800.00)
                .jsonPath("$.perCurrency.ZAR.totalAmount").isEqualTo(1000.00)
                .jsonPath("$.warnings").isEmpty();
    }

    @Test
    void providerHistory_asAtRun_pinsExactlyOneRun() {
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/balance-history/provider/" + PROVIDER_A)
                        .queryParam("asAtRun", RUN_A.toString())
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase6-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.rows.length()").isEqualTo(2)
                .jsonPath("$.data.rows[0].runNumber").isEqualTo("PR-2026-001")
                .jsonPath("$.data.rows[1].runNumber").isEqualTo("PR-2026-001")
                .jsonPath("$.data.rows[0].currencyCode").isEqualTo("USD")
                .jsonPath("$.data.rows[1].currencyCode").isEqualTo("ZAR");
    }

    @Test
    void providerHistory_currencyFilter_narrowsToOneNativeCurrency() {
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/balance-history/provider/" + PROVIDER_A)
                        .queryParam("currency", "ZAR")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase6-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.rows.length()").isEqualTo(1)
                .jsonPath("$.data.rows[0].currencyCode").isEqualTo("ZAR")
                .jsonPath("$.data.rows[0].closingBalance").isEqualTo(1000.00)
                .jsonPath("$.perCurrency.USD.totalAmount").isEqualTo(800.00)
                .jsonPath("$.perCurrency.ZAR.totalAmount").isEqualTo(1000.00);
    }

    @Test
    void providerExcel_export_succeeds_andPublishesDataAccess() {
        webTestClient
                .get().uri("/api/v1/reports/balance-history/provider/" + PROVIDER_A + "/export/excel")
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase6-it")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        JsonNode event = consumeAuditEventContaining("medfund.security.events",
                "PROVIDER_BALANCE_HISTORY", Duration.ofSeconds(10));
        assertThat(event).isNotNull();
        assertThat(event.path("eventType").asText()).isEqualTo("DATA_ACCESS");
        assertThat(event.path("details").asText()).contains("PROVIDER_BALANCE_HISTORY");
        assertThat(event.path("details").asText()).contains(PROVIDER_A.toString());
    }

    @Test
    void memberHistory_returnsJoinedNameAndRows() {
        webTestClient
                .get().uri("/api/v1/reports/balance-history/member/" + MEMBER_A)
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase6-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("MEMBER_BALANCE_HISTORY")
                .jsonPath("$.data.payeeName").isEqualTo("Ada Lovelace")
                .jsonPath("$.data.rows.length()").isEqualTo(1)
                .jsonPath("$.data.rows[0].runNumber").isEqualTo("PR-2026-003")
                .jsonPath("$.data.rows[0].closingBalance").isEqualTo(420.00)
                .jsonPath("$.data.rows[0].netDue").isEqualTo(150.00)
                .jsonPath("$.perCurrency.USD.totalAmount").isEqualTo(420.00);
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

    private void run(String sql) {
        db.sql(sql).fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(5));
    }
}
