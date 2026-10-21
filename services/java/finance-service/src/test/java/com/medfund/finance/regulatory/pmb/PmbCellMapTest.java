package com.medfund.finance.regulatory.pmb;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PmbCellMapTest {

    private final PmbCellMap cellMap = new PmbCellMap();

    @Test
    void keyClass_isPmbField() {
        assertThat(cellMap.keyClass()).isEqualTo(PmbField.class);
    }

    @Test
    void mappings_coverEveryPmbFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<PmbField>> mappings = cellMap.mappings();
        Set<PmbField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<PmbField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added)
                    .as("duplicate mapping for %s", m.key())
                    .isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(PmbField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBundledSyntheticTemplate() {
        List<RegulatoryCellMap.Mapping<PmbField>> mappings = cellMap.mappings();
        for (RegulatoryCellMap.Mapping<PmbField> m : mappings) {
            assertThat(m)
                    .as("Phase 18 SYNTHETIC template ships every field as a named range; "
                            + "any LabelAnchor entry means a real PMB template landed — "
                            + "review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_usePmbPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<PmbField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<PmbField>) m).rangeName();
            assertThat(rangeName).startsWith("PMB_");
        }
    }
}
