package com.medfund.finance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.dto.BillingAggregateRow;
import com.medfund.finance.dto.PremiumEarnedAggregateRow;
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
import java.util.UUID;

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

    /**
     * GET /api/v1/reports/aggregate/billing?periodStart&periodEnd — the
     * SCHEME-only non-monthly variant returning one {@link BillingAggregateRow}
     * per (scheme, currency). Used by the loss-ratio report (Phase 5).
     */
    public Mono<List<BillingAggregateRow>> aggregateBilling(LocalDate periodStart, LocalDate periodEnd) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> uri.path("/api/v1/reports/aggregate/billing")
                            .queryParam("periodStart", periodStart.toString())
                            .queryParam("periodEnd",   periodEnd.toString())
                            .build())
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractBillingRows);
        });
    }

    /**
     * Decodes {@code ReportResponse<List<BillingAggregateRow>>} from the raw
     * JSON body — same String + Jackson approach as the monthly decode.
     */
    private List<BillingAggregateRow> extractBillingRows(String body) {
        try {
            ReportResponse<List<BillingAggregateRow>> envelope = objectMapper.readValue(
                    body, new TypeReference<>() {});
            return envelope != null && envelope.data() != null ? envelope.data() : List.of();
        } catch (Exception e) {
            log.warn("[contributions-client] failed to decode billing envelope: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * GET /api/v1/reports/aggregate/premium-earned?dimension=TENANT|LINE|SCHEME
     * — returns per-(currency[, line][, scheme]) earned-premium totals sourced
     * from Phase-12 {@code earning_schedule.earned_at_period_end} closed rows.
     * Feeds the Phase 18 KPI composer's LOSS_RATIO denominator (K3) and the
     * CLAIMS_FREQUENCY policy-months denominator (K1 via {@code rowCount}).
     *
     * <p>Peer returns a bare JSON array (no envelope wrapper) — Phase 2
     * intentionally chose the leaner service-to-service shape.
     */
    public Mono<List<PremiumEarnedAggregateRow>> earnedPremium(LocalDate periodStart,
                                                                LocalDate periodEnd,
                                                                String dimension,
                                                                String insuranceLine,
                                                                UUID schemeId) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> {
                        var b = uri.path("/api/v1/reports/aggregate/premium-earned")
                                .queryParam("periodStart", periodStart.toString())
                                .queryParam("periodEnd",   periodEnd.toString())
                                .queryParam("dimension",   dimension);
                        if (insuranceLine != null && !insuranceLine.isBlank()) {
                            b = b.queryParam("insuranceLine", insuranceLine);
                        }
                        if (schemeId != null) {
                            b = b.queryParam("schemeId", schemeId.toString());
                        }
                        return b.build();
                    })
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractPremiumEarnedRows);
        });
    }

    private List<PremiumEarnedAggregateRow> extractPremiumEarnedRows(String body) {
        try {
            List<PremiumEarnedAggregateRow> rows = objectMapper.readValue(body, new TypeReference<>() {});
            return rows != null ? rows : List.of();
        } catch (Exception e) {
            log.warn("[contributions-client] failed to decode premium-earned array: {}", e.getMessage());
            return List.of();
        }
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
