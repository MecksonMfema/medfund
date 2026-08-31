package com.medfund.finance.regulatory.naic;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NaicPCellMapTest {

    private final NaicPCellMap cellMap = new NaicPCellMap();

    @Test
    void keyClass_isNaicPField() {
        assertThat(cellMap.keyClass()).isEqualTo(NaicPField.class);
    }

    @Test
    void mappings_coverEveryNaicPFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<NaicPField>> mappings = cellMap.mappings();
        Set<NaicPField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<NaicPField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added)
                    .as("duplicate mapping for %s", m.key())
                    .isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(NaicPField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBundledSyntheticTemplate() {
        List<RegulatoryCellMap.Mapping<NaicPField>> mappings = cellMap.mappings();
        for (RegulatoryCellMap.Mapping<NaicPField> m : mappings) {
            assertThat(m)
                    .as("Phase 12 SYNTHETIC template ships every field as a named range; "
                            + "any LabelAnchor entry means the real NAIC template landed — "
                            + "review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useNaicPPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<NaicPField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<NaicPField>) m).rangeName();
            assertThat(rangeName).startsWith("NAIC_P_");
        }
    }
}
