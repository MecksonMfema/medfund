package com.medfund.claims.service;

import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.HighCostClaimantRow;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import com.medfund.shared.config.TenantConfigClient;
import com.medfund.shared.report.FxRateReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * HIGH_COST_CLAIMANT report logic (Phase 4 §A, G46).
 *
 * <p>Semantics per plan §5: resolve the tenant's configured threshold
 * (V132 config table — an absent row is a config gap, not an error),
 * convert it to the reporting currency at {@code period.periodEnd}
 * (fail-loud on missing FX per G28), then aggregate every member's
 * cumulative {@code paid_amount} per currency in SQL and convert + filter
 * per row in the service layer. The post-SQL filter is deliberate — a
 * mixed-currency member would need a per-row FX lookup inside SQL, which
 * is not portable across Postgres versions and complicates testcontainer
 * seeding (plan §5 rationale).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HighCostClaimantService {

    /** Report payload + any config-gap warning to surface on the envelope. */
    public record HighCostResult(List<HighCostClaimantRow> rows, String configWarning) {}

    private final ClaimsReportQueryRepository repository;
    private final TenantConfigClient tenantConfigClient;
    private final FxRateReader fxRateReader;

    /**
     * Renders the report for one tenant. Empty when the tenant has no
     * threshold configured, with {@link HighCostResult#configWarning()} set
     * (G46 config gap) — the report itself succeeds.
     */
    public Mono<HighCostResult> report(LocalDate periodStart, LocalDate periodEnd,
                                       String reportingCurrency, UUID tenantId) {
        return tenantConfigClient.getHighCostClaimantConfig(tenantId)
                .flatMap(cfg -> fxRateReader.convert(
                                cfg.thresholdAmount(), cfg.currencyCode(), reportingCurrency,
                                periodEnd, tenantId)
                        .flatMap(thresholdReporting -> repository.highCostMemberTotals(periodStart, periodEnd)
                                .collectList()
                                .flatMap(rows -> convertAndFilter(rows, reportingCurrency,
                                        periodEnd, tenantId, thresholdReporting))
                                .map(rows -> new HighCostResult(rows, null))))
                .defaultIfEmpty(new HighCostResult(
                        List.of(), "High-cost threshold not configured for tenant"));
    }

    /**
     * Drill-down — paginated ledger of one member's contributing claims
     * (paid_amount &gt; 0 in the window), the rows that fed the report.
     */
    public Mono<PageResponse<ClaimsDetailResponse.ClaimLedgerRow>> memberDetail(
            UUID memberId, LocalDate periodStart, LocalDate periodEnd, int page, int size) {
        int offset = Math.max(page, 0) * Math.max(size, 1);
        return repository.memberClaimLedger(memberId, periodStart, periodEnd, offset, size)
                .collectList()
                .zipWith(repository.memberClaimLedgerCount(memberId, periodStart, periodEnd))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Mono<List<HighCostClaimantRow>> convertAndFilter(List<HighCostClaimantRow> rows,
                                                             String reportingCurrency,
                                                             LocalDate periodEnd, UUID tenantId,
                                                             BigDecimal thresholdReporting) {
        if (rows.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(rows)
                .flatMap(row -> fxRateReader.convert(
                                row.cumulativePaid(), row.currencyCode(), reportingCurrency,
                                periodEnd, tenantId)
                        .filter(converted -> converted.compareTo(thresholdReporting) > 0)
                        .map(converted -> withReporting(row, converted)))
                .sort(Comparator.comparing(HighCostClaimantRow::cumulativePaidReporting).reversed())
                .collectList();
    }

    private static HighCostClaimantRow withReporting(HighCostClaimantRow row, BigDecimal converted) {
        return new HighCostClaimantRow(
                row.memberId(), row.memberNumber(), row.memberName(), row.currencyCode(),
                row.cumulativePaid(), row.contributingClaims(), converted);
    }
}
