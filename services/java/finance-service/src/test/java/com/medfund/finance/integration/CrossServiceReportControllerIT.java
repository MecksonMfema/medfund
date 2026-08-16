package com.medfund.finance.integration;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Slice-level integration test for the Phase 5 cross-service reports.
 * Runs the full finance-service context (Testcontainers Postgres + Kafka)
 * with the two outbound peers stubbed via okhttp MockWebServer — the
 * {@code services.contributions.base-url} / {@code services.claims.base-url}
 * properties point at the stubs, so the real WebClient fanout, decode,
 * and {@code CrossServiceCallHelper} guard all run. The {@code RequiresReport}
 * aspect looks up {@code public.tenant_report_config}; the test schema has
 * no such table, so it falls back to enabled (absent row = default TRUE).
 *
 * <p>MockWebServer {@link Dispatcher} routes on request path, so the order
 * in which {@code Mono.zip} fires the concurrent peer calls never matters.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(CrossServiceReportControllerIT.SecurityStub.class)
class CrossServiceReportControllerIT {

    @Autowired private WebTestClient webTestClient;

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SCHEME  = "11111111-1111-1111-1111-111111111111";
    private static final String MEMBER  = "22222222-2222-2222-2222-222222222222";

    private static final MockWebServer CONTRIBUTIONS = new MockWebServer();
    private static final MockWebServer CLAIMS = new MockWebServer();

    static {
        try {
            CONTRIBUTIONS.start();
            CLAIMS.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to start MockWebServer stubs", e);
        }
    }

    @AfterAll
    static void shutdownStubs() throws java.io.IOException {
        CONTRIBUTIONS.shutdown();
        CLAIMS.shutdown();
    }

    @DynamicPropertySource
    static void peerUrls(DynamicPropertyRegistry registry) {
        registry.add("services.contributions.base-url", () -> CONTRIBUTIONS.url("/").toString());
        registry.add("services.claims.base-url",        () -> CLAIMS.url("/").toString());
    }

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

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    private static String envelope(String reportKey, String dataJson) {
        return "{\"reportKey\":\"" + reportKey + "\",\"period\":null,\"reportingCurrency\":\"USD\","
                + "\"data\":" + dataJson + ",\"perCurrency\":{},\"fxRates\":{},\"warnings\":[],"
                + "\"generatedAt\":\"2026-08-16T10:00:00Z\"}";
    }

    private static Dispatcher contributionsDispatcher(
            String billingJson, String billingMonthlyJson, String receiptsMonthlyJson) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.startsWith("/api/v1/reports/aggregate/billing/monthly")) {
                    return json(envelope("BILLING_AGGREGATE_MONTHLY", billingMonthlyJson));
                }
                if (path.startsWith("/api/v1/reports/aggregate/receipts/monthly")) {
                    return json(envelope("RECEIPTS_AGGREGATE_MONTHLY", receiptsMonthlyJson));
                }
                if (path.startsWith("/api/v1/reports/aggregate/billing")) {
                    return json(envelope("BILLING_AGGREGATE", billingJson));
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static Dispatcher claimsDispatcher(String claimsJson, String claimsMonthlyJson) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.startsWith("/api/v1/reports/aggregate/claims/monthly")) {
                    return json(envelope("CLAIMS_AGGREGATE_MONTHLY", claimsMonthlyJson));
                }
                if (path.startsWith("/api/v1/reports/aggregate/claims")) {
                    return json(envelope("CLAIMS_AGGREGATE", claimsJson));
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    @Test
    void lossRatio_composesBillingAndClaimsIntoEnvelope() {
        CONTRIBUTIONS.setDispatcher(contributionsDispatcher(
                "[{\"schemeId\":\"" + SCHEME + "\",\"schemeName\":\"Gold\",\"currencyCode\":\"USD\","
                        + "\"totalBilled\":200.00}]",
                "[]", "[]"));
        CLAIMS.setDispatcher(claimsDispatcher(
                "[{\"dimension\":\"SCHEME\",\"dimensionId\":\"" + SCHEME
                        + "\",\"dimensionName\":\"Gold\",\"currencyCode\":\"USD\","
                        + "\"totalClaimed\":150.00,\"totalApproved\":120.00,\"totalPaid\":100.00}]",
                "[]"));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/billing-vs-claims")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase5-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("LOSS_RATIO")
                .jsonPath("$.data.rows").isArray()
                .jsonPath("$.data.rows[0].schemeName").isEqualTo("Gold")
                .jsonPath("$.data.rows[0].totalBilled").isEqualTo(200.00)
                .jsonPath("$.data.rows[0].totalPaid").isEqualTo(100.00)
                .jsonPath("$.data.rows[0].paidRatioPct").isEqualTo(50.00)
                .jsonPath("$.data.rows[0].billedMinusPaid").isEqualTo(100.00)
                .jsonPath("$.warnings").isEmpty();
    }

    @Test
    void memberPayments_composesAllThreeSources() {
        CONTRIBUTIONS.setDispatcher(contributionsDispatcher(
                "[]",
                "[{\"dimension\":\"MEMBER\",\"dimensionId\":\"" + MEMBER
                        + "\",\"dimensionName\":\"Ada\",\"currencyCode\":\"USD\","
                        + "\"month\":\"2026-07-01\",\"totalAmount\":100.00},"
                        + "{\"dimension\":\"MEMBER\",\"dimensionId\":\"" + MEMBER
                        + "\",\"dimensionName\":\"Ada\",\"currencyCode\":\"USD\","
                        + "\"month\":\"2026-08-01\",\"totalAmount\":100.00}]",
                "[{\"dimension\":\"MEMBER\",\"dimensionId\":\"" + MEMBER
                        + "\",\"dimensionName\":\"Ada\",\"currencyCode\":\"USD\","
                        + "\"month\":\"2026-07-01\",\"totalAmount\":50.00},"
                        + "{\"dimension\":\"MEMBER\",\"dimensionId\":\"" + MEMBER
                        + "\",\"dimensionName\":\"Ada\",\"currencyCode\":\"USD\","
                        + "\"month\":\"2026-08-01\",\"totalAmount\":80.00}]"));
        CLAIMS.setDispatcher(claimsDispatcher(
                "[]",
                "[{\"dimension\":\"MEMBER\",\"dimensionId\":\"" + MEMBER
                        + "\",\"dimensionName\":\"Ada\",\"currencyCode\":\"USD\","
                        + "\"month\":\"2026-07-01\",\"totalAmount\":30.00},"
                        + "{\"dimension\":\"MEMBER\",\"dimensionId\":\"" + MEMBER
                        + "\",\"dimensionName\":\"Ada\",\"currencyCode\":\"USD\","
                        + "\"month\":\"2026-08-01\",\"totalAmount\":40.00}]"));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/member-payments")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase5-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("MEMBER_PAYMENTS_UNIFIED")
                .jsonPath("$.data.rows[0].memberName").isEqualTo("Ada")
                .jsonPath("$.data.rows[0].totalBilled").isEqualTo(200.00)
                .jsonPath("$.data.rows[0].totalReceived").isEqualTo(130.00)
                .jsonPath("$.data.rows[0].totalClaimsPaid").isEqualTo(70.00)
                .jsonPath("$.data.rows[0].netPosition").isEqualTo(60.00)
                .jsonPath("$.warnings").isEmpty();
    }

    @Test
    void lossRatio_claimsPeerDown_stillRendersWithWarnings() {
        CONTRIBUTIONS.setDispatcher(contributionsDispatcher(
                "[{\"schemeId\":\"" + SCHEME + "\",\"schemeName\":\"Gold\",\"currencyCode\":\"USD\","
                        + "\"totalBilled\":200.00}]",
                "[]", "[]"));
        CLAIMS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500).setBody("boom");
            }
        });

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/billing-vs-claims")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-31")
                        .build())
                .header("X-Tenant-ID", TENANT)
                .header("Authorization", "Bearer phase5-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("LOSS_RATIO")
                .jsonPath("$.data.rows[0].totalBilled").isEqualTo(200.00)
                .jsonPath("$.data.rows[0].totalPaid").isEqualTo(0)
                .jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("claims-aggregate[SCHEME]"));
    }
}
