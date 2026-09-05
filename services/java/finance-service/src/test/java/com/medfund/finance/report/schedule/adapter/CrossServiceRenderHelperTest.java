package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.security.M2MTokenProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossServiceRenderHelperTest {

    private MockWebServer server;

    @BeforeEach void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach void stop() throws IOException {
        server.shutdown();
    }

    private ScheduledFireContext ctx() {
        return new ScheduledFireContext(
                UUID.randomUUID(), ReportKey.AGED_DEBTORS, UUID.randomUUID(),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 8, 31),
                "USD", "Monthly",
                OffsetDateTime.parse("2026-09-01T08:05:00Z"),
                UUID.randomUUID(), "admin@acme");
    }

    @Test
    void render_acquiresTokenAndPostsRequest() throws Exception {
        M2MTokenProvider provider = mock(M2MTokenProvider.class);
        when(provider.getServiceToken("scheduled_report:render"))
                .thenReturn(Mono.just("tok-XYZ"));

        Buffer buf = new Buffer().write(new byte[]{7, 7, 7});
        server.enqueue(new MockResponse().setBody(buf)
                .addHeader("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

        var helper = new CrossServiceRenderHelper(WebClient.builder(), Optional.of(provider));
        String base = server.url("").toString().replaceAll("/$", "");

        StepVerifier.create(helper.render(ctx(), base, "/api/v1/reports/AGED_DEBTORS/scheduled-render",
                        "contributions.aged-debtors.render"))
                .assertNext(bytes -> assertThat(bytes).isEqualTo(new byte[]{7, 7, 7}))
                .verifyComplete();

        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/v1/reports/AGED_DEBTORS/scheduled-render");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok-XYZ");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"reportingCurrency\":\"USD\"");
        assertThat(body).contains("\"cadenceLabel\":\"Monthly\"");
        assertThat(body).contains("\"actorEmail\":\"admin@acme\"");
    }

    @Test
    void render_withoutTokenProvider_errorsClearly() {
        var helper = new CrossServiceRenderHelper(WebClient.builder(), Optional.empty());
        StepVerifier.create(helper.render(ctx(), "http://ignored", "/x", "test.call"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err).hasMessageContaining("M2MTokenProvider not configured");
                })
                .verify();
    }

    @Test
    void render_ownerService500_becomesGuardedFallbackNull_thenErrorOnEmptyBytes() throws Exception {
        M2MTokenProvider provider = mock(M2MTokenProvider.class);
        when(provider.getServiceToken("scheduled_report:render"))
                .thenReturn(Mono.just("tok"));
        // Two 500s (one for the initial call, one for the retry).
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        var helper = new CrossServiceRenderHelper(WebClient.builder(), Optional.of(provider));
        String base = server.url("").toString().replaceAll("/$", "");

        StepVerifier.create(helper.render(ctx(), base, "/x", "test.call"))
                .expectErrorMatches(err -> err instanceof IllegalStateException
                        && err.getMessage().contains("returned no bytes"))
                .verify();
    }
}
