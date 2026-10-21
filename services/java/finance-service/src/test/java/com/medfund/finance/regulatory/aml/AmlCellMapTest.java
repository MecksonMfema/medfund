package com.medfund.finance.regulatory.aml;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AmlCellMapTest {

    private final AmlCellMap cellMap = new AmlCellMap();

    @Test
    void keyClass_isAmlField() {
        assertThat(cellMap.keyClass()).isEqualTo(AmlField.class);
    }

    @Test
    void mappings_coverEveryAmlFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<AmlField>> mappings = cellMap.mappings();
        Set<AmlField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<AmlField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added).as("duplicate mapping for %s", m.key()).isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(AmlField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingSyntheticZaTemplate() {
        for (RegulatoryCellMap.Mapping<AmlField> m : cellMap.mappings()) {
            assertThat(m)
                    .as("Phase 25 SYNTHETIC ZA template ships every field as a named range; "
                            + "any LabelAnchor entry means at least one country's real template "
                            + "landed (Phase 26) — review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useAmlPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<AmlField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<AmlField>) m).rangeName();
            assertThat(rangeName).startsWith("AML_");
        }
    }
}
