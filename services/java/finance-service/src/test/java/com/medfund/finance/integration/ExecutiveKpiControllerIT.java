package com.medfund.finance.integration;

import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.service.IbnrLookupService;
import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.service.CommissionAggregateService;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader.AmlFilingIdentity;
import com.medfund.finance.regulatory.aml.AmlSummaryRawData;
import com.medfund.finance.regulatory.aml.AmlSummaryRawDataProvider;
import com.medfund.finance.regulatory.aml.AmlThresholdReader;
import com.medfund.finance.regulatory.aml.AmlThresholds;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportEnablementReader;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end slice for the Phase 18 KPI composer. Runs the full
 * finance-service context with the two cross-service peers stubbed via
 * MockWebServer (real WebClient fanout + JSON decode + CrossServiceCallHelper
 * guard) and the finance-local dependencies ({@link CommissionAggregateService},
 * {@link IbnrLookupService}, {@link FxRateReader}) mocked as {@code @MockBean}
 * per the Phase 5 plan's IT guidance ({@link ExecutiveKpiControllerIT} §5
 * Tests: "Finance-local peers can be spring-context beans" — we use
 * {@code @MockBean} rather than seeding the tables).
 *
 * <p>Redis is not available in the Testcontainers harness — the composer's
 * cache read errors fall through to compute (see
 * {@code cachedOrCompute} onErrorResume), so the IT still runs.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ExecutiveKpiControllerIT.SecurityStub.class)
class ExecutiveKpiControllerIT extends AbstractIntegrationTest {

    private static final String TENANT_ID = "22222222-2222-2222-2222-222222222222";

    @Autowired private WebTestClient webTestClient;
    @MockBean private CommissionAggregateService commissionAggregateService;
    @MockBean private IbnrLookupService ibnrLookupService;
    @MockBean private FxRateReader fxRateReader;
    @MockBean private ReportEnablementReader reportEnablementReader;
    // Real Redis may hold stale entries from prior IT runs — mock the template so
    // every test starts with an empty cache and the composer runs a real fanout.
    @MockBean private ReactiveRedisTemplate<String, KpiReportData> kpiCache;

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

        // Pre-existing baseline gap: matches CommissionAggregateIT precedent.
        @Bean @Primary
        AmlSummaryRawDataProvider stubAmlSummaryRawDataProvider() {
            return (tenantId, periodStart, periodEnd) -> Mono.just(new AmlSummaryRawData(
                    "test-entity", "test-regulator",
                    new EnumMap<>(AmlSummaryRawData.ActivityCategory.class),
                    new EnumMap<>(AmlSummaryRawData.ActivityCategory.class),
                    new EnumMap<>(AmlSummaryRawData.StrStatus.class),
                    BigDecimal.ZERO));
        }

        @Bean @Primary
        AmlThresholdReader stubAmlThresholdReader() {
            return (tenantId, countryCode, currency, asOf) -> Mono.just(AmlThresholds.empty());
        }

        @Bean @Primary
        AmlFilingIdentityReader stubAmlFilingIdentityReader() {
            return tenantId -> Mono.just(AmlFilingIdentity.empty());
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubs() {
        // Reset previous-test stubbing on the @MockBeans so specific-arg
        // stubs (e.g. isEnabled(LOSS_RATIO_KPI)->false) don't leak forward.
        reset(fxRateReader, ibnrLookupService, commissionAggregateService,
                reportEnablementReader, kpiCache);
        // Empty cache every test — forces the composer to fan out through
        // MockWebServer + Mockito peers, which is what the IT is actually
        // exercising. valueOps returned by opsForValue() must also be a mock.
        ReactiveValueOperations<String, KpiReportData> valueOps =
                mock(ReactiveValueOperations.class);
        when(kpiCache.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(valueOps.set(anyString(), any(), any(java.time.Duration.class)))
                .thenReturn(Mono.just(true));
        // Identity FX by default — same-currency conversion short-circuits.
        when(fxRateReader.convert(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> Mono.just((BigDecimal) inv.getArgument(0)));
        when(ibnrLookupService.latestIbnrTotal(any(), any(), any(), anyList()))
                .thenReturn(Mono.just(Optional.<BigDecimal>empty()));
        when(commissionAggregateService.aggregatePaid(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(List.of(new CommissionAggregateRow(
                        null, null, "HEALTH", "USD", new BigDecimal("12"), 1L))));
        when(reportEnablementReader.isEnabled(any(), any())).thenReturn(Mono.just(true));
        // 1-arg overload used by the @RequiresReport aspect (resolves tenant from reactive context)
        when(reportEnablementReader.isEnabled(any(com.medfund.shared.report.ReportKey.class)))
                .thenReturn(Mono.just(true));

        CONTRIBUTIONS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.startsWith("/api/v1/reports/aggregate/premium-earned")) {
                    return json("[{\"schemeId\":null,\"schemeName\":null,\"insuranceLine\":\"HEALTH\","
                            + "\"currencyCode\":\"USD\",\"earnedPremium\":100.00,\"rowCount\":12}]");
                }
                if (path.startsWith("/api/v1/reports/aggregate/billing/monthly")
                        || path.startsWith("/api/v1/reports/aggregate/receipts/monthly")) {
                    return envelopeJson("[]");
                }
                if (path.startsWith("/api/v1/reports/aggregate/billing")) {
                    return envelopeJson("[{\"schemeId\":\"" + UUID.randomUUID()
                            + "\",\"schemeName\":\"Scheme A\",\"currencyCode\":\"USD\","
                            + "\"totalBilled\":100.00}]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        CLAIMS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if (path.startsWith("/api/v1/reports/aggregate/claims-incurred")) {
                    return json("[{\"schemeId\":null,\"schemeName\":null,\"insuranceLine\":\"HEALTH\","
                            + "\"currencyCode\":\"USD\",\"totalPaid\":60.00,\"reserveBalanceStart\":0,"
                            + "\"reserveBalanceEnd\":10.00,\"reserveMovement\":10.00,"
                            + "\"subtotalIncurredExIbnr\":70.00,\"claimCount\":5}]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    @Test
    void lossRatio_composesPeersIntoEnvelope() {
        hit("/api/v1/reports/kpi/loss-ratio")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("LOSS_RATIO_KPI")
                .jsonPath("$.reportingCurrency").isEqualTo("USD")
                // subtotalIncurredExIbnr=70 (no IBNR) / earned=100 = 0.70
                .jsonPath("$.data.compositeRatio").isEqualTo(0.7)
                .jsonPath("$.data.perCurrency.USD.ratio").isEqualTo(0.7);
    }

    @Test
    void expenseRatio_composesCommissionOverBilling() {
        hit("/api/v1/reports/kpi/expense-ratio")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("EXPENSE_RATIO")
                // commission=12 / billed=100 = 0.12
                .jsonPath("$.data.compositeRatio").isEqualTo(0.12);
    }

    @Test
    void combinedRatio_sumsLossAndExpenseWithBasisNote() {
        hit("/api/v1/reports/kpi/combined-ratio")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("COMBINED_RATIO")
                .jsonPath("$.data.basisNote").isEqualTo("MIXED_LOSS_EARNED_EXPENSE_WRITTEN")
                // 0.70 + 0.12 = 0.82
                .jsonPath("$.data.compositeRatio").isEqualTo(0.82);
    }

    @Test
    void claimsFrequency_countOverPolicyMonths() {
        hit("/api/v1/reports/kpi/claims-frequency")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("CLAIMS_FREQUENCY")
                // 5 claims / 12 policy-months ≈ 0.416667
                .jsonPath("$.data.compositeRatio").value(v ->
                        org.assertj.core.api.Assertions.assertThat((Double) v)
                                .isCloseTo(0.416667, org.assertj.core.data.Offset.offset(1e-4)));
    }

    @Test
    void averageSeverity_paidOverCount() {
        hit("/api/v1/reports/kpi/average-severity")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("AVERAGE_SEVERITY")
                // paid=60 / count=5 = 12
                .jsonPath("$.data.compositeRatio").isEqualTo(12);
    }

    @Test
    void dashboard_returnsFiveTiles() {
        hit("/api/v1/reports/kpi/dashboard")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tiles.LOSS_RATIO_KPI.reportKey").isEqualTo("LOSS_RATIO_KPI")
                .jsonPath("$.tiles.EXPENSE_RATIO.reportKey").isEqualTo("EXPENSE_RATIO")
                .jsonPath("$.tiles.COMBINED_RATIO.reportKey").isEqualTo("COMBINED_RATIO")
                .jsonPath("$.tiles.CLAIMS_FREQUENCY.reportKey").isEqualTo("CLAIMS_FREQUENCY")
                .jsonPath("$.tiles.AVERAGE_SEVERITY.reportKey").isEqualTo("AVERAGE_SEVERITY");
    }

    @Test
    void dashboard_disabledKey_returns403() {
        when(reportEnablementReader.isEnabled(any(),
                eq(com.medfund.shared.report.ReportKey.EXPENSE_RATIO)))
                .thenReturn(Mono.just(false));

        hit("/api/v1/reports/kpi/dashboard").expectStatus().isForbidden();
    }

    @Test
    void trend_12MonthlyBuckets_oldestFirst() {
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/trend").build())
                .header("X-Tenant-ID", TENANT_ID)
                .header("Authorization", "Bearer kpi-it")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(12)
                // Oldest-first: bucket[0].periodStart < bucket[11].periodStart
                .jsonPath("$[0].composite.compositeRatio").isEqualTo(0.7);
    }

    @Test
    void trend_invalidWindow_returns400() {
        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/trend")
                        .queryParam("windowMonths", 6)
                        .build())
                .header("X-Tenant-ID", TENANT_ID)
                .header("Authorization", "Bearer kpi-it")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void trend_disabledKey_returns403() {
        when(reportEnablementReader.isEnabled(any(),
                eq(com.medfund.shared.report.ReportKey.LOSS_RATIO_KPI)))
                .thenReturn(Mono.just(false));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/trend").build())
                .header("X-Tenant-ID", TENANT_ID)
                .header("Authorization", "Bearer kpi-it")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void exportExcel_returnsXlsxBytes() {
        byte[] body = webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID)
                .header("Authorization", "Bearer kpi-it")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().valueMatches("Content-Disposition",
                        "attachment; filename=\"loss_ratio_kpi-2026-07-01-to-2026-08-01\\.xlsx\"")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        // XLSX zip magic bytes — cheap sanity check that we actually got a workbook.
        org.assertj.core.api.Assertions.assertThat(body).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(body).startsWith((byte) 0x50, (byte) 0x4B);
    }

    @Test
    void exportExcel_disabledKey_returns403() {
        when(reportEnablementReader.isEnabled(any(),
                eq(com.medfund.shared.report.ReportKey.LOSS_RATIO_KPI)))
                .thenReturn(Mono.just(false));

        webTestClient
                .get().uri(uri -> uri.path("/api/v1/reports/kpi/LOSS_RATIO_KPI/export/excel")
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID)
                .header("Authorization", "Bearer kpi-it")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void claimsPeerDown_populatesWarningsInEnvelope() {
        CLAIMS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500).setBody("peer down");
            }
        });

        hit("/api/v1/reports/kpi/loss-ratio")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reportKey").isEqualTo("LOSS_RATIO_KPI")
                .jsonPath("$.warnings").isNotEmpty();
    }

    private WebTestClient.ResponseSpec hit(String path) {
        return webTestClient
                .get().uri(uri -> uri.path(path)
                        .queryParam("periodStart", "2026-07-01")
                        .queryParam("periodEnd",   "2026-08-01")
                        .build())
                .header("X-Tenant-ID", TENANT_ID)
                .header("Authorization", "Bearer kpi-it")
                .exchange();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    private static MockResponse envelopeJson(String dataJson) {
        return json("{\"reportKey\":\"BILLING_AGGREGATE\",\"period\":null,\"reportingCurrency\":\"USD\","
                + "\"data\":" + dataJson + ",\"perCurrency\":{},\"fxRates\":{},\"warnings\":[],"
                + "\"generatedAt\":\"2026-08-16T10:00:00Z\"}");
    }
}
