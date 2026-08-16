package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Phase 7 payment-run workbook export. Extends
 * {@link AbstractIntegrationTest} so it runs on Testcontainers Postgres +
 * Kafka with the test-migration schema (V005 providers + payment_runs,
 * V006 payment_run_items + payments). Seeds a run with USD + ZWL items
 * (provider and member payees) and proves the read path end-to-end:
 * multi-sheet workbook with per-currency sheets + summary, grand-total
 * reconciliation, the joined payee name, the audited export, and the
 * 403 gate for users without FINANCE_VIEW_SUBLEDGER.
 *
 * <p>{@code RequiresReport} queries {@code public.tenant_report_config};
 * the test schema has no such table, so it falls back to enabled.
 * {@code ReportingCurrencyResolver} likewise falls back to {@code USD},
 * so the summary conversion short-circuits (run currency = reporting
 * currency) without needing an exchange_rates fixture.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(PaymentRunWorkbookControllerIT.SecurityStub.class)
@WithTenant("11111111-1111-1111-1111-111111111111")
class PaymentRunWorkbookControllerIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> {
                boolean admin = token.contains("admin");
                return Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test",
                            "realm_access", Map.of("roles",
                                    admin ? List.of("super_admin")
                                          : List.of("finance:view_payments")))));
            };
        }
    }

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROVIDER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID MEMBER_A   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID RUN_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID PAYMENT_1 = UUID.fromString("11111111-1111-4000-8000-111111111111");
    private static final UUID PAYMENT_2 = UUID.fromString("22222222-2222-4000-8000-222222222222");

    @Autowired private WebTestClient webTestClient;
    @Autowired private DatabaseClient db;

    @BeforeEach
    void seed() {
        run("DELETE FROM payment_run_items");
        run("DELETE FROM payments");
        run("DELETE FROM payment_runs");
        run("DELETE FROM providers");
        run("DELETE FROM members");

        insert("INSERT INTO providers (id, name) VALUES (:id, :name)",
                Map.of("id", PROVIDER_A, "name", "Sunrise Clinic"));
        insert("INSERT INTO members (id, first_name, last_name, member_number) VALUES (:id, :fn, :ln, :mn)",
                Map.of("id", MEMBER_A, "fn", "Ada", "ln", "Lovelace", "mn", "M-0001"));

        insert("INSERT INTO payment_runs (id, run_number, status, currency_code, payment_count, executed_at) "
                        + "VALUES (:id, :runNumber, 'executed', 'USD', :count, :executedAt)",
                Map.of("id", RUN_A, "runNumber", "PR-2026-100", "count", 3,
                        "executedAt", Instant.parse("2026-07-01T00:00:00Z")));

        insert("INSERT INTO payments (id, payment_number, provider_id, payee_type, amount, currency_code, "
                        + "payment_type, status, payment_method, reference, paid_at) VALUES "
                        + "(:id, :num, :providerId, 'PROVIDER', :amount, 'USD', 'CLAIM_SETTLEMENT', 'paid', "
                        + "'EFT', :ref, :paidAt)",
                Map.of("id", PAYMENT_1, "num", "PAY-2026-001", "providerId", PROVIDER_A,
                        "amount", new BigDecimal("100.00"), "ref", "REF-1",
                        "paidAt", Instant.parse("2026-07-02T00:00:00Z")));
        insert("INSERT INTO payments (id, payment_number, provider_id, payee_type, amount, currency_code, "
                        + "payment_type, status, payment_method, reference, paid_at) VALUES "
                        + "(:id, :num, :providerId, 'PROVIDER', :amount, 'USD', 'CLAIM_SETTLEMENT', 'paid', "
                        + "'EFT', :ref, :paidAt)",
                Map.of("id", PAYMENT_2, "num", "PAY-2026-002", "providerId", PROVIDER_A,
                        "amount", new BigDecimal("250.50"), "ref", "REF-2",
                        "paidAt", Instant.parse("2026-07-02T00:00:00Z")));

        insert("INSERT INTO payment_run_items (id, payment_run_id, payment_id, provider_id, payee_type, "
                        + "amount, currency_code, status, created_at) VALUES "
                        + "(:id, :runId, :paymentId, :providerId, 'PROVIDER', :amount, 'USD', 'paid', :createdAt)",
                Map.of("id", UUID.randomUUID(), "runId", RUN_A, "paymentId", PAYMENT_1,
                        "providerId", PROVIDER_A, "amount", new BigDecimal("100.00"),
                        "createdAt", Instant.parse("2026-06-30T10:00:00Z")));
        insert("INSERT INTO payment_run_items (id, payment_run_id, payment_id, provider_id, payee_type, "
                        + "amount, currency_code, status, created_at) VALUES "
                        + "(:id, :runId, :paymentId, :providerId, 'PROVIDER', :amount, 'USD', 'scheduled', :createdAt)",
                Map.of("id", UUID.randomUUID(), "runId", RUN_A, "paymentId", PAYMENT_2,
                        "providerId", PROVIDER_A, "amount", new BigDecimal("250.50"),
                        "createdAt", Instant.parse("2026-06-30T10:01:00Z")));
        insert("INSERT INTO payment_run_items (id, payment_run_id, member_id, payee_type, "
                        + "amount, currency_code, status, created_at) VALUES "
                        + "(:id, :runId, :memberId, 'MEMBER', :amount, 'ZWL', 'pending', :createdAt)",
                Map.of("id", UUID.randomUUID(), "runId", RUN_A, "memberId", MEMBER_A,
                        "amount", new BigDecimal("5000.00"),
                        "createdAt", Instant.parse("2026-06-30T10:02:00Z")));
    }

    @Test
    void exportWorkbook_succeeds_publishesDataAccess_andSheetsReconcile() {
        byte[] bytes = webTestClient
                .get().uri("/api/v1/payment-runs/" + RUN_A + "/export/excel")
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase7-admin")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().value("Content-Disposition", v -> assertThat(v)
                        .contains("payment-run-PR-2026-100.xlsx"))
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // D7-1: one sheet per distinct currency + summary.
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            Sheet usd = wb.getSheet("Payment USD");
            Sheet zwl = wb.getSheet("Payment ZWL");
            Sheet summary = wb.getSheet("Summary");
            assertThat(usd).isNotNull();
            assertThat(zwl).isNotNull();
            assertThat(summary).isNotNull();

            // D7-4: provider payee name resolved through the join.
            boolean sawProvider = false;
            var it = usd.rowIterator();
            while (it.hasNext()) {
                var row = it.next();
                if (row.getCell(1) != null && "Sunrise Clinic".equals(row.getCell(1).toString())) {
                    sawProvider = true;
                }
            }
            assertThat(sawProvider).isTrue();

            // D7-2: grand total reconciles to the USD item sum.
            boolean reconciled = false;
            var sit = summary.rowIterator();
            while (sit.hasNext()) {
                var row = sit.next();
                if (row.getCell(0) != null && row.getCell(0).toString().contains("Grand total")
                        && row.getCell(1) != null && "350.5".equals(row.getCell(1).toString())) {
                    reconciled = true;
                }
            }
            assertThat(reconciled).isTrue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        JsonNode event = consumeAuditEventContaining("medfund.security.events",
                "PAYMENT_RUN_WORKBOOK", Duration.ofSeconds(10));
        assertThat(event).isNotNull();
        assertThat(event.path("eventType").asText()).isEqualTo("DATA_ACCESS");
        assertThat(event.path("details").asText()).contains("PAYMENT_RUN_WORKBOOK");
        assertThat(event.path("details").asText()).contains(RUN_A.toString());
    }

    @Test
    void exportWorkbook_missingPermission_forbidden() {
        webTestClient
                .get().uri("/api/v1/payment-runs/" + RUN_A + "/export/excel")
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase7-viewer")
                .exchange()
                .expectStatus().isForbidden();
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
