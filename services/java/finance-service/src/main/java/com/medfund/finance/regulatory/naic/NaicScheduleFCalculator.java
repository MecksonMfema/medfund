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
 * Computes NAIC Schedule F ceded-reinsurance totals + statutory
 * Provision for Reinsurance using YAML defaults for the provision
 * percentages. Loads {@code regulatory-defaults/US_NAIC/{date}.yaml} —
 * picks the highest effective_from ≤ report period.
 *
 * <p>Phase 13 ships YAML-only resolution; Phase 15 wires the rules-engine
 * {@code REGULATORY_PARAMETER} category override in front of the YAML load
 * via {@code RegulatoryParameterResolver}. The calculator's public API
 * accepts pre-resolved {@link NaicScheduleFParameters} so a future
 * retrofit can inject the resolver without changing the compute surface.
 *
 * <p>All amounts are {@link BigDecimal}. FX conversion happens upstream
 * in {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy};
 * this calculator assumes every input is already in the report's
 * reporting currency (USD for NAIC Schedule F).
 */
@Slf4j
@Component
public class NaicScheduleFCalculator {

    private static final String JURISDICTION = "US_NAIC";
    private static final Pattern VERSION_FILENAME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\.yaml$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver(getClass().getClassLoader());

    // ── Parameter resolution ─────────────────────────────────────────────────

    /**
     * Load the effective Schedule F parameter set for the given period-end.
     * Fails loud with {@link RegulatoryReportGenerationException} when no
     * YAML matches or the file is missing a required key — a missing
     * defaults file is a template-authoring gap that must not silently
     * zero the provision on a live submission.
     */
    /**
     * Phase 15b REG15 escape-hatch overload — resolve each parameter through
     * {@link RegulatoryParameterResolver} so a tenant's
     * {@code REGULATORY_PARAMETER} rule wins over the bundled YAML default.
     * Null resolver falls back to the sync YAML-only path for backward-compat
     * with unit tests that don't wire the resolver yet.
     */
    public Mono<NaicScheduleFParameters> resolveParameters(RegulatoryParameterResolver resolver,
                                                           UUID tenantId,
                                                           LocalDate effectiveDate) {
        if (resolver == null) return Mono.just(resolveParameters(effectiveDate));
        return Mono.zip(
                        resolver.resolve(tenantId, JURISDICTION,
                                NaicScheduleFParameters.KEY_UNAUTHORIZED_PROVISION_PCT, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                NaicScheduleFParameters.KEY_CERTIFIED_PROVISION_PCT, effectiveDate))
                .map(t -> new NaicScheduleFParameters(t.getT1(), t.getT2()));
    }

    public NaicScheduleFParameters resolveParameters(LocalDate effectiveDate) {
        String resourcePath = highestVersionedYaml(effectiveDate)
                .orElseThrow(() -> new RegulatoryReportGenerationException(
                        "No NAIC Schedule F defaults YAML found for effective date " + effectiveDate
                                + " under regulatory-defaults/" + JURISDICTION + "/. "
                                + "Phase 12 ships a 2024-06-01.yaml baseline; Phase 13 "
                                + "extends it with the Schedule F provision percentages."));
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            Object rawParams = root.get("parameters");
            if (!(rawParams instanceof Map<?, ?> params)) {
                throw new RegulatoryReportGenerationException(
                        "Malformed NAIC defaults YAML " + resourcePath
                                + " — top-level 'parameters:' map required.");
            }
            return new NaicScheduleFParameters(
                    requireDecimal(params, NaicScheduleFParameters.KEY_UNAUTHORIZED_PROVISION_PCT, resourcePath),
                    requireDecimal(params, NaicScheduleFParameters.KEY_CERTIFIED_PROVISION_PCT, resourcePath));
        } catch (IOException e) {
            throw new RegulatoryReportGenerationException(
                    "Failed to read NAIC Schedule F defaults " + resourcePath, e);
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
            log.warn("[naic-f-defaults] resource scan failed for {}: {}", pattern, e.getMessage());
            return Optional.empty();
        }
        List<String> filenames = new ArrayList<>();
        for (Resource r : resources) {
            String fn = r.getFilename();
            if (fn != null) filenames.add(fn);
        }
        return pickHighestVersion(filenames, effectiveDate);
    }

    /** Pure-function seam mirroring the Schedule P version-pick logic. */
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
                    "Missing required NAIC Schedule F parameter '" + key + "' in " + source);
        }
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        if (raw instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) {
                throw new RegulatoryReportGenerationException(
                        "NAIC Schedule F parameter '" + key + "' in " + source + " is not a decimal: " + s);
            }
        }
        throw new RegulatoryReportGenerationException(
                "NAIC Schedule F parameter '" + key + "' in " + source + " has unsupported type: "
                        + raw.getClass().getSimpleName());
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    /**
     * Aggregated per-stratum result — three fields per stratum, plus the
     * combined recoverable ({@code lossesPaid + lossesUnpaid}) exposed as
     * a convenience for the provision calc.
     */
    public record StratumResult(
            BigDecimal premiums,
            BigDecimal lossesPaid,
            BigDecimal lossesUnpaid,
            BigDecimal recoverable) {}

    /** Aggregate result across the four ceded strata + statutory provision. */
    public record CededTotalsResult(
            BigDecimal totalCededPremiums,
            BigDecimal totalCededLossesPaid,
            BigDecimal totalCededLossesUnpaid,
            BigDecimal totalReinsuranceRecoverable,
            BigDecimal provisionForReinsurance,
            BigDecimal netReinsurancePosition) {}

    /**
     * Roll a single stratum's three raw amounts into a {@link StratumResult}
     * — passthrough with two-decimal normalisation and the recoverable
     * ({@code lossesPaid + lossesUnpaid}) precomputed.
     */
    public StratumResult computeStratum(BigDecimal premiums,
                                        BigDecimal lossesPaid,
                                        BigDecimal lossesUnpaid) {
        requireNonNull(premiums, "premiums");
        requireNonNull(lossesPaid, "lossesPaid");
        requireNonNull(lossesUnpaid, "lossesUnpaid");
        return new StratumResult(
                premiums.setScale(2, RoundingMode.HALF_UP),
                lossesPaid.setScale(2, RoundingMode.HALF_UP),
                lossesUnpaid.setScale(2, RoundingMode.HALF_UP),
                lossesPaid.add(lossesUnpaid).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Compute the across-stratum ceded totals + statutory provision.
     * {@code provision = (unauthorized recoverable × unauthorizedPct)
     * + (certified recoverable × certifiedPct)}; affiliated and
     * authorized strata carry no provision.
     * {@code netReinsurancePosition = totalReinsuranceRecoverable − provision}.
     */
    public CededTotalsResult computeCededTotals(StratumResult affiliated,
                                                 StratumResult authorized,
                                                 StratumResult unauthorized,
                                                 StratumResult certified,
                                                 NaicScheduleFParameters parameters) {
        requireNonNullResult(affiliated, "affiliated");
        requireNonNullResult(authorized, "authorized");
        requireNonNullResult(unauthorized, "unauthorized");
        requireNonNullResult(certified, "certified");
        if (parameters == null) {
            throw new RegulatoryReportGenerationException("parameters required");
        }
        BigDecimal totalPremiums = affiliated.premiums()
                .add(authorized.premiums())
                .add(unauthorized.premiums())
                .add(certified.premiums());
        BigDecimal totalPaid = affiliated.lossesPaid()
                .add(authorized.lossesPaid())
                .add(unauthorized.lossesPaid())
                .add(certified.lossesPaid());
        BigDecimal totalUnpaid = affiliated.lossesUnpaid()
                .add(authorized.lossesUnpaid())
                .add(unauthorized.lossesUnpaid())
                .add(certified.lossesUnpaid());
        BigDecimal totalRecoverable = totalPaid.add(totalUnpaid);
        BigDecimal provision = unauthorized.recoverable()
                .multiply(parameters.unauthorizedReinsurerProvisionPercentage())
                .add(certified.recoverable()
                        .multiply(parameters.certifiedReinsurerProvisionPercentage()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = totalRecoverable.subtract(provision).setScale(2, RoundingMode.HALF_UP);
        return new CededTotalsResult(
                totalPremiums.setScale(2, RoundingMode.HALF_UP),
                totalPaid.setScale(2, RoundingMode.HALF_UP),
                totalUnpaid.setScale(2, RoundingMode.HALF_UP),
                totalRecoverable.setScale(2, RoundingMode.HALF_UP),
                provision,
                net);
    }

    private static void requireNonNull(BigDecimal amount, String name) {
        if (amount == null) {
            throw new RegulatoryReportGenerationException(name + " required");
        }
    }

    private static void requireNonNullResult(StratumResult stratum, String name) {
        if (stratum == null) {
            throw new RegulatoryReportGenerationException(name + " stratum result required");
        }
    }
}
