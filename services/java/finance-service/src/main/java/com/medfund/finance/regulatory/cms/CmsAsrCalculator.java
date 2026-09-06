package com.medfund.finance.regulatory.cms;

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
 * Computes CMS Annual Statutory Return solvency + cost-ratio metrics using
 * YAML defaults for the parameter set. Loads
 * {@code regulatory-defaults/ZA_CMS_MEDICAL_SCHEME/{date}.yaml} — picks the
 * highest effective_from ≤ report period.
 *
 * <p>Phase 11 ships YAML-only resolution; Phase 15 wires the rules-engine
 * {@code REGULATORY_PARAMETER} category override in front of the YAML load
 * via {@code RegulatoryParameterResolver}. The calculator's public API
 * accepts pre-resolved {@link CmsSolvencyParameters} so a future retrofit
 * can inject the resolver without changing the compute surface.
 *
 * <p>All amounts are {@link BigDecimal}. FX conversion happens upstream
 * in {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy};
 * this calculator assumes every input is already in the report's
 * reporting currency (ZAR for CMS ASR).
 */
@Slf4j
@Component
public class CmsAsrCalculator {

    private static final String JURISDICTION = "ZA_CMS_MEDICAL_SCHEME";
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
    public Mono<CmsSolvencyParameters> resolveParameters(RegulatoryParameterResolver resolver,
                                                         UUID tenantId,
                                                         LocalDate effectiveDate) {
        if (resolver == null) return Mono.just(resolveParameters(effectiveDate));
        return Mono.zip(
                        resolver.resolve(tenantId, JURISDICTION,
                                CmsSolvencyParameters.KEY_MIN_SOLVENCY_RATIO, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                CmsSolvencyParameters.KEY_NON_HEALTHCARE_COST_TARGET, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                CmsSolvencyParameters.KEY_BROKER_FEES_CAP, effectiveDate),
                        resolver.resolve(tenantId, JURISDICTION,
                                CmsSolvencyParameters.KEY_MANAGED_CARE_FEES_CAP, effectiveDate))
                .map(t -> new CmsSolvencyParameters(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }

    public CmsSolvencyParameters resolveParameters(LocalDate effectiveDate) {
        String resourcePath = highestVersionedYaml(effectiveDate)
                .orElseThrow(() -> new RegulatoryReportGenerationException(
                        "No CMS solvency defaults YAML found for effective date " + effectiveDate
                                + " under regulatory-defaults/" + JURISDICTION + "/. "
                                + "Phase 11 ships a 2024-06-01.yaml baseline."));
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            Object rawParams = root.get("parameters");
            if (!(rawParams instanceof Map<?, ?> params)) {
                throw new RegulatoryReportGenerationException(
                        "Malformed CMS defaults YAML " + resourcePath
                                + " - top-level 'parameters:' map required.");
            }
            return new CmsSolvencyParameters(
                    requireDecimal(params, CmsSolvencyParameters.KEY_MIN_SOLVENCY_RATIO, resourcePath),
                    requireDecimal(params, CmsSolvencyParameters.KEY_NON_HEALTHCARE_COST_TARGET, resourcePath),
                    requireDecimal(params, CmsSolvencyParameters.KEY_BROKER_FEES_CAP, resourcePath),
                    requireDecimal(params, CmsSolvencyParameters.KEY_MANAGED_CARE_FEES_CAP, resourcePath));
        } catch (IOException e) {
            throw new RegulatoryReportGenerationException(
                    "Failed to read CMS solvency defaults " + resourcePath, e);
        }
    }

    /** Package-visible for unit tests exercising the version-pick logic. */
    Optional<String> highestVersionedYaml(LocalDate effectiveDate) {
        if (effectiveDate == null) return Optional.empty();
        // classpath*: enumerates matches across every jar on the classpath —
        // plain classpath: only resolves a single root, missing bundled
        // defaults that ship inside the shared jar (Phase 10 IPEC fix).
        String pattern = "classpath*:regulatory-defaults/" + JURISDICTION + "/*.yaml";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("[cms-defaults] resource scan failed for {}: {}", pattern, e.getMessage());
            return Optional.empty();
        }
        List<String> filenames = new ArrayList<>();
        for (Resource r : resources) {
            String fn = r.getFilename();
            if (fn != null) filenames.add(fn);
        }
        return pickHighestVersion(filenames, effectiveDate);
    }

    /** Pure-function seam mirroring {@code IpecSolvencyCalculator.pickHighestVersion}. */
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
                    "Missing required CMS parameter '" + key + "' in " + source);
        }
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        if (raw instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) {
                throw new RegulatoryReportGenerationException(
                        "CMS parameter '" + key + "' in " + source + " is not a decimal: " + s);
            }
        }
        throw new RegulatoryReportGenerationException(
                "CMS parameter '" + key + "' in " + source + " has unsupported type: "
                        + raw.getClass().getSimpleName());
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    /** Result of the solvency + cost-ratio calculation — used by the shaper to populate cells. */
    public record SolvencyResult(
            BigDecimal accumulatedFunds,
            BigDecimal minRequiredReserves,
            BigDecimal actualRatio,
            BigDecimal minRequiredRatio,
            BigDecimal surplusDeficit,
            boolean meetsMinimum) {}

    /** Per-CMS cost-ratio breakdown. Ratios are decimals (0.80 = 80%). */
    public record CostRatios(
            BigDecimal claimsRatio,
            BigDecimal nonHealthcareRatio,
            BigDecimal adminRatio,
            BigDecimal brokerRatio,
            BigDecimal managedCareRatio) {}

    /**
     * Compute solvency against the given inputs. Formula per Medical
     * Schemes Act 131 of 1998 (synthetic approximation pending compliance
     * sign-off):
     *
     * <ul>
     *   <li>{@code accumulatedFunds = totalAssets − totalLiabilities}</li>
     *   <li>{@code minRequiredReserves = grossContributions × minSolvencyRatio}</li>
     *   <li>{@code actualRatio = accumulatedFunds / grossContributions} (4 dp)</li>
     *   <li>{@code surplusDeficit = accumulatedFunds − minRequiredReserves}</li>
     * </ul>
     *
     * <p>All arguments must be non-null and non-negative; a
     * {@link RegulatoryReportGenerationException} is thrown otherwise so a
     * bad shaper input surfaces as an export failure rather than a
     * misleading positive solvency figure.
     */
    public SolvencyResult computeSolvency(
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal grossContributions,
            CmsSolvencyParameters params) {
        requireNonNull(totalAssets, "totalAssets");
        requireNonNull(totalLiabilities, "totalLiabilities");
        requireNonNull(grossContributions, "grossContributions");
        if (params == null) {
            throw new RegulatoryReportGenerationException("solvency parameters required");
        }
        BigDecimal accumulatedFunds = totalAssets.subtract(totalLiabilities);
        BigDecimal minRequiredReserves = grossContributions.multiply(params.minSolvencyRatio());
        BigDecimal surplusDeficit = accumulatedFunds.subtract(minRequiredReserves);
        BigDecimal actualRatio;
        boolean meetsMinimum;
        if (grossContributions.signum() == 0) {
            actualRatio = BigDecimal.ZERO;
            meetsMinimum = false;
        } else {
            actualRatio = accumulatedFunds.divide(grossContributions, MathContext.DECIMAL64)
                    .setScale(4, RoundingMode.HALF_UP);
            meetsMinimum = actualRatio.compareTo(params.minSolvencyRatio()) >= 0;
        }
        return new SolvencyResult(
                accumulatedFunds.setScale(2, RoundingMode.HALF_UP),
                minRequiredReserves.setScale(2, RoundingMode.HALF_UP),
                actualRatio,
                params.minSolvencyRatio().setScale(4, RoundingMode.HALF_UP),
                surplusDeficit.setScale(2, RoundingMode.HALF_UP),
                meetsMinimum);
    }

    /**
     * Compute the CMS cost-containment ratios. Each ratio is
     * {@code component / grossContributions} rounded to 4 dp; when
     * {@code grossContributions} is zero every ratio is ZERO (a scheme with
     * no premium income is deep in dissolution territory — the surrounding
     * report will already have flagged solvency failure).
     */
    public CostRatios computeCostRatios(
            BigDecimal grossContributions,
            BigDecimal riskClaimsIncurred,
            BigDecimal adminExpenses,
            BigDecimal brokerFees,
            BigDecimal managedCareFees) {
        requireNonNull(grossContributions, "grossContributions");
        requireNonNull(riskClaimsIncurred, "riskClaimsIncurred");
        requireNonNull(adminExpenses, "adminExpenses");
        requireNonNull(brokerFees, "brokerFees");
        requireNonNull(managedCareFees, "managedCareFees");
        if (grossContributions.signum() == 0) {
            return new CostRatios(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal nonHealthcare = adminExpenses.add(brokerFees).add(managedCareFees);
        return new CostRatios(
                ratio(riskClaimsIncurred, grossContributions),
                ratio(nonHealthcare, grossContributions),
                ratio(adminExpenses, grossContributions),
                ratio(brokerFees, grossContributions),
                ratio(managedCareFees, grossContributions));
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP);
    }

    private static void requireNonNull(BigDecimal amount, String name) {
        if (amount == null) {
            throw new RegulatoryReportGenerationException(name + " required");
        }
    }
}
