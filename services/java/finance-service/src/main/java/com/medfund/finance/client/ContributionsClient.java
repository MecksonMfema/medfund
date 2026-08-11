package com.medfund.finance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * Thin WebClient wrapper around the contributions-service billing +
 * receipts cross-service aggregate endpoints. Called from the Phase 3
 * collection-rate report + Phase 5+ loss-ratio.
 *
 * <p>Failure semantics: no in-client retries or fallbacks — those live
 * in {@link com.medfund.shared.report.CrossServiceCallHelper} at the
 * calling service, so composing reports can attribute warnings to the
 * specific peer call that failed (G37).
 */
@Slf4j
@Component
public class ContributionsClient {

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public ContributionsClient(WebClient.Builder builder,
                                @Value("${services.contributions.base-url:http://localhost:8084}") String baseUrl,
                                ObjectMapper objectMapper) {
        this.http = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/v1/reports/aggregate/billing/monthly?dimension=SCHEME|GROUP|MEMBER
     * — returns per-(dimension, currency, month) billed totals.
     */
    public Mono<List<MonthlyAggregateRow>> aggregateBillingMonthly(LocalDate periodStart, LocalDate periodEnd,
                                                                    String dimension) {
        return fetchMonthly("/api/v1/reports/aggregate/billing/monthly",
                periodStart, periodEnd, dimension);
    }

    /**
     * GET /api/v1/reports/aggregate/receipts/monthly?dimension=SCHEME|GROUP|MEMBER
     * — returns per-(dimension, currency, month) received totals.
     */
    public Mono<List<MonthlyAggregateRow>> aggregateReceiptsMonthly(LocalDate periodStart, LocalDate periodEnd,
                                                                     String dimension) {
        return fetchMonthly("/api/v1/reports/aggregate/receipts/monthly",
                periodStart, periodEnd, dimension);
    }

    private Mono<List<MonthlyAggregateRow>> fetchMonthly(String path,
                                                          LocalDate periodStart, LocalDate periodEnd,
                                                          String dimension) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> uri.path(path)
                            .queryParam("periodStart", periodStart.toString())
                            .queryParam("periodEnd",   periodEnd.toString())
                            .queryParam("dimension",   dimension)
                            .build())
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractMonthlyRows);
        });
    }

    /**
     * Decodes {@code ReportResponse<List<MonthlyAggregateRow>>} from the raw
     * JSON body. Using {@code String} + Jackson (rather than
     * {@code bodyToMono(ReportResponse.class)}) sidesteps R2dbc's WebClient
     * needing a codec for the generic parametrised envelope type.
     */
    private List<MonthlyAggregateRow> extractMonthlyRows(String body) {
        try {
            ReportResponse<List<MonthlyAggregateRow>> envelope = objectMapper.readValue(
                    body, new TypeReference<>() {});
            return envelope != null && envelope.data() != null ? envelope.data() : List.of();
        } catch (Exception e) {
            log.warn("[contributions-client] failed to decode envelope: {}", e.getMessage());
            return List.of();
        }
    }
}
