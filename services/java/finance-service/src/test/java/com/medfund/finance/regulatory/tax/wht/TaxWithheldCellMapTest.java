package com.medfund.finance.regulatory.tax.wht;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TaxWithheldCellMapTest {

    private final TaxWithheldCellMap cellMap = new TaxWithheldCellMap();

    @Test
    void keyClass_isTaxWithheldField() {
        assertThat(cellMap.keyClass()).isEqualTo(TaxWithheldField.class);
    }

    @Test
    void mappings_coverEveryFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<TaxWithheldField>> mappings = cellMap.mappings();
        Set<TaxWithheldField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<TaxWithheldField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added).as("duplicate mapping for %s", m.key()).isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(TaxWithheldField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBothSyntheticTemplates() {
        for (RegulatoryCellMap.Mapping<TaxWithheldField> m : cellMap.mappings()) {
            assertThat(m)
                    .as("Phase 21 SYNTHETIC ZW + ZA templates ship every field as a named range")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useWhtPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<TaxWithheldField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<TaxWithheldField>) m).rangeName();
            assertThat(rangeName).startsWith("WHT_");
        }
    }
}
