package com.medfund.claims.service;

import com.medfund.claims.dto.ClaimStatusMatrixResponse;
import com.medfund.claims.dto.ClaimsAggregateRow;
import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.ClaimsSummaryRow;
import com.medfund.claims.dto.DenialAnalysisResponse;
import com.medfund.claims.dto.FrequencySeverityRow;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.PerCurrencyTotal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin façade over {@link ClaimsReportQueryRepository} — every method
 * returns a report payload sized for the envelope layer to wrap. Zero
 * in-memory aggregation; the repository does the SQL, the controller does
 * the envelope.
 *
 * <p>Symmetric to Phase 3 {@code ReceiptsReportService} in
 * contributions-service — same shape (per-scheme / per-provider summaries
 * + detail drill-down + narrow cross-service aggregates), same conventions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimsReportService {

    private final ClaimsReportQueryRepository repository;

    // ── Per-scheme ─────────────────────────────────────────────────────────

    public Mono<List<ClaimsSummaryRow>> perSchemeSummary(LocalDate periodStart, LocalDate periodEnd,
                                                         String insuranceLine) {
        return repository.perSchemeSummary(periodStart, periodEnd, insuranceLine).collectList();
    }

    // ── Per-provider ───────────────────────────────────────────────────────

    public Mono<List<ClaimsSummaryRow>> perProviderSummary(LocalDate periodStart, LocalDate periodEnd,
                                                           String insuranceLine) {
        return repository.perProviderSummary(periodStart, periodEnd, insuranceLine).collectList();
    }

    // ── Per-group (CLAIMS_SUMMARY §B G45) ──────────────────────────────────

    public Mono<List<ClaimsSummaryRow>> perGroupSummary(LocalDate periodStart, LocalDate periodEnd,
                                                        String insuranceLine) {
        return repository.perGroupSummary(periodStart, periodEnd, insuranceLine).collectList();
    }

    // ── Per-member (CLAIMS_SUMMARY §B G45) ─────────────────────────────────

    public Mono<PageResponse<ClaimsSummaryRow>> perMemberSummary(LocalDate periodStart, LocalDate periodEnd,
                                                                 String search, String insuranceLine,
                                                                 UUID schemeId, UUID providerId,
                                                                 int page, int size) {
        int offset = Math.max(page, 0) * Math.max(size, 1);
        return repository.perMemberSummary(periodStart, periodEnd, search, insuranceLine, schemeId, providerId,
                        offset, size)
                .collectList()
                .zipWith(repository.perMemberCount(periodStart, periodEnd, search, insuranceLine,
                        schemeId, providerId))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<Map<String, PerCurrencyTotal>> memberPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                       String search, String insuranceLine,
                                                                       UUID schemeId, UUID providerId) {
        return repository.memberPerCurrencyTotals(periodStart, periodEnd, search, insuranceLine,
                schemeId, providerId);
    }

    // ── Filtered-set native totals (G6/G18) ────────────────────────────────

    public Mono<Map<String, PerCurrencyTotal>> claimsPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                       String insuranceLine) {
        return repository.claimsPerCurrencyTotals(periodStart, periodEnd, insuranceLine);
    }

    // ── Detail drill-down ──────────────────────────────────────────────────

    /**
     * Detail payload for one scheme / provider / group / member — monthly
     * funnel buckets + paginated claim ledger with optional status /
     * provider / currency filters. The dimension display name resolves via
     * the repository (empty when the id is unknown, so a drill never fails
     * on a dangling reference).
     */
    public Mono<ClaimsDetailResponse> detail(String dimension, UUID id,
                                             LocalDate periodStart, LocalDate periodEnd,
                                             String status, UUID providerId, String currencyCode,
                                             int page, int size) {
        String col = dimensionColumn(dimension);
        int offset = Math.max(page, 0) * Math.max(size, 1);

        return repository.monthlyBuckets(col, id, periodStart, periodEnd).collectList()
                .zipWith(repository.ledger(col, id, periodStart, periodEnd,
                                status, providerId, currencyCode, offset, size)
                        .collectList()
                        .zipWith(repository.ledgerCount(col, id, periodStart, periodEnd,
                                status, providerId, currencyCode))
                        .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size)))
                .zipWith(repository.dimensionName(dimension, id).defaultIfEmpty(""))
                .map(t -> new ClaimsDetailResponse(id, t.getT2(),
                        t.getT1().getT1(), t.getT1().getT2()));
    }

    // ── CLAIM_STATUS_LIST (G49) ────────────────────────────────────────────

    public Mono<ClaimStatusMatrixResponse> statusMatrix(LocalDate submittedFrom, LocalDate submittedTo,
                                                        String insuranceLine) {
        return repository.statusMatrixCells(submittedFrom, submittedTo, insuranceLine)
                .collectList()
                .map(cells -> new ClaimStatusMatrixResponse(submittedFrom, submittedTo, cells, Instant.now()));
    }

    public Mono<PageResponse<ClaimsDetailResponse.ClaimLedgerRow>> statusMatrixDrill(
            LocalDate submittedFrom, LocalDate submittedTo,
            String status, String ageBucket, int page, int size) {
        int offset = Math.max(page, 0) * Math.max(size, 1);
        return repository.statusMatrixLedger(submittedFrom, submittedTo, status, ageBucket, offset, size)
                .collectList()
                .zipWith(repository.statusMatrixLedgerCount(submittedFrom, submittedTo, status, ageBucket))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    // ── DENIAL_ANALYSIS (G47) ──────────────────────────────────────────────

    /**
     * Composite denial analysis. {@code monthlyTrend} is only queried when
     * the window spans more than one calendar month (G47) — a single-month
     * window renders an empty trend.
     */
    public Mono<DenialAnalysisResponse> denialAnalysis(LocalDate periodStart, LocalDate periodEnd,
                                                       String category, String code, UUID providerId) {
        boolean multiMonth = periodStart != null && periodEnd != null
                && !YearMonth.from(periodStart).equals(YearMonth.from(periodEnd));
        return Mono.zip(
                        repository.denialByCategory(periodStart, periodEnd, category, code, providerId).collectList(),
                        repository.denialByCode(periodStart, periodEnd, category, code, providerId).collectList(),
                        repository.denialByProvider(periodStart, periodEnd, category, code, providerId).collectList(),
                        multiMonth
                                ? repository.denialMonthlyTrend(periodStart, periodEnd, category, code, providerId).collectList()
                                : Mono.<List<DenialAnalysisResponse.MonthlyRow>>just(List.of()))
                .map(t -> new DenialAnalysisResponse(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }

    // ── CLAIMS_FREQUENCY_SEVERITY (G48) ────────────────────────────────────

    /** Report payload + the exposure-proxy caveat to surface on the envelope. */
    public record FrequencySeverityResult(List<FrequencySeverityRow> rows, String exposureWarning) {}

    /**
     * Frequency + severity on the service-date clock. Exposure always uses
     * the G48 fallback (active members × days ÷ 30.4375) because
     * {@code member_status_history} does not exist — the warning names the
     * caveat so a reader treats the metric as a static proxy.
     */
    public Mono<FrequencySeverityResult> frequencySeverity(LocalDate serviceFrom, LocalDate serviceTo,
                                                           String insuranceLine) {
        long days = ChronoUnit.DAYS.between(serviceFrom, serviceTo) + 1;
        return repository.frequencySeverity(serviceFrom, serviceTo, insuranceLine, days)
                .collectList()
                .map(rows -> new FrequencySeverityResult(rows, FREQUENCY_SEVERITY_EXPOSURE_WARNING));
    }

    // ── Cross-service aggregates (ungated, G44) ────────────────────────────

    public Mono<List<ClaimsAggregateRow>> aggregate(String dimension, LocalDate periodStart, LocalDate periodEnd) {
        return repository.aggregate(dimension, periodStart, periodEnd).collectList();
    }

    public Mono<List<MonthlyAggregateRow>> aggregateMonthly(String dimension,
                                                            LocalDate periodStart, LocalDate periodEnd) {
        return repository.aggregateMonthly(dimension, periodStart, periodEnd).collectList();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /** Exposure-proxy caveat (G48) — member_status_history absent. */
    static final String FREQUENCY_SEVERITY_EXPOSURE_WARNING =
            "Exposure is a static proxy (active members × days ÷ 30.4375) — "
                    + "member_status_history is absent so member-months cannot be computed.";

    /** Maps public dimension enum to the SQL column the repository queries against. */
    static String dimensionColumn(String dimension) {
        if (dimension == null) throw new IllegalArgumentException("dimension is required");
        return switch (dimension.toUpperCase()) {
            case "SCHEME"   -> "c.scheme_id";
            case "PROVIDER" -> "c.provider_id";
            case "GROUP"    -> "m.group_id";
            case "MEMBER"   -> "c.member_id";
            default -> throw new IllegalArgumentException(
                    "dimension must be SCHEME|PROVIDER|GROUP|MEMBER, got '" + dimension + "'");
        };
    }
}
