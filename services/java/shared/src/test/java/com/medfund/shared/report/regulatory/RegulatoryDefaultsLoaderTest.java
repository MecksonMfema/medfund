package com.medfund.shared.report.regulatory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the pure-function version-pick seam + the classpath-backed
 * {@code loadParameters} / {@code lookup} paths using the real bundled
 * YAMLs shipped for Phase 10-13 (ZW_IPEC_SHORT_TERM, ZA_CMS_MEDICAL_SCHEME,
 * US_NAIC). Missing keys / missing jurisdictions / null inputs all short
 * to {@link Optional#empty()}; malformed YAML would throw
 * {@link RegulatoryReportGenerationException} (covered by
 * {@code IpecSolvencyCalculatorTest} — the shared loader shares the same
 * validation path).
 */
class RegulatoryDefaultsLoaderTest {

    private final RegulatoryDefaultsLoader loader = new RegulatoryDefaultsLoader();

    // ── Version resolution ─────────────────────────────────────────────────

    @Test
    void pickHighestVersion_picksHighestDateLeEffective() {
        List<String> files = List.of("2024-01-01.yaml", "2024-06-01.yaml", "2025-03-01.yaml");

        Optional<String> pick = RegulatoryDefaultsLoader.pickHighestVersion(
                "ZW_IPEC_SHORT_TERM", files, LocalDate.of(2024, 12, 31));

        assertThat(pick).contains("regulatory-defaults/ZW_IPEC_SHORT_TERM/2024-06-01.yaml");
    }

    @Test
    void pickHighestVersion_ignoresFutureVersions() {
        List<String> files = List.of("2024-01-01.yaml", "2027-03-01.yaml");

        Optional<String> pick = RegulatoryDefaultsLoader.pickHighestVersion(
                "US_NAIC", files, LocalDate.of(2026, 6, 30));

        assertThat(pick).contains("regulatory-defaults/US_NAIC/2024-01-01.yaml");
    }

    @Test
    void pickHighestVersion_emptyWhenNothingMatchesEffectiveDate() {
        Optional<String> pick = RegulatoryDefaultsLoader.pickHighestVersion(
                "US_NAIC", List.of("2030-01-01.yaml"), LocalDate.of(2026, 1, 1));

        assertThat(pick).isEmpty();
    }

    @Test
    void pickHighestVersion_skipsMalformedFilenames() {
        List<String> files = List.of("bad-date.yaml", "notes.yaml", "2024-06-01.yaml");

        Optional<String> pick = RegulatoryDefaultsLoader.pickHighestVersion(
                "ZW_IPEC_SHORT_TERM", files, LocalDate.of(2026, 1, 1));

        assertThat(pick).contains("regulatory-defaults/ZW_IPEC_SHORT_TERM/2024-06-01.yaml");
    }

    @Test
    void pickHighestVersion_nullInputs_returnEmpty() {
        assertThat(RegulatoryDefaultsLoader.pickHighestVersion(null, List.of("2024-06-01.yaml"),
                LocalDate.of(2026, 1, 1))).isEmpty();
        assertThat(RegulatoryDefaultsLoader.pickHighestVersion("ZW_IPEC_SHORT_TERM", null,
                LocalDate.of(2026, 1, 1))).isEmpty();
        assertThat(RegulatoryDefaultsLoader.pickHighestVersion("ZW_IPEC_SHORT_TERM",
                List.of("2024-06-01.yaml"), null)).isEmpty();
    }

    // ── Classpath resolution ───────────────────────────────────────────────

    @Test
    void resolveHighestVersionedResource_findsBundledIpecYaml() {
        // Phase 10 ships regulatory-defaults/ZW_IPEC_SHORT_TERM/2024-06-01.yaml.
        Optional<String> path = loader.resolveHighestVersionedResource(
                "ZW_IPEC_SHORT_TERM", LocalDate.of(2026, 6, 30));

        assertThat(path).contains("regulatory-defaults/ZW_IPEC_SHORT_TERM/2024-06-01.yaml");
    }

    @Test
    void resolveHighestVersionedResource_unknownJurisdiction_returnsEmpty() {
        Optional<String> path = loader.resolveHighestVersionedResource(
                "ATLANTIS_XYZ", LocalDate.of(2026, 6, 30));

        assertThat(path).isEmpty();
    }

    @Test
    void resolveHighestVersionedResource_blankJurisdiction_returnsEmpty() {
        assertThat(loader.resolveHighestVersionedResource("", LocalDate.of(2026, 6, 30))).isEmpty();
        assertThat(loader.resolveHighestVersionedResource(null, LocalDate.of(2026, 6, 30))).isEmpty();
    }

    // ── loadParameters ─────────────────────────────────────────────────────

    @Test
    void loadParameters_ipecYaml_returnsEveryDecimalKey() {
        Optional<Map<String, BigDecimal>> params = loader.loadParameters(
                "ZW_IPEC_SHORT_TERM", LocalDate.of(2026, 6, 30));

        assertThat(params).isPresent();
        Map<String, BigDecimal> map = params.orElseThrow();
        assertThat(map).containsKeys(
                "min_solvency_ratio",
                "min_required_capital_multiplier",
                "reserve_haircut_pct",
                "reinsurance_recoverable_pct_haircut");
        assertThat(map.get("min_solvency_ratio")).isEqualByComparingTo("1.30");
        assertThat(map.get("min_required_capital_multiplier")).isEqualByComparingTo("0.30");
    }

    @Test
    void loadParameters_cmsYaml_returnsEveryDecimalKey() {
        Optional<Map<String, BigDecimal>> params = loader.loadParameters(
                "ZA_CMS_MEDICAL_SCHEME", LocalDate.of(2026, 6, 30));

        assertThat(params).isPresent();
        Map<String, BigDecimal> map = params.orElseThrow();
        assertThat(map.get("min_solvency_ratio")).isEqualByComparingTo("0.25");
        assertThat(map.get("non_healthcare_cost_target")).isEqualByComparingTo("0.10");
    }

    @Test
    void loadParameters_naicYaml_returnsEveryDecimalKey() {
        Optional<Map<String, BigDecimal>> params = loader.loadParameters(
                "US_NAIC", LocalDate.of(2026, 6, 30));

        assertThat(params).isPresent();
        Map<String, BigDecimal> map = params.orElseThrow();
        assertThat(map.get("unauthorized_reinsurer_provision_percentage")).isEqualByComparingTo("1.00");
        assertThat(map.get("certified_reinsurer_provision_percentage")).isEqualByComparingTo("0.20");
    }

    @Test
    void loadParameters_returnedMapIsUnmodifiable() {
        Map<String, BigDecimal> map = loader.loadParameters(
                "ZW_IPEC_SHORT_TERM", LocalDate.of(2026, 6, 30)).orElseThrow();

        assertThatThrownByAtLeast(() -> map.put("extra", BigDecimal.ONE));
    }

    private static void assertThatThrownByAtLeast(Runnable r) {
        try {
            r.run();
            org.assertj.core.api.Assertions.fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
    }

    @Test
    void loadParameters_unknownJurisdiction_returnsEmpty() {
        assertThat(loader.loadParameters("ATLANTIS_XYZ", LocalDate.of(2026, 6, 30))).isEmpty();
    }

    // ── lookup ─────────────────────────────────────────────────────────────

    @Test
    void lookup_ipecSolvencyRatio_returnsExpectedValue() {
        Optional<BigDecimal> value = loader.lookup(
                "ZW_IPEC_SHORT_TERM", "min_solvency_ratio", LocalDate.of(2026, 6, 30));

        assertThat(value).isPresent();
        assertThat(value.orElseThrow()).isEqualByComparingTo("1.30");
    }

    @Test
    void lookup_unknownKey_returnsEmpty() {
        Optional<BigDecimal> value = loader.lookup(
                "ZW_IPEC_SHORT_TERM", "not_a_real_parameter", LocalDate.of(2026, 6, 30));

        assertThat(value).isEmpty();
    }

    @Test
    void lookup_blankKey_returnsEmpty() {
        assertThat(loader.lookup("ZW_IPEC_SHORT_TERM", "", LocalDate.of(2026, 6, 30))).isEmpty();
        assertThat(loader.lookup("ZW_IPEC_SHORT_TERM", null, LocalDate.of(2026, 6, 30))).isEmpty();
    }
}
