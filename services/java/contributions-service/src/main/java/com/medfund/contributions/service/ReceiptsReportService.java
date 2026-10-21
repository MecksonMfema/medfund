package com.medfund.contributions.service;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.ReceiptsAggregateRow;
import com.medfund.contributions.dto.ReceiptsDetailResponse;
import com.medfund.contributions.dto.ReceiptsSummaryRow;
import com.medfund.contributions.repository.ReceiptsReportQueryRepository;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.PerCurrencyTotal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin façade over {@link ReceiptsReportQueryRepository} — every method
 * returns a report payload (list or detail) sized for the envelope layer
 * to wrap. Zero in-memory aggregation; the repository does the SQL, the
 * controller does the envelope.
 *
 * <p>Symmetric to {@link BillingReportService} — same shape (per-scheme
 * / per-group / per-member summaries + detail drill-down + narrow
 * cross-service aggregates), same conventions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptsReportService {

    private final ReceiptsReportQueryRepository repository;

    // ── Per-scheme ─────────────────────────────────────────────────────────

    public Mono<List<ReceiptsSummaryRow>> perSchemeSummary(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perSchemeSummary(periodStart, periodEnd).collectList();
    }

    public Mono<Map<String, PerCurrencyTotal>> perSchemePerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perSchemePerCurrencyTotals(periodStart, periodEnd);
    }

    // ── Per-group ──────────────────────────────────────────────────────────

    public Mono<List<ReceiptsSummaryRow>> perGroupSummary(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perGroupSummary(periodStart, periodEnd).collectList();
    }

    public Mono<Map<String, PerCurrencyTotal>> perGroupPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perGroupPerCurrencyTotals(periodStart, periodEnd);
    }

    // ── Per-member (paginated + search) ────────────────────────────────────

    public Mono<PageResponse<ReceiptsSummaryRow>> perMemberSummary(LocalDate periodStart, LocalDate periodEnd,
                                                                    String search, String insuranceLine, UUID schemeId,
                                                                    int page, int size) {
        int offset = Math.max(page, 0) * Math.max(size, 1);
        return repository.perMemberSummary(periodStart, periodEnd, search, insuranceLine, schemeId, offset, size)
                .collectList()
                .zipWith(repository.perMemberSummaryCount(periodStart, periodEnd, search, insuranceLine, schemeId))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<Map<String, PerCurrencyTotal>> perMemberPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                           String search, String insuranceLine, UUID schemeId) {
        return repository.perMemberPerCurrencyTotals(periodStart, periodEnd, search, insuranceLine, schemeId);
    }

    // ── Detail drill-down ──────────────────────────────────────────────────

    /**
     * Detail payload for one scheme / group / member — monthly buckets +
     * paginated transaction ledger with optional type + currency filters.
     */
    public Mono<ReceiptsDetailResponse> detail(String dimension, UUID id,
                                                LocalDate periodStart, LocalDate periodEnd,
                                                String transactionType, String currencyCode,
                                                int page, int size) {
        String col = dimensionColumn(dimension);
        String dimensionName = "";
        int offset = Math.max(page, 0) * Math.max(size, 1);

        return repository.monthlyBuckets(col, id, periodStart, periodEnd).collectList()
                .zipWith(repository.ledger(col, id, periodStart, periodEnd, transactionType, currencyCode, offset, size)
                        .collectList()
                        .zipWith(repository.ledgerCount(col, id, periodStart, periodEnd, transactionType, currencyCode))
                        .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size)))
                .map(t -> new ReceiptsDetailResponse(id, dimensionName, t.getT1(), t.getT2()));
    }

    /**
     * Detail payload for the synthetic "Unallocated group payments" bucket
     * — scheme_id IS NULL. Same monthly-bucket shape as a normal scheme
     * drill-down but the ledger side has no member/group filter beyond the
     * receipts CTE itself.
     */
    public Mono<ReceiptsDetailResponse> unallocatedDetail(LocalDate periodStart, LocalDate periodEnd,
                                                           String transactionType, String currencyCode,
                                                           int page, int size) {
        int offset = Math.max(page, 0) * Math.max(size, 1);
        return repository.monthlyBucketsUnallocated(periodStart, periodEnd).collectList()
                .zipWith(repository.ledger("r.attributed_scheme_id", null, periodStart, periodEnd,
                                transactionType, currencyCode, offset, size)
                        .collectList()
                        .zipWith(repository.ledgerCount("r.attributed_scheme_id", null, periodStart, periodEnd,
                                transactionType, currencyCode))
                        .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size)))
                .map(t -> new ReceiptsDetailResponse(null, "Unallocated group payments", t.getT1(), t.getT2()));
    }

    // ── Cross-service aggregates (ungated) ─────────────────────────────────

    public Mono<List<ReceiptsAggregateRow>> aggregatePerScheme(LocalDate periodStart, LocalDate periodEnd) {
        return repository.aggregatePerScheme(periodStart, periodEnd).collectList();
    }

    public Mono<List<MonthlyAggregateRow>> aggregateMonthly(String dimension, LocalDate periodStart, LocalDate periodEnd) {
        return repository.aggregateMonthly(dimension, periodStart, periodEnd).collectList();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /** Maps public dimension enum to the SQL column the repository queries against. */
    static String dimensionColumn(String dimension) {
        if (dimension == null) throw new IllegalArgumentException("dimension is required");
        return switch (dimension.toUpperCase()) {
            case "SCHEME" -> "r.attributed_scheme_id";
            case "GROUP"  -> "r.group_id";
            case "MEMBER" -> "r.member_id";
            default -> throw new IllegalArgumentException(
                    "dimension must be SCHEME|GROUP|MEMBER, got '" + dimension + "'");
        };
    }
}
