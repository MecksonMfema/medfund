package com.medfund.finance.regulatory.aml;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Phase 26 per-STR cell map's shape: every {@link AmlStrField}
 * is mapped exactly once, all mappings use named ranges (no
 * {@code LabelAnchor} fallbacks since we own the synthetic templates), and
 * every range name carries the {@code AMLSTR_} prefix so cross-template
 * collisions with the periodic-summary map are impossible.
 */
class AmlStrFilingCellMapTest {

    private final AmlStrFilingCellMap cellMap = new AmlStrFilingCellMap();

    @Test
    void everyEnumValue_isMappedExactlyOnce() {
        Set<AmlStrField> mapped = cellMap.mappings().stream()
                .map(RegulatoryCellMap.Mapping::key)
                .collect(Collectors.toSet());
        assertThat(mapped).containsExactlyInAnyOrder(AmlStrField.values());
    }

    @Test
    void everyMapping_isANamedRangeNotAnAnchor() {
        assertThat(cellMap.mappings()).allSatisfy(m ->
                assertThat(m).isInstanceOf(RegulatoryCellMap.NamedRange.class));
    }

    @Test
    void everyNamedRange_carriesTheAmlstrPrefix() {
        assertThat(cellMap.mappings()).allSatisfy(m -> {
            RegulatoryCellMap.NamedRange<?> nr = (RegulatoryCellMap.NamedRange<?>) m;
            assertThat(nr.rangeName()).startsWith("AMLSTR_");
        });
    }

    @Test
    void keyClass_isAmlStrField() {
        assertThat(cellMap.keyClass()).isEqualTo(AmlStrField.class);
    }
}
