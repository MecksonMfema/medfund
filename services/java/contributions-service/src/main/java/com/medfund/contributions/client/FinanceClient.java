package com.medfund.contributions.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.PlannedOutflowRow;
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
 * Thin WebClient wrapper around finance-service's planned-outflow feed
 * (Phase 8, D8-1 — the forecast lives here in contributions-service and
 * reverses the usual aggregator direction). Failure semantics match the
 * other cross-service clients: no in-client retries or fallbacks — those
 * live in {@link com.medfund.shared.report.CrossServiceCallHelper} so the
 * forecast can attribute a warning to the specific peer call that failed.
 */
@Slf4j
@Component
public class FinanceClient {

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public FinanceClient(WebClient.Builder builder,
                         @Value("${services.finance.base-url:http://localhost:8085}") String baseUrl,
                         ObjectMapper objectMapper) {
        this.http = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/v1/reports/aggregate/outflows?periodStart&periodEnd —
     * item-level planned payouts from draft/approved payment runs in the
     * window. Decoded from {@code ReportResponse<List<PlannedOutflowRow>>}
     * the same String + Jackson way as finance's own
     * {@code ContributionsClient} — R2DBC's WebClient can't codec the
     * generic parametrised envelope type directly.
     */
    public Mono<List<PlannedOutflowRow>> plannedOutflows(LocalDate periodStart, LocalDate periodEnd) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> uri.path("/api/v1/reports/aggregate/outflows")
                            .queryParam("periodStart", periodStart.toString())
                            .queryParam("periodEnd",   periodEnd.toString())
                            .build())
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::extractOutflowRows);
        });
    }

    private List<PlannedOutflowRow> extractOutflowRows(String body) {
        try {
            ReportResponse<List<PlannedOutflowRow>> envelope = objectMapper.readValue(
                    body, new TypeReference<>() {});
            return envelope != null && envelope.data() != null ? envelope.data() : List.of();
        } catch (Exception e) {
            log.warn("[finance-client] failed to decode outflow envelope: {}", e.getMessage());
            return List.of();
        }
    }
}
