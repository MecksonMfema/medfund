package com.medfund.finance.ifrs17.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fetches {@code ifrs17_portfolio} + {@code ifrs17_cohort} rows from user-service
 * for the IFRS 17 shaping fan-out (§17). Mirrors the shape used by
 * {@code Ifrs17PortfolioController} / {@code Ifrs17CohortController} on the peer
 * service — kept local to avoid a shared-module coupling.
 *
 * <p>Both fetches carry the reactive tenant context via the {@code X-Tenant-ID}
 * header so user-service applies its Rule-2 guard on the read.
 */
@Slf4j
@Component
public class Ifrs17PortfolioClient {

    private final WebClient http;
    private final ObjectMapper objectMapper;

    public Ifrs17PortfolioClient(WebClient.Builder builder,
                                  @Value("${services.user.base-url:http://localhost:8082}") String baseUrl,
                                  ObjectMapper objectMapper) {
        this.http = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * GET {@code /api/v1/underwriting/portfolios} — active + non-active per
     * the {@code includeInactive=true} query. Shaping filters to active
     * portfolios only, so the default (false) matches the intent.
     */
    public Mono<List<PortfolioRow>> listActive() {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri("/api/v1/underwriting/portfolios")
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::decodePortfolios);
        });
    }

    /**
     * GET {@code /api/v1/underwriting/cohorts?portfolioId=<uuid>} — active
     * cohorts for the given portfolio. Returned in the shape ready for chunk
     * fan-out.
     */
    public Mono<List<CohortRow>> listCohortsByPortfolio(UUID portfolioId) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> uri.path("/api/v1/underwriting/cohorts")
                            .queryParam("portfolioId", portfolioId.toString())
                            .build())
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(this::decodeCohorts);
        });
    }

    private List<PortfolioRow> decodePortfolios(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<List<PortfolioRow>>() {});
        } catch (Exception e) {
            log.warn("[user-service] failed to decode ifrs17_portfolio body: {}", e.getMessage());
            return List.of();
        }
    }

    private List<CohortRow> decodeCohorts(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<List<CohortRow>>() {});
        } catch (Exception e) {
            log.warn("[user-service] failed to decode ifrs17_cohort body: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Portfolio wire shape — matches {@code Ifrs17PortfolioResponse} on the
     * user-service side. Only fields the shaping service uses are declared.
     */
    public record PortfolioRow(
            UUID id,
            String name,
            String description,
            String insuranceLine,
            Boolean isActive,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * Cohort wire shape — matches {@code Ifrs17CohortResponse} on the
     * user-service side.
     */
    public record CohortRow(
            UUID id,
            UUID portfolioId,
            Integer cohortYear,
            String cohortType,
            String name,
            Boolean isActive,
            Instant createdAt,
            Instant updatedAt) {
    }
}
