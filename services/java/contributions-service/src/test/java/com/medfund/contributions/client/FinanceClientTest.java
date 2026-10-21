package com.medfund.contributions.client;

import com.medfund.contributions.dto.PlannedOutflowRow;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebClient-level tests for the Phase 8 {@link FinanceClient} — the
 * planned-outflow feed the cash-flow forecast consumes. Custom
 * {@link ExchangeFunction} (no MockWebServer — same choice as
 * {@code UserServiceClientTest}) records the outgoing request so we can
 * pin the routing contract: path, query params, and tenant header.
 */
class FinanceClientTest {

    private RecordingExchange exchange;
    private FinanceClient client;

    @BeforeEach
    void setUp() {
        exchange = new RecordingExchange();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        // The app's ObjectMapper is Spring-managed with JSR-310 modules;
        // mirror that so the envelope's Instant field decodes.
        client = new FinanceClient(builder, "http://finance-service:8085",
                com.fasterxml.jackson.databind.json.JsonMapper.builder()
                        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .build());
    }

    @Test
    void plannedOutflows_getsFeedPathWithWindowAndTenantHeader() {
        UUID runId = UUID.randomUUID();
        exchange.setResponse(HttpStatus.OK, """
                {"reportKey":"CASH_FLOW_FORECAST_13W","data":[{"runId":"%s","runNumber":"RUN-1",
                 "currencyCode":"USD","amount":40.00,"runStatus":"draft","itemStatus":"pending",
                 "createdAt":"2026-08-12T10:00:00Z"}]}
                """.formatted(runId));

        List<PlannedOutflowRow> rows = client.plannedOutflows(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
                .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1"))
                .block();

        ClientRequest req = exchange.lastRequest();
        assertThat(req.method()).isEqualTo(HttpMethod.GET);
        assertThat(req.url().getPath()).isEqualTo("/api/v1/reports/aggregate/outflows");
        assertThat(req.url().getQuery()).contains("periodStart=2026-08-01")
                .contains("periodEnd=2026-08-31");
        assertThat(req.headers().getFirst("X-Tenant-ID")).isEqualTo("tnt-1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).runId()).isEqualTo(runId);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("40.00");
    }

    @Test
    void plannedOutflows_malformedEnvelope_returnsEmptyNotThrows() {
        exchange.setResponse(HttpStatus.OK, "not-json");

        List<PlannedOutflowRow> rows = client.plannedOutflows(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
                .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1"))
                .block();

        assertThat(rows).isEmpty();
    }

    @Test
    void plannedOutflows_peerError_propagates() {
        exchange.setResponse(HttpStatus.INTERNAL_SERVER_ERROR, "down");

        StepVerifier.create(client.plannedOutflows(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))
                .contextWrite(ctx -> ctx.put(TenantContext.KEY, "tnt-1")))
                .expectError()
                .verify();
    }

    static class RecordingExchange implements ExchangeFunction {
        private final List<ClientRequest> requests = new java.util.ArrayList<>();
        private HttpStatus responseStatus = HttpStatus.OK;
        private String responseBody = "";

        void setResponse(HttpStatus status, String body) {
            this.responseStatus = status;
            this.responseBody = body;
        }

        ClientRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            return Mono.just(ClientResponse.create(responseStatus)
                    .header("Content-Type", "application/json")
                    .body(responseBody)
                    .build());
        }
    }
}
