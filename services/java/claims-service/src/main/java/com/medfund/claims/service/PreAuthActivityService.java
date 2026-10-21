package com.medfund.claims.service;

import com.medfund.claims.dto.PreAuthActivityResponse;
import com.medfund.claims.dto.PreAuthActivityRow;
import com.medfund.claims.repository.ClaimsReportQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PRE_AUTH_ACTIVITY report logic (Phase 4 §A, G43). Reads
 * {@code pre_authorizations} on the {@code requested_date} clock and
 * composes the claims-side R04/R05 rejection signal as a companion metric
 * (F55 — the classical pre-auth utilisation calc is un-computable from
 * stored data, so the report surfaces activity + this proxy).
 *
 * <p>Two independent SQL aggregates (per-status counts + claims-side
 * R04/R05 count) are composed here; the approval / expiry rate columns are
 * populated per-status where meaningful — the share of all pre-auths in the
 * window (per currency) that were approved / expired.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreAuthActivityService {

    private final ClaimsReportQueryRepository repository;

    public Mono<PreAuthActivityResponse> activity(LocalDate periodStart, LocalDate periodEnd,
                                                  String status, UUID providerId) {
        return repository.preAuthActivity(periodStart, periodEnd, status, providerId).collectList()
                .map(this::decorateRates)
                .zipWith(repository.r04r05Signal(periodStart, periodEnd))
                .map(t -> new PreAuthActivityResponse(t.getT1(), t.getT2()));
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Per-status, per-currency approval / expiry share columns. The rate is
     * only meaningful on the status the rate names (APPROVED / EXPIRED) —
     * the share of all window pre-auths (that currency) in that state.
     */
    private List<PreAuthActivityRow> decorateRates(List<PreAuthActivityRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<String, Long> totalByCurrency = rows.stream().collect(Collectors.groupingBy(
                PreAuthActivityRow::currencyCode, Collectors.summingLong(PreAuthActivityRow::count)));
        return rows.stream().map(row -> {
            BigDecimal approval = "APPROVED".equals(row.status())
                    ? share(row.count(), totalByCurrency.getOrDefault(row.currencyCode(), row.count()))
                    : null;
            BigDecimal expiry = "EXPIRED".equals(row.status())
                    ? share(row.count(), totalByCurrency.getOrDefault(row.currencyCode(), row.count()))
                    : null;
            return new PreAuthActivityRow(row.status(), row.currencyCode(), row.count(),
                    row.totalRequested(), row.totalApproved(), row.avgDecisionDays(), approval, expiry);
        }).toList();
    }

    private static BigDecimal share(long part, long total) {
        if (total <= 0) return null;
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
