package com.medfund.finance.kpi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 18 K9 — fetch the latest committed IBNR total for a period. Reads
 * from {@code report_job.result_json} (inline JSON payload committed by the
 * ai-service chain-ladder consumer). Returns empty when no completed
 * {@link ReportKey#IBNR_TRIANGLE} run exists within the 90-day window
 * ending at {@code periodEnd} — the caller
 * ({@link KpiComposerService}) surfaces a warning on the envelope.
 *
 * <p>Deviation from Phase 5 plan (2026-09-05): the plan referenced a
 * {@code ReportJobPayloadStore} with MinIO fallback for oversize
 * {@code payload_ref} payloads. The {@code report_job} entity does not
 * carry a {@code payload_ref} column (verified 2026-09-05 against
 * {@link ReportJob}) and no such store exists in the codebase, so the
 * lookup is inline-only. IBNR result payloads from chain-ladder are small
 * (a handful of floats) and fit inline; if a future actuarial method
 * produces oversize IBNR results, that migration will add the column and
 * the MinIO branch here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbnrLookupService {

    private static final int WINDOW_DAYS = 90;
    private static final String STATUS_COMPLETED = "completed";

    private final ReportJobRepository reportJobRepository;
    private final ObjectMapper objectMapper;

    /**
     * Latest committed IBNR total for {@code (tenantId, insuranceLineFilter)}
     * at or before {@code periodEnd}. Appends a warning to {@code warnings}
     * when no in-window run exists.
     *
     * @param insuranceLineFilter {@code null} → sum {@code ibnr_total} scalar
     *      from the payload; else sum the matching entries in
     *      {@code per_cohort_ultimate[].ibnr} filtered by insurance_line
     */
    public Mono<Optional<BigDecimal>> latestIbnrTotal(UUID tenantId,
                                                      LocalDate periodEnd,
                                                      String insuranceLineFilter,
                                                      List<String> warnings) {
        OffsetDateTime completedFloor = periodEnd.minusDays(WINDOW_DAYS)
                .atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return reportJobRepository
                .findFirstByTenantIdAndReportKeyAndStatusAndCompletedAtGreaterThanEqualOrderByCompletedAtDesc(
                        tenantId, ReportKey.IBNR_TRIANGLE.name(), STATUS_COMPLETED, completedFloor)
                .flatMap(job -> parseIbnrTotal(job, insuranceLineFilter))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .doOnNext(opt -> {
                    if (opt.isEmpty() && warnings != null) {
                        String line = insuranceLineFilter != null ? insuranceLineFilter : "all lines";
                        warnings.add("IBNR run pending or older than " + WINDOW_DAYS
                                + " days for (line=" + line + ", asOf=" + periodEnd
                                + ") - displaying paid + Δreserve only");
                    }
                });
    }

    private Mono<BigDecimal> parseIbnrTotal(ReportJob job, String insuranceLineFilter) {
        if (job.getResultJson() == null) {
            log.debug("IBNR job {} has null result_json — skipping", job.getJobId());
            return Mono.empty();
        }
        return Mono.fromCallable(() -> objectMapper.readTree(job.getResultJson().asString()))
                .onErrorResume(err -> {
                    log.warn("IBNR job {} result_json unreadable: {}", job.getJobId(), err.getMessage());
                    return Mono.empty();
                })
                .map(root -> {
                    if (insuranceLineFilter == null) {
                        JsonNode t = root.get("ibnr_total");
                        return t != null && !t.isNull() ? new BigDecimal(t.asText()) : BigDecimal.ZERO;
                    }
                    JsonNode arr = root.get("per_cohort_ultimate");
                    if (arr == null || !arr.isArray()) {
                        return BigDecimal.ZERO;
                    }
                    BigDecimal sum = BigDecimal.ZERO;
                    for (JsonNode row : arr) {
                        if (insuranceLineFilter.equalsIgnoreCase(row.path("insurance_line").asText())) {
                            JsonNode ibnr = row.get("ibnr");
                            if (ibnr != null && !ibnr.isNull()) {
                                sum = sum.add(new BigDecimal(ibnr.asText()));
                            }
                        }
                    }
                    return sum;
                });
    }
}
