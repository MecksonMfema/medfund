package com.medfund.finance.regulatory.tax.vat;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VatCellMapTest {

    private final VatCellMap cellMap = new VatCellMap();

    @Test
    void keyClass_isVatField() {
        assertThat(cellMap.keyClass()).isEqualTo(VatField.class);
    }

    @Test
    void mappings_coverEveryVatFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<VatField>> mappings = cellMap.mappings();
        Set<VatField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<VatField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added).as("duplicate mapping for %s", m.key()).isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(VatField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBothSyntheticTemplates() {
        for (RegulatoryCellMap.Mapping<VatField> m : cellMap.mappings()) {
            assertThat(m)
                    .as("Phase 20 SYNTHETIC ZW + ZA templates ship every field as a named range; "
                            + "any LabelAnchor entry means at least one country's real template "
                            + "landed — review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useVatPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<VatField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<VatField>) m).rangeName();
            assertThat(rangeName).startsWith("VAT_");
        }
    }
}
