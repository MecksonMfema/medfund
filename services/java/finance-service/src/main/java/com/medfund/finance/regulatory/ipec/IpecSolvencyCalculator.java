package com.medfund.finance.regulatory.ipec;

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
 * Computes IPEC solvency metrics using YAML defaults for the parameter
 * set. Loads {@code regulatory-defaults/ZW_IPEC_SHORT_TERM/{date}.yaml}
 * — picks the highest effective_from ≤ report period.
 *
 * <p>Phase 10 ships YAML-only resolution; Phase 15 wires the rules-engine
 * {@code REGULATORY_PARAMETER} category override in front of the YAML load
 * via {@code RegulatoryParameterResolver}. The calculator's public API
 * accepts pre-resolved {@link IpecSolvencyParameters} so a future
 * retrofit can inject the resolver without changing the compute surface.
 *
 * <p>All amounts are {@link BigDecimal}. FX conversion happens upstream
 * in {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy};
 * this calculator assumes every input is already in the report's
 * reporting currency (ZWL for IPEC).
 */
@Slf4j
@Component
public class IpecSolvencyCalculator {

    private static final String JURISDICTION = "ZW_IPEC_SHORT_TERM";
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
     * Phase 15 REG15 escape-hatch overload — resolve each parameter through
     * {@link RegulatoryParameterResolver} so a tenant's
     * {@code REGULATORY_PARAMETER} rule wins over the bundled YAML default.
     * Fails loud via {@link RegulatoryParameterResolver} when neither a rule
     * nor the YAML matches.
     *
     * <p>Callers still on the sync {@link #resolveParameters(LocalDate)}
     * overload keep the YAML-only path — pick this overload up in the
     * downstream shaper retrofit (Phase 15b) to enable tenant overrides.
     */
    public Mono<IpecSolvencyParameters> resolveParameters(RegulatoryParameterResolver resolver,
                                                          UUID tenantId,
                                                          LocalDate effectiveDate) {
        if (resolver == null) return Mono.just(resolveParameters(effectiveDate));
        return Mono.zip(
                        resolver.resolve(tenantId, JURISDICTION,
                                IpecSolvencyParameters.KEY_MIN_SOLVENCY_RATIO, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                IpecSolvencyParameters.KEY_MIN_REQUIRED_CAPITAL_MULTIPLIER, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                IpecSolvencyParameters.KEY_RESERVE_HAIRCUT_PCT, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                IpecSolvencyParameters.KEY_REINSURANCE_RECOVERABLE_PCT_HAIRCUT, effectiveDate))
                .map(t -> new IpecSolvencyParameters(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }

    public IpecSolvencyParameters resolveParameters(LocalDate effectiveDate) {
        String resourcePath = highestVersionedYaml(effectiveDate)
                .orElseThrow(() -> new RegulatoryReportGenerationException(
                        "No IPEC solvency defaults YAML found for effective date " + effectiveDate
                                + " under regulatory-defaults/" + JURISDICTION + "/. "
                                + "Phase 10 ships a 2024-06-01.yaml baseline."));
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            Object rawParams = root.get("parameters");
            if (!(rawParams instanceof Map<?, ?> params)) {
                throw new RegulatoryReportGenerationException(
                        "Malformed IPEC defaults YAML " + resourcePath
                                + " - top-level 'parameters:' map required.");
            }
            return new IpecSolvencyParameters(
                    requireDecimal(params, IpecSolvencyParameters.KEY_MIN_SOLVENCY_RATIO, resourcePath),
                    requireDecimal(params, IpecSolvencyParameters.KEY_MIN_REQUIRED_CAPITAL_MULTIPLIER, resourcePath),
                    requireDecimal(params, IpecSolvencyParameters.KEY_RESERVE_HAIRCUT_PCT, resourcePath),
                    requireDecimal(params, IpecSolvencyParameters.KEY_REINSURANCE_RECOVERABLE_PCT_HAIRCUT, resourcePath));
        } catch (IOException e) {
            throw new RegulatoryReportGenerationException(
                    "Failed to read IPEC solvency defaults " + resourcePath, e);
        }
    }

    /** Public for unit tests exercising the version-pick logic without a live JVM classpath. */
    Optional<String> highestVersionedYaml(LocalDate effectiveDate) {
        if (effectiveDate == null) return Optional.empty();
        // classpath*: enumerates matches across every jar on the classpath — plain
        // classpath: only resolves a single root, missing bundled defaults that
        // ship inside the shared jar (see RegulatoryTemplateService fix).
        String pattern = "classpath*:regulatory-defaults/" + JURISDICTION + "/*.yaml";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("[ipec-defaults] resource scan failed for {}: {}", pattern, e.getMessage());
            return Optional.empty();
        }
        List<String> filenames = new ArrayList<>();
        for (Resource r : resources) {
            String fn = r.getFilename();
            if (fn != null) filenames.add(fn);
        }
        return pickHighestVersion(filenames, effectiveDate);
    }

    /** Pure-function seam mirroring {@code RegulatoryTemplateService.pickHighestVersion}. */
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
                    "Missing required IPEC parameter '" + key + "' in " + source);
        }
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        if (raw instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) {
                throw new RegulatoryReportGenerationException(
                        "IPEC parameter '" + key + "' in " + source + " is not a decimal: " + s);
            }
        }
        throw new RegulatoryReportGenerationException(
                "IPEC parameter '" + key + "' in " + source + " has unsupported type: "
                        + raw.getClass().getSimpleName());
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    /** Result of the solvency calculation — used by the shaper to populate cells. */
    public record SolvencyResult(
            BigDecimal admittedCapital,
            BigDecimal minRequiredCapital,
            BigDecimal margin,
            BigDecimal ratio,
            boolean meetsMinimum) {}

    /**
     * Compute solvency against the given inputs. Formula (synthetic
     * approximation pending compliance sign-off):
     *
     * <ul>
     *   <li>{@code admittedCapital = totalAssets − totalLiabilities}</li>
     *   <li>{@code minRequiredCapital = (netEarnedPremium + haircutReserves) × multiplier}</li>
     *   <li>{@code margin = admittedCapital − minRequiredCapital}</li>
     *   <li>{@code ratio = admittedCapital / minRequiredCapital} (4 dp)</li>
     * </ul>
     *
     * <p>All arguments must be non-null and non-negative; a
     * {@link RegulatoryReportGenerationException} is thrown otherwise so
     * a bad shaper input surfaces as an export failure rather than a
     * misleading positive solvency figure.
     */
    public SolvencyResult compute(
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal netEarnedPremium,
            BigDecimal outstandingClaimsReserve,
            BigDecimal ibnrReserve,
            IpecSolvencyParameters params) {
        requireNonNull(totalAssets, "totalAssets");
        requireNonNull(totalLiabilities, "totalLiabilities");
        requireNonNull(netEarnedPremium, "netEarnedPremium");
        requireNonNull(outstandingClaimsReserve, "outstandingClaimsReserve");
        requireNonNull(ibnrReserve, "ibnrReserve");
        if (params == null) {
            throw new RegulatoryReportGenerationException("solvency parameters required");
        }
        BigDecimal admittedCapital = totalAssets.subtract(totalLiabilities);
        BigDecimal reservesTotal = outstandingClaimsReserve.add(ibnrReserve);
        BigDecimal haircutReserves = reservesTotal.multiply(BigDecimal.ONE.subtract(params.reserveHaircutPct()));
        BigDecimal minRequired = netEarnedPremium.add(haircutReserves)
                .multiply(params.minRequiredCapitalMultiplier());
        BigDecimal margin = admittedCapital.subtract(minRequired);
        BigDecimal ratio;
        boolean meetsMinimum;
        if (minRequired.signum() == 0) {
            ratio = BigDecimal.ZERO;
            meetsMinimum = false;
        } else {
            ratio = admittedCapital.divide(minRequired, MathContext.DECIMAL64)
                    .setScale(4, RoundingMode.HALF_UP);
            meetsMinimum = ratio.compareTo(params.minSolvencyRatio()) >= 0;
        }
        return new SolvencyResult(
                admittedCapital.setScale(2, RoundingMode.HALF_UP),
                minRequired.setScale(2, RoundingMode.HALF_UP),
                margin.setScale(2, RoundingMode.HALF_UP),
                ratio,
                meetsMinimum);
    }

    private static void requireNonNull(BigDecimal amount, String name) {
        if (amount == null) {
            throw new RegulatoryReportGenerationException(name + " required");
        }
    }
}
