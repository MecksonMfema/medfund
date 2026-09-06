package com.medfund.shared.report.regulatory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundled regulator YAML defaults loader — the shared half of the Phase 16
 * §Phase-15 REG15 rules-engine escape hatch. Loads
 * {@code regulatory-defaults/{jurisdiction}/{effective_from}.yaml} from the
 * classpath and returns the {@code parameters:} map, picking the highest
 * {@code effective_from} ≤ the requested period.
 *
 * <p>Every regulator YAML follows the same shape:
 *
 * <pre>
 * jurisdiction: ZW_IPEC_SHORT_TERM
 * effective_from: 2024-06-01
 * parameters:
 *   min_solvency_ratio: 1.30
 *   min_required_capital_multiplier: 0.30
 *   ...
 * </pre>
 *
 * <p>Callers:
 * <ul>
 *   <li>{@code RegulatoryParameterResolver} (finance-service) — falls back
 *       here when no rules-engine {@code REGULATORY_PARAMETER} rule fires.</li>
 *   <li>Per-regulator calculators
 *       ({@code IpecSolvencyCalculator}, {@code CmsAsrCalculator},
 *       {@code NaicSchedulePCalculator}, {@code NaicScheduleFCalculator}) —
 *       consume this to build their typed parameters record after the
 *       resolver has produced any rules-engine overrides.</li>
 * </ul>
 *
 * <p>Missing YAML file for a jurisdiction returns {@code Optional.empty()};
 * a malformed file throws {@link RegulatoryReportGenerationException} so
 * an authoring bug surfaces as an export failure rather than a silent zero
 * on a live report.
 */
@Slf4j
@Component
public class RegulatoryDefaultsLoader {

    private static final Pattern VERSION_FILENAME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\.yaml$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver(getClass().getClassLoader());

    /**
     * Load the full {@code parameters:} map for the highest bundled YAML
     * whose {@code effective_from} ≤ the requested date. Returns
     * {@link Optional#empty()} when no YAML matches the jurisdiction.
     *
     * @throws RegulatoryReportGenerationException when the matched file has
     *     a malformed {@code parameters:} block or a value that isn't a
     *     decimal.
     */
    public Optional<Map<String, BigDecimal>> loadParameters(String jurisdiction, LocalDate effectiveDate) {
        Optional<String> resource = resolveHighestVersionedResource(jurisdiction, effectiveDate);
        if (resource.isEmpty()) return Optional.empty();
        String resourcePath = resource.get();
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(in, Map.class);
            Object rawParams = root.get("parameters");
            if (!(rawParams instanceof Map<?, ?> params)) {
                throw new RegulatoryReportGenerationException(
                        "Malformed regulatory defaults YAML " + resourcePath
                                + " - top-level 'parameters:' map required.");
            }
            java.util.LinkedHashMap<String, BigDecimal> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : params.entrySet()) {
                String key = String.valueOf(entry.getKey());
                out.put(key, requireDecimal(entry.getValue(), key, resourcePath));
            }
            return Optional.of(Collections.unmodifiableMap(out));
        } catch (IOException e) {
            throw new RegulatoryReportGenerationException(
                    "Failed to read regulatory defaults " + resourcePath, e);
        }
    }

    /**
     * Convenience — resolve a single parameter from the highest bundled
     * YAML for the jurisdiction. Returns {@link Optional#empty()} when the
     * jurisdiction has no YAML or the YAML has no such key.
     */
    public Optional<BigDecimal> lookup(String jurisdiction, String parameterKey, LocalDate effectiveDate) {
        if (parameterKey == null || parameterKey.isBlank()) return Optional.empty();
        return loadParameters(jurisdiction, effectiveDate)
                .map(params -> params.get(parameterKey));
    }

    /**
     * Enumerate every {@code regulatory-defaults/{jurisdiction}/*.yaml} on
     * the classpath and return the resource path of the highest-versioned
     * file whose {@code effective_from} ≤ the requested date. Uses
     * {@code classpath*:} so files bundled inside the shared jar are
     * discovered from every downstream service.
     */
    public Optional<String> resolveHighestVersionedResource(String jurisdiction, LocalDate effectiveDate) {
        if (jurisdiction == null || jurisdiction.isBlank() || effectiveDate == null) {
            return Optional.empty();
        }
        String pattern = "classpath*:regulatory-defaults/" + jurisdiction + "/*.yaml";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("[regulatory-defaults] resource scan failed for {}: {}", pattern, e.getMessage());
            return Optional.empty();
        }
        List<String> filenames = new ArrayList<>();
        for (Resource r : resources) {
            String fn = r.getFilename();
            if (fn != null) filenames.add(fn);
        }
        return pickHighestVersion(jurisdiction, filenames, effectiveDate);
    }

    /**
     * Pure-function seam mirroring
     * {@code RegulatoryTemplateService.pickHighestVersion} — exposed so
     * calculator unit tests can exercise the version-pick logic without
     * touching the classpath.
     */
    public static Optional<String> pickHighestVersion(String jurisdiction, List<String> filenames,
                                                     LocalDate effectiveDate) {
        if (jurisdiction == null || jurisdiction.isBlank()
                || effectiveDate == null || filenames == null) return Optional.empty();
        LocalDate best = null;
        String bestPath = null;
        for (String filename : filenames) {
            Matcher m = VERSION_FILENAME.matcher(filename);
            if (!m.find()) continue;
            LocalDate v;
            try {
                v = LocalDate.parse(m.group(1));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (v.isAfter(effectiveDate)) continue;
            if (best == null || v.isAfter(best)) {
                best = v;
                bestPath = "regulatory-defaults/" + jurisdiction + "/" + filename;
            }
        }
        return Optional.ofNullable(bestPath);
    }

    private static BigDecimal requireDecimal(Object raw, String key, String source) {
        if (raw == null) {
            throw new RegulatoryReportGenerationException(
                    "Missing required regulatory parameter '" + key + "' in " + source);
        }
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        if (raw instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) {
                throw new RegulatoryReportGenerationException(
                        "Regulatory parameter '" + key + "' in " + source + " is not a decimal: " + s);
            }
        }
        throw new RegulatoryReportGenerationException(
                "Regulatory parameter '" + key + "' in " + source + " has unsupported type: "
                        + raw.getClass().getSimpleName());
    }
}
