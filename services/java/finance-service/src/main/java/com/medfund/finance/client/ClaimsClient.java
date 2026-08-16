package com.medfund.finance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.dto.ClaimsAggregateRow;
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
 * Thin WebClient wrapper around the claims-service cross-service aggregate
 * endpoints. Mirrors {@link ContributionsClient}: envelope decoded via
 * {@code String} + Jackson {@code TypeReference}, no in-client retries or
 * fallbacks — those live in
 * {@link com.medfund.shared.report.CrossServiceCallHelper} at the calling
 * service so composing reports can attribute warnings to the specific peer
 * call that failed (G37).
 */
@Slf4j
@Component
public class ClaimsClient {

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public ClaimsClient(WebClient.Builder builder,
                        @Value("${services.claims.base-url:http://localhost:8083}") String baseUrl,
                        ObjectMapper objectMapper) {
        this.http = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/v1/reports/aggregate/claims?periodStart&periodEnd — the
     * SCHEME-hardcoded variant returning the full funnel (claimed,
     * approved, paid) per (scheme, currency). Used by the loss-ratio
     * report (Phase 5).
     */
    public Mono<List<ClaimsAggregateRow>> aggregateClaims(LocalDate periodStart, LocalDate periodEnd) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> uri.path("/api/v1/reports/aggregate/claims")
                            .queryParam("periodStart", periodStart.toString())
                            .queryParam("periodEnd",   periodEnd.toString())
                            .build())
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractClaimsRows);
        });
    }

    /**
     * GET /api/v1/reports/aggregate/claims/monthly?dimension=MEMBER — per
     * (dimension, currency, month) totals where {@code totalAmount} carries
     * the paid amount (G44). Used by the member-payments report (Phase 5).
     */
    public Mono<List<MonthlyAggregateRow>> aggregateClaimsMonthly(LocalDate periodStart, LocalDate periodEnd,
                                                                  String dimension) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> uri.path("/api/v1/reports/aggregate/claims/monthly")
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
     * Decodes {@code ReportResponse<List<ClaimsAggregateRow>>} from the raw
     * JSON body — same String + Jackson approach as the contributions client.
     */
    private List<ClaimsAggregateRow> extractClaimsRows(String body) {
        try {
            ReportResponse<List<ClaimsAggregateRow>> envelope = objectMapper.readValue(
                    body, new TypeReference<>() {});
            return envelope != null && envelope.data() != null ? envelope.data() : List.of();
        } catch (Exception e) {
            log.warn("[claims-client] failed to decode envelope: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Decodes {@code ReportResponse<List<MonthlyAggregateRow>>} from the raw
     * JSON body — same String + Jackson approach as the contributions client.
     */
    private List<MonthlyAggregateRow> extractMonthlyRows(String body) {
        try {
            ReportResponse<List<MonthlyAggregateRow>> envelope = objectMapper.readValue(
                    body, new TypeReference<>() {});
            return envelope != null && envelope.data() != null ? envelope.data() : List.of();
        } catch (Exception e) {
            log.warn("[claims-client] failed to decode monthly envelope: {}", e.getMessage());
            return List.of();
        }
    }
}
