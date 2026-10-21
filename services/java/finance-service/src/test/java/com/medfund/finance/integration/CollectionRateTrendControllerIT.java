package com.medfund.finance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.WithTenant;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Phase 8 portfolio-level collection-rate trend.
 * Runs the full finance-service context (Testcontainers Postgres + Kafka)
 * with the contributions peer stubbed via okhttp MockWebServer — the
 * {@code services.contributions.base-url} points at the stub, so the real
 * WebClient fanout, decode, and {@code CrossServiceCallHelper} guard all
 * run. Proves the flattened (month, currency, billed, received, ratePct)
 * strip reconciles to the six dimension aggregates, the peer-down warning
 * banner, the audited export, and the 403 gate.
 *
 * <p>{@code RequiresReport} queries {@code public.tenant_report_config};
 * the test schema has no such table, so it falls back to enabled — the
 * gate is proven via {@code FINANCE_VIEW_SUBLEDGER} (mirroring
 * PaymentRunWorkbookControllerIT).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(CollectionRateTrendControllerIT.SecurityStub.class)
@WithTenant("11111111-1111-1111-1111-111111111111")
class CollectionRateTrendControllerIT extends AbstractIntegrationTest {

    @Autowired private WebTestClient webTestClient;

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SCHEME_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GROUP_ID  = "22222222-2222-2222-2222-222222222222";
    private static final String MEMBER_ID = "33333333-3333-3333-3333-333333333333";

    private static final MockWebServer CONTRIBUTIONS = new MockWebServer();

    static {
        try {
            CONTRIBUTIONS.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to start MockWebServer stub", e);
        }
    }

    @AfterAll
    static void shutdownStubs() throws java.io.IOException {
        CONTRIBUTIONS.shutdown();
    }

    @DynamicPropertySource
    static void peerUrls(DynamicPropertyRegistry registry) {
        registry.add("services.contributions.base-url", () -> CONTRIBUTIONS.url("/").toString());
    }

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

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    private static String envelope(String reportKey, String dataJson) {
        return "{\"reportKey\":\"" + reportKey + "\",\"period\":null,\"reportingCurrency\":\"USD\","
                + "\"data\":" + dataJson + ",\"perCurrency\":{},\"fxRates\":{},"
                + "\"warnings\":[],\"generatedAt\":\"2026-08-16T10:00:00Z\"}";
    }

    /** Aggregate rows JSON for one dimension: {month, totalAmount} pairs. */
    private static String monthlyRows(String dimension, String dimensionId, Object[][] byMonth) {
        StringBuilder sb = new StringBuilder("[");
        for (Object[] m : byMonth) {
            if (sb.length() > 1) sb.append(",");
            sb.append("{\"dimension\":\"").append(dimension)
              .append("\",\"dimensionId\":\"").append(dimensionId)
              .append("\",\"dimensionName\":\"").append(dimension)
              .append("\",\"currencyCode\":\"USD\"")
              .append(",\"month\":\"").append(m[0])
              .append("\",\"totalAmount\":").append(m[1]).append("}");
        }
        return sb.append("]").toString();
    }

    /**
     * Routes on path + {@code dimension} query param so each of the six
     * fanout calls decodes its own slice — the order in which
     * {@code Mono.zip} fires them never matters.
     */
    private static Dispatcher aggregateDispatcher(
            Map<String, String> billingByDimension, Map<String, String> receiptsByDimension) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                String dimension = request.getRequestUrl() != null
                        ? request.getRequestUrl().queryParameter("dimension") : null;
                if (path.startsWith("/api/v1/reports/aggregate/billing/monthly")) {
                    return json(envelope("BILLING_AGGREGATE_MONTHLY",
                            billingByDimension.getOrDefault(dimension, "[]")));
                }
                if (path.startsWith("/api/v1/reports/aggregate/receipts/monthly")) {
                    return json(envelope("RECEIPTS_AGGREGATE_MONTHLY",
                            receiptsByDimension.getOrDefault(dimension, "[]")));
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    @Test
    void trend_reconcilesFlattenedMonthlyRateAcrossDimensions() {
        // Billed: Jul 120/60/20 (scheme/group/member) → 200; Aug 80/40/20 → 140.
        // Received: Jul 60/30/10 → 100; Aug 50/30/20 → 100.
        Map<String, String> billing = new LinkedHashMap<>();
        billing.put("SCHEME", monthlyRows("SCHEME", SCHEME_ID,
                new Object[][]{{"2026-07-01", 120.00}, {"2026-08-01", 80.00}}));
        billing.put("GROUP", monthlyRows("GROUP", GROUP_ID,
                new Object[][]{{"2026-07-01", 60.00}, {"2026-08-01", 40.00}}));
        billing.put("MEMBER", monthlyRows("MEMBER", MEMBER_ID,
                new Object[][]{{"2026-07-01", 20.00}, {"2026-08-01", 20.00}}));
        Map<String, String> receipts = new LinkedHashMap<>();
        receipts.put("SCHEME", monthlyRows("SCHEME", SCHEME_ID,
                new Object[][]{{"2026-07-01", 60.00}, {"2026-08-01", 50.00}}));
        receipts.put("GROUP", monthlyRows("GROUP", GROUP_ID,
                new Object[][]{{"2026-07-01", 30.00}, {"2026-08-01", 30.00}}));
        receipts.put("MEMBER", monthlyRows("MEMBER", MEMBER_ID,
                new Object[][]{{"2026-07-01", 10.00}, {"2026-08-01", 20.00}}));
        CONTRIBUTIONS.setDispatcher(aggregateDispatcher(billing, receipts));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate-trend")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("COLLECTION_RATE_TREND")
                .jsonPath("$.data.months.length()").isEqualTo(2)
                .jsonPath("$.data.months[0].month").isEqualTo("2026-07-01")
                .jsonPath("$.data.months[0].currencyCode").isEqualTo("USD")
                .jsonPath("$.data.months[0].billed").isEqualTo(200.00)
                .jsonPath("$.data.months[0].received").isEqualTo(100.00)
                .jsonPath("$.data.months[0].ratePct").isEqualTo(50.00)
                .jsonPath("$.data.months[1].month").isEqualTo("2026-08-01")
                .jsonPath("$.data.months[1].billed").isEqualTo(140.00)
                .jsonPath("$.data.months[1].received").isEqualTo(100.00)
                .jsonPath("$.data.months[1].ratePct").isEqualTo(71.43)
                .jsonPath("$.perCurrency.USD.totalAmount").isEqualTo(200.00)
                .jsonPath("$.perCurrency.USD.rowCount").isEqualTo(2)
                .jsonPath("$.warnings").isEmpty();
    }

    @Test
    void trend_contributionsPeerDown_stillRendersWithWarnings() {
        CONTRIBUTIONS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500).setBody("boom");
            }
        });

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate-trend")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("COLLECTION_RATE_TREND")
                .jsonPath("$.data.months").isArray()
                .jsonPath("$.data.months.length()").isEqualTo(0)
                .jsonPath("$.warnings").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("billing-aggregate-monthly[SCHEME]")));
    }

    @Test
    void exportExcel_returnsWorkbookAndEmitsDataAccessEvent() {
        CONTRIBUTIONS.setDispatcher(aggregateDispatcher(Map.of(), Map.of()));

        byte[] bytes = webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate-trend/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-admin")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(bytes).isNotEmpty();
        assertThat(bytes).startsWith(new byte[]{0x50, 0x4B, 0x03, 0x04}); // zip magic

        JsonNode event = consumeAuditEventContaining("medfund.security.events",
                "COLLECTION_RATE_TREND", Duration.ofSeconds(10));
        assertThat(event).isNotNull();
        assertThat(event.path("eventType").asText()).isEqualTo("DATA_ACCESS");
        assertThat(event.path("details").asText()).contains("COLLECTION_RATE_TREND");
    }

    @Test
    void report_missingSubledgerPermission_forbidden() {
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/collection-rate-trend")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase8-viewer")
                .exchange()
                .expectStatus().isForbidden();
    }
}
