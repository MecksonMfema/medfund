package com.medfund.finance.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.dto.ClaimsAggregateRow;
import com.medfund.finance.reinsurance.dto.FacultativeCandidateRow;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
     * GET {@code /api/v1/claims/page?status=ADJUDICATED&insuranceLine=…}
     * used by the facultative-candidates browse (Phase 7). Returns one
     * {@link FacultativeCandidateRow} per adjudicated claim with an
     * {@code approvedAmount} at or above {@code minAmount}. Threshold
     * filtering is done client-side because the operational
     * {@code /page} endpoint has no amount predicate — pulling one page
     * (server-side capped at 100) and filtering it is cheaper than
     * bolting a new query onto the claim-service list surface for a
     * single reinsurance workflow.
     *
     * <p>Peer downtime returns an empty list — the caller decides
     * whether to surface a warning banner. The FacultativeCession
     * service intentionally does not treat empty as an error; the
     * candidates browse is discovery-only.
     */
    public Mono<List<FacultativeCandidateRow>> findFacultativeCandidates(
            String insuranceLine, BigDecimal minAmount, int page, int size) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return http.get()
                    .uri(uri -> {
                        var b = uri.path("/api/v1/claims/page")
                                .queryParam("status", "ADJUDICATED")
                                .queryParam("page",   page)
                                .queryParam("size",   size);
                        if (insuranceLine != null && !insuranceLine.isBlank()) {
                            b = b.queryParam("insuranceLine", insuranceLine);
                        }
                        return b.build();
                    })
                    .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(body -> extractCandidateRows(body, minAmount))
                    .onErrorResume(err -> {
                        log.warn("[claims-client] facultative-candidates lookup failed: {}", err.getMessage());
                        return Mono.just(List.of());
                    });
        });
    }

    /**
     * Phase 8 — page through {@code /api/v1/claims/page} to stream every
     * ADJUDICATED claim on a given insurance line that landed at or
     * after {@code sinceInclusive}. Used by
     * {@link com.medfund.finance.reinsurance.service.TreatyActivationBackfillJob}.
     *
     * <p>Pages are fetched sequentially; the caller sees a hot {@link Flux}
     * that terminates when the last page comes back short. Peer downtime
     * on any page terminates the stream with an {@code empty} tail — the
     * backfill records the partial result rather than aborting, so an
     * operator can retry.
     */
    public Flux<AdjudicatedClaimRow> streamAdjudicatedClaimsForBackfill(String insuranceLine,
                                                                        LocalDate sinceInclusive,
                                                                        int pageSize) {
        int size = Math.min(100, Math.max(20, pageSize));
        return Flux.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return pageStream(tenantId, insuranceLine, sinceInclusive, size, 0);
        });
    }

    private Flux<AdjudicatedClaimRow> pageStream(String tenantId, String insuranceLine,
                                                 LocalDate sinceInclusive, int size, int page) {
        return fetchPage(tenantId, insuranceLine, page, size)
                .flatMapMany(rows -> {
                    if (rows.isEmpty()) return Flux.empty();
                    Flux<AdjudicatedClaimRow> filtered = Flux.fromIterable(rows)
                            .filter(row -> row.adjudicatedAt() != null
                                    && !row.adjudicatedAt().toLocalDate().isBefore(sinceInclusive));
                    if (rows.size() < size) return filtered;
                    return filtered.concatWith(pageStream(tenantId, insuranceLine,
                            sinceInclusive, size, page + 1));
                });
    }

    private Mono<List<AdjudicatedClaimRow>> fetchPage(String tenantId, String insuranceLine,
                                                      int page, int size) {
        return http.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/claims/page")
                            .queryParam("status", "ADJUDICATED")
                            .queryParam("page",   page)
                            .queryParam("size",   size);
                    if (insuranceLine != null && !insuranceLine.isBlank()) {
                        b = b.queryParam("insuranceLine", insuranceLine);
                    }
                    return b.build();
                })
                .header("X-Tenant-ID", tenantId != null ? tenantId : "")
                .retrieve()
                .bodyToMono(String.class)
                .map(this::decodeAdjudicatedRows)
                .onErrorResume(err -> {
                    log.warn("[claims-client] backfill page {} for line={} failed: {}",
                            page, insuranceLine, err.getMessage());
                    return Mono.just(List.of());
                });
    }

    private List<AdjudicatedClaimRow> decodeAdjudicatedRows(String body) {
        try {
            PageEnvelope envelope = objectMapper.readValue(body, new TypeReference<>() {});
            if (envelope == null || envelope.content() == null) return List.of();
            return envelope.content().stream()
                    .filter(row -> row.approvedAmount() != null
                            && row.approvedAmount().signum() > 0)
                    .map(row -> new AdjudicatedClaimRow(
                            row.id(),
                            row.memberId(),
                            row.providerId(),
                            row.insuranceLine(),
                            row.approvedAmount(),
                            row.currencyCode(),
                            row.submissionDate() != null
                                    ? row.submissionDate().atOffset(java.time.ZoneOffset.UTC) : null))
                    .toList();
        } catch (Exception e) {
            log.warn("[claims-client] failed to decode /page for backfill: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Streamed adjudicated-claim projection consumed by the backfill.
     * Deliberately narrower than {@link FacultativeCandidateRow} — the
     * backfill only needs enough to build a {@code ClaimAdjudicatedEvent}
     * for the cession dispatch.
     */
    public record AdjudicatedClaimRow(
            UUID claimId,
            UUID memberId,
            UUID providerId,
            String insuranceLine,
            BigDecimal approvedAmount,
            String currencyCode,
            OffsetDateTime adjudicatedAt) {}

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

    /**
     * Decodes the claims-service {@code /page} envelope into a filtered
     * list of facultative candidates. Anything without an approvedAmount
     * or below {@code minAmount} is dropped here so the caller sees the
     * ready-to-cede shape only.
     */
    private List<FacultativeCandidateRow> extractCandidateRows(String body, BigDecimal minAmount) {
        try {
            PageEnvelope envelope = objectMapper.readValue(body, new TypeReference<>() {});
            if (envelope == null || envelope.content() == null) return List.of();
            BigDecimal threshold = minAmount != null ? minAmount : BigDecimal.ZERO;
            return envelope.content().stream()
                    .filter(row -> row.approvedAmount() != null
                            && row.approvedAmount().compareTo(threshold) >= 0)
                    .map(row -> new FacultativeCandidateRow(
                            row.id(),
                            row.claimNumber(),
                            row.memberId(),
                            row.memberName(),
                            row.providerId(),
                            row.providerName(),
                            row.insuranceLine(),
                            row.approvedAmount(),
                            row.currencyCode(),
                            row.submissionDate(),
                            false))
                    .toList();
        } catch (Exception e) {
            log.warn("[claims-client] failed to decode /page envelope: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Minimal decode shape for {@code /api/v1/claims/page} — only the
     * fields the facultative-candidates browse needs. Kept private so
     * the wider service surface doesn't grow another duplicate of the
     * claims-service ClaimRow.
     */
    private record PageEnvelope(List<ClaimPageRow> content, long total, int page, int size, int totalPages) {}

    private record ClaimPageRow(
            UUID id,
            String claimNumber,
            UUID memberId,
            String memberName,
            UUID providerId,
            String providerName,
            String insuranceLine,
            BigDecimal approvedAmount,
            String currencyCode,
            Instant submissionDate) {}
}
