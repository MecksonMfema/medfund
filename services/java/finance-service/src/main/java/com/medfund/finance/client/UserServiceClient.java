package com.medfund.finance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thin WebClient wrapper around the user-service data feeds finance-service
 * consumes. Today: the Phase-14 persistency-study shaping call. Mirrors
 * {@link ClaimsClient} — retry/timeout/fallback stays at the caller so any
 * peer-call failure attributes to the specific report, not the client.
 */
@Slf4j
@Component
public class UserServiceClient {

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public UserServiceClient(WebClient.Builder builder,
                             @Value("${services.user.base-url:http://localhost:8082}") String baseUrl,
                             ObjectMapper objectMapper) {
        this.http = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * GET {@code /api/v1/reports/policy-lifecycle/persistency-cohort-feed}.
     * Returns one row per (cohortMonth, insuranceLine, checkpointMonths) with
     * {@code cohortSize} and {@code stillActive} counts — the shaping service
     * pivots this into the PERSISTENCY_STUDY cohort payload.
     */
    public Mono<List<PersistencyCohortFeedRow>> persistencyCohortFeed(
            LocalDate periodStart, LocalDate periodEnd,
            List<Integer> checkpoints, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> {
                        var b = uri.path("/api/v1/reports/policy-lifecycle/persistency-cohort-feed")
                                .queryParam("periodStart", periodStart.toString())
                                .queryParam("periodEnd",   periodEnd.toString());
                        if (checkpoints != null && !checkpoints.isEmpty()) {
                            b = b.queryParam("checkpoints",
                                    checkpoints.stream()
                                            .map(String::valueOf)
                                            .collect(Collectors.joining(",")));
                        }
                        if (insuranceLine != null && !insuranceLine.isBlank()) {
                            b = b.queryParam("insuranceLine", insuranceLine);
                        }
                        return b.build();
                    })
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::decode);
        });
    }

    private List<PersistencyCohortFeedRow> decode(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<List<PersistencyCohortFeedRow>>() {});
        } catch (Exception e) {
            log.warn("[user-service] failed to decode persistency-cohort-feed body: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Wire shape for a single cohort row — matches
     * {@code com.medfund.user.reports.lifecycle.dto.PersistencyCohortRow} on
     * the user-service side. Kept local to avoid a shared-module coupling.
     */
    public record PersistencyCohortFeedRow(
            LocalDate cohortMonth,
            String insuranceLine,
            int checkpointMonths,
            long cohortSize,
            long stillActive,
            BigDecimal retentionRate
    ) {}
}
