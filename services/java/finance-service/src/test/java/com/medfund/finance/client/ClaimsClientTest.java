package com.medfund.finance.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.dto.ClaimsAggregateRow;
import com.medfund.shared.tenant.TenantContext;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebClient-level tests for {@link ClaimsClient} against okhttp's
 * MockWebServer. Each test enqueues a peer response and asserts the
 * decoded rows, plus the request shape the client actually sent
 * (path + tenant header). Malformed peer bodies must not blow up the
 * caller — they degrade to an empty list so the composing report keeps
 * the other leg (G37).
 */
class ClaimsClientTest {

    private MockWebServer server;
    private ClaimsClient client;

    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd   = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ClaimsClient(WebClient.builder(),
                server.url("/").toString(),
                new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void aggregateClaims_decodesEnvelopeData() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"reportKey":"CLAIMS_AGGREGATE","period":null,"reportingCurrency":"USD",
                 "data":[{"dimension":"SCHEME",
                          "dimensionId":"11111111-1111-1111-1111-111111111111",
                          "dimensionName":"Gold","currencyCode":"USD",
                          "totalClaimed":150.00,"totalApproved":120.00,"totalPaid":100.00}],
                 "perCurrency":{},"fxRates":{},"warnings":[],"generatedAt":"2026-08-16T10:00:00Z"}
                """).addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.aggregateClaims(periodStart, periodEnd)
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    ClaimsAggregateRow row = rows.get(0);
                    assertThat(row.dimension()).isEqualTo("SCHEME");
                    assertThat(row.dimensionName()).isEqualTo("Gold");
                    assertThat(row.currencyCode()).isEqualTo("USD");
                    assertThat(row.totalClaimed()).isEqualByComparingTo("150.00");
                    assertThat(row.totalApproved()).isEqualByComparingTo("120.00");
                    assertThat(row.totalPaid()).isEqualByComparingTo("100.00");
                })
                .verifyComplete();

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/api/v1/reports/aggregate/claims"
                + "?periodStart=2026-07-01&periodEnd=2026-08-31");
        assertThat(req.getHeader("X-Tenant-ID")).isEqualTo("tnt-1");
    }

    @Test
    void aggregateClaims_monthlyHitsMonthlyPathWithDimension() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"reportKey":"CLAIMS_AGGREGATE_MONTHLY","period":null,"reportingCurrency":"USD",
                 "data":[{"dimension":"MEMBER",
                          "dimensionId":"22222222-2222-2222-2222-222222222222",
                          "dimensionName":"Ada","currencyCode":"USD",
                          "month":"2026-07-01","totalAmount":40.00}],
                 "perCurrency":{},"fxRates":{},"warnings":[],"generatedAt":"2026-08-16T10:00:00Z"}
                """).addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.aggregateClaimsMonthly(periodStart, periodEnd, "MEMBER")
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).dimensionName()).isEqualTo("Ada");
                    assertThat(rows.get(0).totalAmount()).isEqualByComparingTo("40.00");
                })
                .verifyComplete();

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/api/v1/reports/aggregate/claims/monthly"
                + "?periodStart=2026-07-01&periodEnd=2026-08-31&dimension=MEMBER");
    }

    @Test
    void aggregateClaims_malformedBody_degradesToEmptyList() throws Exception {
        server.enqueue(new MockResponse().setBody("{not json at all")
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.aggregateClaims(periodStart, periodEnd)
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .assertNext(rows -> assertThat(rows).isEmpty())
                .verifyComplete();
    }

    @Test
    void aggregateClaims_wrongReportKey_stillDecodesData() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"reportKey":"SOMETHING_ELSE","period":null,"reportingCurrency":"USD",
                 "data":[{"dimension":"SCHEME",
                          "dimensionId":"11111111-1111-1111-1111-111111111111",
                          "dimensionName":"Gold","currencyCode":"USD",
                          "totalClaimed":10.00,"totalApproved":5.00,"totalPaid":2.00}],
                 "perCurrency":{},"fxRates":{},"warnings":[],"generatedAt":"2026-08-16T10:00:00Z"}
                """).addHeader("Content-Type", "application/json"));

        StepVerifier.create(client.aggregateClaims(periodStart, periodEnd)
                        .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).totalPaid()).isEqualByComparingTo("2.00");
                })
                .verifyComplete();
    }
}
