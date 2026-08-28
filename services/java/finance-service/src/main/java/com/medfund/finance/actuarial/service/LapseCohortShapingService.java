package com.medfund.finance.actuarial.service;

import com.medfund.finance.client.UserServiceClient;
import com.medfund.finance.client.UserServiceClient.PersistencyCohortFeedRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Shapes the Phase-12 LAPSE_STUDY input from the same two feeds the
 * persistency study reads:
 * <ul>
 *   <li>The user-service persistency-cohort feed — one row per
 *       (cohortMonth, line, checkpoint) with size + still-active counts.
 *       Lapse is the complement of retention, so we relabel the payload
 *       key to {@code still_active} (Python's compute expects that name)
 *       and let it derive {@code lapsed_count = cohort_size -
 *       still_active}.</li>
 *   <li>The tenancy-service {@code tenant_persistency_basis} table (public
 *       schema) — expected retention percentages per (line, cohort_months).
 *       The Python compute converts these to expected lapse via
 *       {@code 1 - expected_retention_pct} so the pair sums to 1.0 by
 *       construction and both reports reconcile against each other.</li>
 * </ul>
 * The output map is the {@code cohort} payload slot on
 * {@code ActuarialJobRequestedEvent} — Python's {@code app.actuarial.lapse.compute}
 * consumes it directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LapseCohortShapingService {

    private final UserServiceClient userServiceClient;
    private final DatabaseClient databaseClient;

    public Mono<LapseShapeResult> shape(LapseShapeRequest request) {
        List<Integer> checkpoints = request.checkpoints() == null ? List.of() : request.checkpoints();
        Mono<List<PersistencyCohortFeedRow>> feed =
                userServiceClient.persistencyCohortFeed(
                        request.periodStart(), request.periodEnd(),
                        checkpoints, request.insuranceLine());
        Mono<Map<String, List<BasisPoint>>> basis = loadExpectedBasis(request.tenantId(), request.insuranceLine());
        return Mono.zip(feed, basis)
                .map(t -> assemble(t.getT1(), t.getT2()));
    }

    private LapseShapeResult assemble(List<PersistencyCohortFeedRow> feedRows,
                                      Map<String, List<BasisPoint>> basis) {
        List<String> warnings = new ArrayList<>();
        Map<CohortKey, CohortAccumulator> cohortMap = new LinkedHashMap<>();
        for (PersistencyCohortFeedRow row : feedRows) {
            CohortKey key = new CohortKey(row.cohortMonth().toString().substring(0, 7),
                    row.insuranceLine());
            CohortAccumulator acc = cohortMap.computeIfAbsent(key, k -> new CohortAccumulator());
            if (row.cohortSize() > acc.size) {
                acc.size = row.cohortSize();
            }
            acc.checkpoints.put(row.checkpointMonths(), row.stillActive());
        }

        List<Map<String, Object>> cohorts = new ArrayList<>(cohortMap.size());
        for (Map.Entry<CohortKey, CohortAccumulator> entry : cohortMap.entrySet()) {
            Map<String, Object> cohort = new LinkedHashMap<>();
            cohort.put("cohort_month", entry.getKey().month());
            cohort.put("insurance_line", entry.getKey().line());
            cohort.put("cohort_size", entry.getValue().size);
            List<Map<String, Object>> checkpointList = new ArrayList<>(entry.getValue().checkpoints.size());
            entry.getValue().checkpoints.forEach((months, stillActive) -> {
                Map<String, Object> cp = new LinkedHashMap<>();
                cp.put("months", months);
                cp.put("still_active", stillActive);
                checkpointList.add(cp);
            });
            cohort.put("checkpoints", checkpointList);
            cohorts.add(cohort);
        }
        Map<String, List<Map<String, Object>>> basisPayload = new LinkedHashMap<>();
        basis.forEach((line, points) -> {
            List<Map<String, Object>> pointList = new ArrayList<>(points.size());
            for (BasisPoint p : points) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("cohort_months", p.cohortMonths());
                pt.put("expected_retention_pct", p.expectedRetentionPct());
                pointList.add(pt);
            }
            basisPayload.put(line, pointList);
        });

        if (cohorts.isEmpty()) {
            warnings.add("No cohorts landed in the requested period; LAPSE_STUDY result will be empty");
        }
        if (basisPayload.isEmpty()) {
            warnings.add("Tenant has no persistency_basis rows for the selected line — A/E ratios will be null");
        }

        Map<String, Object> cohort = new LinkedHashMap<>();
        cohort.put("cohorts", cohorts);
        cohort.put("expected_basis", basisPayload);
        return new LapseShapeResult(cohort, List.copyOf(warnings));
    }

    private Mono<Map<String, List<BasisPoint>>> loadExpectedBasis(UUID tenantId, String insuranceLine) {
        String sql = insuranceLine == null || insuranceLine.isBlank()
                ? """
                    SELECT insurance_line, cohort_months, expected_retention_pct
                      FROM public.tenant_persistency_basis
                     WHERE tenant_id = :tenantId
                       AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
                       AND (effective_to   IS NULL OR effective_to   >= CURRENT_DATE)
                     ORDER BY insurance_line, cohort_months
                  """
                : """
                    SELECT insurance_line, cohort_months, expected_retention_pct
                      FROM public.tenant_persistency_basis
                     WHERE tenant_id = :tenantId
                       AND insurance_line = :insuranceLine
                       AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
                       AND (effective_to   IS NULL OR effective_to   >= CURRENT_DATE)
                     ORDER BY cohort_months
                  """;
        var spec = databaseClient.sql(sql).bind("tenantId", tenantId);
        if (insuranceLine != null && !insuranceLine.isBlank()) {
            spec = spec.bind("insuranceLine", insuranceLine);
        }
        return spec
                .map((row, meta) -> new LineBasisPoint(
                        row.get("insurance_line", String.class),
                        row.get("cohort_months", Integer.class),
                        row.get("expected_retention_pct", BigDecimal.class)))
                .all()
                .collectList()
                .map(list -> {
                    Map<String, List<BasisPoint>> byLine = new TreeMap<>();
                    for (LineBasisPoint p : list) {
                        byLine.computeIfAbsent(p.line(), k -> new ArrayList<>())
                                .add(new BasisPoint(p.cohortMonths(),
                                        p.expectedRetentionPct() == null
                                                ? 0.0
                                                : p.expectedRetentionPct().doubleValue()));
                    }
                    return byLine;
                });
    }

    /** Input to the shaping call — tenantId + window + optional filters. */
    public record LapseShapeRequest(
            UUID tenantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<Integer> checkpoints,
            String insuranceLine) {

        public LapseShapeRequest {
            if (periodStart == null || periodEnd == null) {
                throw new IllegalArgumentException("periodStart and periodEnd are required");
            }
            if (periodStart.isAfter(periodEnd)) {
                throw new IllegalArgumentException("periodStart must be <= periodEnd");
            }
        }
    }

    /** Shape output — the cohort JSON slot + collected shaping warnings. */
    public record LapseShapeResult(Map<String, Object> cohort, List<String> warnings) {}

    private record CohortKey(String month, String line) {}

    private static class CohortAccumulator {
        long size;
        Map<Integer, Long> checkpoints = new TreeMap<>();
    }

    private record BasisPoint(int cohortMonths, double expectedRetentionPct) {}

    private record LineBasisPoint(String line, Integer cohortMonths, BigDecimal expectedRetentionPct) {}
}
