package com.medfund.finance.regulatory.naic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.medfund.finance.regulatory.service.RegulatoryParameterResolver;
import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes NAIC Schedule P loss-ratio + total metrics using YAML defaults
 * for the parameter set. Loads
 * {@code regulatory-defaults/US_NAIC/{date}.yaml} — picks the highest
 * effective_from ≤ report period.
 *
 * <p>Phase 12 ships YAML-only resolution; Phase 15 wires the rules-engine
 * {@code REGULATORY_PARAMETER} category override in front of the YAML load
 * via {@code RegulatoryParameterResolver}. The calculator's public API
 * accepts pre-resolved {@link NaicSolvencyParameters} so a future retrofit
 * can inject the resolver without changing the compute surface.
 *
 * <p>All amounts are {@link BigDecimal}. FX conversion happens upstream
 * in {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy};
 * this calculator assumes every input is already in the report's
 * reporting currency (USD for NAIC Schedule P).
 */
@Slf4j
@Component
public class NaicSchedulePCalculator {

    private static final String JURISDICTION = "US_NAIC";
    private static final Pattern VERSION_FILENAME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\.yaml$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver(getClass().getClassLoader());

    // ── Parameter resolution ─────────────────────────────────────────────────

    /**
     * Load the effective parameter set for the given period-end. Fails loud
     * with {@link RegulatoryReportGenerationException} when no YAML matches
     * — a missing defaults file is a template-authoring gap that must not
     * silently zero the solvency numbers on a live submission.
     */
    /**
     * Phase 15b REG15 escape-hatch overload — resolve each parameter through
     * {@link RegulatoryParameterResolver} so a tenant's
     * {@code REGULATORY_PARAMETER} rule wins over the bundled YAML default.
     * Null resolver falls back to the sync YAML-only path for backward-compat
     * with unit tests that don't wire the resolver yet.
     */
    public Mono<NaicSolvencyParameters> resolveParameters(RegulatoryParameterResolver resolver,
                                                          UUID tenantId,
                                                          LocalDate effectiveDate) {
        if (resolver == null) return Mono.just(resolveParameters(effectiveDate));
        return Mono.zip(
                        resolver.resolve(tenantId, JURISDICTION,
                                NaicSolvencyParameters.KEY_MIN_RBC_RATIO_COMPANY_ACTION_LEVEL, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                NaicSolvencyParameters.KEY_ULAE_RATIO, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                NaicSolvencyParameters.KEY_LOSS_RATIO_HIGH_WATERMARK, effectiveDate))
                .map(t -> new NaicSolvencyParameters(t.getT1(), t.getT2(), t.getT3()));
    }

    public NaicSolvencyParameters resolveParameters(LocalDate effectiveDate) {
        String resourcePath = highestVersionedYaml(effectiveDate)
                .orElseThrow(() -> new RegulatoryReportGenerationException(
                        "No NAIC solvency defaults YAML found for effective date " + effectiveDate
                                + " under regulatory-defaults/" + JURISDICTION + "/. "
                                + "Phase 12 ships a 2024-06-01.yaml baseline."));
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            Object rawParams = root.get("parameters");
            if (!(rawParams instanceof Map<?, ?> params)) {
                throw new RegulatoryReportGenerationException(
                        "Malformed NAIC defaults YAML " + resourcePath
                                + " — top-level 'parameters:' map required.");
            }
            return new NaicSolvencyParameters(
                    requireDecimal(params, NaicSolvencyParameters.KEY_MIN_RBC_RATIO_COMPANY_ACTION_LEVEL, resourcePath),
                    requireDecimal(params, NaicSolvencyParameters.KEY_ULAE_RATIO, resourcePath),
                    requireDecimal(params, NaicSolvencyParameters.KEY_LOSS_RATIO_HIGH_WATERMARK, resourcePath));
        } catch (IOException e) {
            throw new RegulatoryReportGenerationException(
                    "Failed to read NAIC solvency defaults " + resourcePath, e);
        }
    }

    /** Package-visible for unit tests exercising the version-pick logic. */
    Optional<String> highestVersionedYaml(LocalDate effectiveDate) {
        if (effectiveDate == null) return Optional.empty();
        String pattern = "classpath*:regulatory-defaults/" + JURISDICTION + "/*.yaml";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("[naic-defaults] resource scan failed for {}: {}", pattern, e.getMessage());
            return Optional.empty();
        }
        List<String> filenames = new ArrayList<>();
        for (Resource r : resources) {
            String fn = r.getFilename();
            if (fn != null) filenames.add(fn);
        }
        return pickHighestVersion(filenames, effectiveDate);
    }

    /** Pure-function seam mirroring the IPEC / CMS version-pick logic. */
    static Optional<String> pickHighestVersion(List<String> filenames, LocalDate effectiveDate) {
        LocalDate best = null;
        String bestPath = null;
        for (String filename : filenames) {
            Matcher m = VERSION_FILENAME.matcher(filename);
            if (!m.find()) continue;
            LocalDate v;
            try {
                v = LocalDate.parse(m.group(1));
            } catch (java.time.format.DateTimeParseException e) {
                continue;
            }
            if (v.isAfter(effectiveDate)) continue;
            if (best == null || v.isAfter(best)) {
                best = v;
                bestPath = "regulatory-defaults/" + JURISDICTION + "/" + filename;
            }
        }
        return Optional.ofNullable(bestPath);
    }

    private static BigDecimal requireDecimal(Map<?, ?> params, String key, String source) {
        Object raw = params.get(key);
        if (raw == null) {
            throw new RegulatoryReportGenerationException(
                    "Missing required NAIC parameter '" + key + "' in " + source);
        }
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        if (raw instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) {
                throw new RegulatoryReportGenerationException(
                        "NAIC parameter '" + key + "' in " + source + " is not a decimal: " + s);
            }
        }
        throw new RegulatoryReportGenerationException(
                "NAIC parameter '" + key + "' in " + source + " has unsupported type: "
                        + raw.getClass().getSimpleName());
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    /**
     * Result of the per-accident-year Schedule P calculation — used by the
     * shaper to populate cells. All amounts two-decimal HALF_UP; loss
     * ratios 4-dp HALF_UP.
     */
    public record AccidentYearResult(
            BigDecimal incurred,
            BigDecimal paid,
            BigDecimal caseReserves,
            BigDecimal ibnr,
            BigDecimal earnedPremium,
            BigDecimal lossRatio) {}

    /** Aggregate result across the three accident years. */
    public record TotalsResult(
            BigDecimal totalIncurred,
            BigDecimal totalPaid,
            BigDecimal totalCaseReserves,
            BigDecimal totalIbnr,
            BigDecimal totalEarnedPremium,
            BigDecimal overallLossRatio) {}

    /**
     * Compute a single-accident-year result from paid + case + IBNR +
     * earned premium. {@code incurred = paid + case_reserves + ibnr}
     * (already includes ULAE if present in the paid input; if the shaper
     * cannot split ULAE from paid it can multiply raw paid by
     * {@code (1 + ulaeRatio)} upstream — this calculator treats the input
     * as pre-loaded).
     */
    public AccidentYearResult computeAccidentYear(
            BigDecimal paid,
            BigDecimal caseReserves,
            BigDecimal ibnr,
            BigDecimal earnedPremium) {
        requireNonNull(paid, "paid");
        requireNonNull(caseReserves, "caseReserves");
        requireNonNull(ibnr, "ibnr");
        requireNonNull(earnedPremium, "earnedPremium");
        BigDecimal incurred = paid.add(caseReserves).add(ibnr);
        BigDecimal lossRatio = earnedPremium.signum() == 0
                ? BigDecimal.ZERO
                : incurred.divide(earnedPremium, MathContext.DECIMAL64)
                        .setScale(4, RoundingMode.HALF_UP);
        return new AccidentYearResult(
                incurred.setScale(2, RoundingMode.HALF_UP),
                paid.setScale(2, RoundingMode.HALF_UP),
                caseReserves.setScale(2, RoundingMode.HALF_UP),
                ibnr.setScale(2, RoundingMode.HALF_UP),
                earnedPremium.setScale(2, RoundingMode.HALF_UP),
                lossRatio);
    }

    /**
     * Compute the across-accident-year totals + overall loss ratio.
     * {@code overallLossRatio = total_incurred / total_earned_premium}
     * (4-dp HALF_UP) — zero premium yields zero ratio.
     */
    public TotalsResult computeTotals(List<AccidentYearResult> ayResults) {
        if (ayResults == null || ayResults.isEmpty()) {
            throw new RegulatoryReportGenerationException("ayResults required");
        }
        BigDecimal totalIncurred = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalCase = BigDecimal.ZERO;
        BigDecimal totalIbnr = BigDecimal.ZERO;
        BigDecimal totalPremium = BigDecimal.ZERO;
        for (AccidentYearResult r : ayResults) {
            totalIncurred = totalIncurred.add(r.incurred());
            totalPaid = totalPaid.add(r.paid());
            totalCase = totalCase.add(r.caseReserves());
            totalIbnr = totalIbnr.add(r.ibnr());
            totalPremium = totalPremium.add(r.earnedPremium());
        }
        BigDecimal overallLossRatio = totalPremium.signum() == 0
                ? BigDecimal.ZERO
                : totalIncurred.divide(totalPremium, MathContext.DECIMAL64)
                        .setScale(4, RoundingMode.HALF_UP);
        return new TotalsResult(
                totalIncurred.setScale(2, RoundingMode.HALF_UP),
                totalPaid.setScale(2, RoundingMode.HALF_UP),
                totalCase.setScale(2, RoundingMode.HALF_UP),
                totalIbnr.setScale(2, RoundingMode.HALF_UP),
                totalPremium.setScale(2, RoundingMode.HALF_UP),
                overallLossRatio);
    }

    private static void requireNonNull(BigDecimal amount, String name) {
        if (amount == null) {
            throw new RegulatoryReportGenerationException(name + " required");
        }
    }
}
