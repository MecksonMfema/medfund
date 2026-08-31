package com.medfund.finance.regulatory.naic;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NaicFCellMapTest {

    private final NaicFCellMap cellMap = new NaicFCellMap();

    @Test
    void keyClass_isNaicFField() {
        assertThat(cellMap.keyClass()).isEqualTo(NaicFField.class);
    }

    @Test
    void mappings_coverEveryNaicFFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<NaicFField>> mappings = cellMap.mappings();
        Set<NaicFField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<NaicFField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added)
                    .as("duplicate mapping for %s", m.key())
                    .isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(NaicFField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBundledSyntheticTemplate() {
        List<RegulatoryCellMap.Mapping<NaicFField>> mappings = cellMap.mappings();
        for (RegulatoryCellMap.Mapping<NaicFField> m : mappings) {
            assertThat(m)
                    .as("Phase 13 SYNTHETIC template ships every field as a named range; "
                            + "any LabelAnchor entry means the real NAIC template landed — "
                            + "review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useNaicFPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<NaicFField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<NaicFField>) m).rangeName();
            assertThat(rangeName).startsWith("NAIC_F_");
        }
    }
}
