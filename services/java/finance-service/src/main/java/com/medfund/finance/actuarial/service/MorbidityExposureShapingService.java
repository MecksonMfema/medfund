package com.medfund.finance.actuarial.service;

import com.medfund.finance.client.UserServiceClient;
import com.medfund.finance.client.UserServiceClient.MorbidityExposureFeedRow;
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
import java.util.UUID;

/**
 * Shapes the Phase-14 MORBIDITY_STUDY input from two sources:
 * <ul>
 *   <li>The user-service morbidity-incidence feed — aggregated
 *       (age_band, sex, exposure_years, incidents) rows for the tenant's
 *       members over the requested window.</li>
 *   <li>The tenancy-service {@code tenant_morbidity_basis} table (public
 *       schema) — the basis name + multiplier the tenant has selected for
 *       the insurance line.</li>
 * </ul>
 *
 * <p>The output map is the {@code exposure} payload slot on
 * {@code ReportJobRequestedEvent} — Python's
 * {@code app.actuarial.morbidity.compute} reads it verbatim.
 *
 * <p>Missing basis is not fatal — the shaping service still emits a
 * cohort with a default basis name so the compute can surface a warning
 * rather than a hard failure. Mirrors the Phase-13
 * {@link MortalityExposureShapingService} approach; the CIDA basis is
 * the default for HEALTH morbidity and is always shipped as a YAML
 * fixture in ai-service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MorbidityExposureShapingService {

    private static final String DEFAULT_BASIS = "CIDA";

    private final UserServiceClient userServiceClient;
    private final DatabaseClient databaseClient;

    public Mono<MorbidityShapeResult> shape(MorbidityShapeRequest request) {
        Mono<List<MorbidityExposureFeedRow>> feed = userServiceClient.morbidityIncidenceFeed(
                request.periodStart(), request.periodEnd(), request.insuranceLine());
        Mono<Map<String, BasisRow>> basisByLine = loadBasis(request.tenantId(), request.insuranceLine());
        return Mono.zip(feed, basisByLine)
                .map(t -> assemble(t.getT1(), t.getT2(), request));
    }

    private MorbidityShapeResult assemble(List<MorbidityExposureFeedRow> feedRows,
                                          Map<String, BasisRow> basisByLine,
                                          MorbidityShapeRequest request) {
        List<String> warnings = new ArrayList<>();

        String line = request.insuranceLine() == null || request.insuranceLine().isBlank()
                ? "HEALTH"
                : request.insuranceLine().trim();

        BasisRow basis = basisByLine.get(line);
        String basisName;
        double multiplier;
        if (request.basisNameOverride() != null && !request.basisNameOverride().isBlank()) {
            basisName = request.basisNameOverride().trim();
            multiplier = request.multiplierOverride() != null ? request.multiplierOverride() : 1.0;
        } else if (basis != null) {
            basisName = basis.basisName();
            multiplier = basis.multiplier();
        } else {
            basisName = DEFAULT_BASIS;
            multiplier = 1.0;
            warnings.add(
                    "Tenant has no morbidity_basis row for line " + line
                            + " - falling back to " + DEFAULT_BASIS + " with multiplier 1.0"
            );
        }

        List<Map<String, Object>> bands = new ArrayList<>(feedRows.size());
        for (MorbidityExposureFeedRow row : feedRows) {
            Map<String, Object> band = new LinkedHashMap<>();
            band.put("age_band", row.ageBand());
            band.put("sex", row.sex());
            band.put("exposure_years", row.exposureYears());
            band.put("incidents", row.incidents());
            bands.add(band);
        }

        Map<String, Object> cohort = new LinkedHashMap<>();
        cohort.put("insurance_line", line);
        cohort.put("basis_name", basisName);
        cohort.put("multiplier", multiplier);
        cohort.put("bands", bands);

        if (bands.isEmpty()) {
            warnings.add("No member exposure in the requested window for line " + line
                    + " - MORBIDITY_STUDY result will be empty");
        }

        Map<String, Object> exposure = new LinkedHashMap<>();
        exposure.put("cohorts", List.of(cohort));
        return new MorbidityShapeResult(exposure, List.copyOf(warnings));
    }

    private Mono<Map<String, BasisRow>> loadBasis(UUID tenantId, String insuranceLine) {
        String sql = insuranceLine == null || insuranceLine.isBlank()
                ? """
                    SELECT insurance_line, basis_name, morbidity_multiplier
                      FROM public.tenant_morbidity_basis
                     WHERE tenant_id = :tenantId
                       AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
                       AND (effective_to   IS NULL OR effective_to   >= CURRENT_DATE)
                     ORDER BY insurance_line, effective_from DESC
                  """
                : """
                    SELECT insurance_line, basis_name, morbidity_multiplier
                      FROM public.tenant_morbidity_basis
                     WHERE tenant_id = :tenantId
                       AND insurance_line = :insuranceLine
                       AND (effective_from IS NULL OR effective_from <= CURRENT_DATE)
                       AND (effective_to   IS NULL OR effective_to   >= CURRENT_DATE)
                     ORDER BY effective_from DESC
                  """;
        var spec = databaseClient.sql(sql).bind("tenantId", tenantId);
        if (insuranceLine != null && !insuranceLine.isBlank()) {
            spec = spec.bind("insuranceLine", insuranceLine);
        }
        return spec
                .map((row, meta) -> new BasisRow(
                        row.get("insurance_line", String.class),
                        row.get("basis_name", String.class),
                        row.get("morbidity_multiplier", BigDecimal.class) == null
                                ? 1.0
                                : row.get("morbidity_multiplier", BigDecimal.class).doubleValue()))
                .all()
                .collectList()
                .map(list -> {
                    // Keep only the newest row per line — SQL sorts DESC on
                    // effective_from so the first row for each line wins.
                    Map<String, BasisRow> byLine = new LinkedHashMap<>();
                    for (BasisRow r : list) {
                        byLine.putIfAbsent(r.insuranceLine(), r);
                    }
                    return byLine;
                });
    }

    /** Input to the shaping call. */
    public record MorbidityShapeRequest(
            UUID tenantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String insuranceLine,
            String basisNameOverride,
            Double multiplierOverride) {

        public MorbidityShapeRequest {
            if (periodStart == null || periodEnd == null) {
                throw new IllegalArgumentException("periodStart and periodEnd are required");
            }
            if (periodStart.isAfter(periodEnd)) {
                throw new IllegalArgumentException("periodStart must be <= periodEnd");
            }
        }
    }

    /** Shape output — the exposure JSON slot + collected shaping warnings. */
    public record MorbidityShapeResult(Map<String, Object> exposure, List<String> warnings) {}

    private record BasisRow(String insuranceLine, String basisName, double multiplier) {}
}
