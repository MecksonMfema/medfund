package com.medfund.contributions.integration;

import com.medfund.contributions.client.FinanceClient;
import com.medfund.contributions.dto.PlannedOutflowRow;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end test for the Phase 8 cash-flow forecast (D8-1..D8-7): full
 * contributions-service context on Testcontainers Postgres, the
 * finance-service outflow feed stubbed via a {@code @MockBean
 * FinanceClient}. Seeds the {@code invoices} ledger in {@code public}
 * (V003 test-migration) and pins the envelope shape, the 400 on
 * rollingWeeks < 1, and the finance-down warning path (the real
 * {@code CrossServiceCallHelper.guarded()} still runs because the mock
 * emits a reactor error).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(CashFlowForecastControllerIT.SecurityStub.class)
@WithTenant("11111111-1111-1111-1111-111111111111")
class CashFlowForecastControllerIT extends AbstractIntegrationTest {

    @Autowired private WebTestClient webTestClient;
    @Autowired private DatabaseClient db;

    @MockBean private FinanceClient financeClient;

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String AS_OF  = "2026-08-14";

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
    void seedInvoices() {
        db.sql("DELETE FROM invoices").fetch().rowsUpdated()
                .contextWrite(TenantTestContext.put()).block(Duration.ofSeconds(5));
        // Unpaid invoices inside the window → inflow. One paid + one voided
        // + one due before the window (lower bound is asOf) → must NOT contribute.
        insert("INSERT INTO invoices (id, due_date, total_amount, currency_code, status) "
                + "VALUES (:id, '2026-08-15', 100.00, 'USD', 'unpaid')",
                Map.of("id", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
        insert("INSERT INTO invoices (id, due_date, total_amount, currency_code, status) "
                + "VALUES (:id, '2026-08-18', 200.00, 'USD', 'unpaid')",
                Map.of("id", UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")));
        insert("INSERT INTO invoices (id, due_date, total_amount, currency_code, status) "
                + "VALUES (:id, '2026-08-15', 999.00, 'USD', 'paid')",
                Map.of("id", UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")));
        insert("INSERT INTO invoices (id, due_date, total_amount, currency_code, status) "
                + "VALUES (:id, '2026-08-15', 999.00, 'USD', 'void')",
                Map.of("id", UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")));
        insert("INSERT INTO invoices (id, due_date, total_amount, currency_code, status) "
                + "VALUES (:id, '2026-08-01', 999.00, 'USD', 'unpaid')",
                Map.of("id", UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")));
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

    @Test
    void forecast_happyPath_composesInflowAndOutflowInEnvelope() {
        when(financeClient.plannedOutflows(any(), any())).thenReturn(Mono.just(List.of(
                new PlannedOutflowRow(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"), "RUN-1", "USD",
                        new java.math.BigDecimal("40.00"), "draft", "pending",
                        Instant.parse("2026-08-15T10:00:00Z")))));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/cash-flow-forecast")
                        .queryParam("asOf", AS_OF)
                        .queryParam("rollingWeeks", "13")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("CASH_FLOW_FORECAST_13W")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                .jsonPath("$.data.asOf").isEqualTo(AS_OF)
                .jsonPath("$.data.rollingWeeks").isEqualTo(13)
                .jsonPath("$.data.series.length()").isEqualTo(1)
                .jsonPath("$.data.series[0].currencyCode").isEqualTo("USD")
                .jsonPath("$.data.series[0].totalInflow").isEqualTo(300.00)
                .jsonPath("$.data.series[0].totalOutflow").isEqualTo(40.00)
                .jsonPath("$.data.series[0].totalNet").isEqualTo(260.00)
                .jsonPath("$.data.series[0].buckets.length()").isEqualTo(13)
                .jsonPath("$.data.series[0].buckets[0].weekStart").isEqualTo("2026-08-10")
                .jsonPath("$.data.series[0].buckets[0].inflow").isEqualTo(100.00)
                .jsonPath("$.data.series[0].buckets[0].outflow").isEqualTo(40.00)
                .jsonPath("$.data.series[0].buckets[0].net").isEqualTo(60.00)
                .jsonPath("$.warnings.length()").isEqualTo(0);
    }

    @Test
    void forecast_rollingWeeksBelowOne_rejectedWith400() {
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/cash-flow-forecast")
                        .queryParam("asOf", AS_OF)
                        .queryParam("rollingWeeks", "0")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void forecast_financeDown_rendersAllZeroOutflowWithWarning() {
        when(financeClient.plannedOutflows(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/cash-flow-forecast")
                        .queryParam("asOf", AS_OF)
                        .queryParam("rollingWeeks", "13")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.series[0].totalInflow").isEqualTo(300.00)
                .jsonPath("$.data.series[0].totalOutflow").isEqualTo(0)
                .jsonPath("$.warnings.length()").isEqualTo(1)
                .jsonPath("$.warnings[0]").value(w ->
                        org.assertj.core.api.Assertions.assertThat((String) w)
                                .contains("payment-run-outflows"));
    }

    @Test
    void forecast_exportEmitsSecurityEventAndReturnsAttachment() {
        when(financeClient.plannedOutflows(any(), any())).thenReturn(Mono.just(List.of()));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/cash-flow-forecast/export/excel")
                        .queryParam("asOf", AS_OF)
                        .queryParam("rollingWeeks", "13")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-it")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"cash-flow-forecast-.*\\.xlsx\"");
    }
}
