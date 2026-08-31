package com.medfund.finance.regulatory.ipec;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IpecCellMapTest {

    private final IpecCellMap cellMap = new IpecCellMap();

    @Test
    void keyClass_isIpecField() {
        assertThat(cellMap.keyClass()).isEqualTo(IpecField.class);
    }

    @Test
    void mappings_coverEveryIpecFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<IpecField>> mappings = cellMap.mappings();
        Set<IpecField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<IpecField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added)
                    .as("duplicate mapping for %s", m.key())
                    .isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(IpecField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBundledSyntheticTemplate() {
        List<RegulatoryCellMap.Mapping<IpecField>> mappings = cellMap.mappings();
        for (RegulatoryCellMap.Mapping<IpecField> m : mappings) {
            assertThat(m)
                    .as("Phase 10 SYNTHETIC template ships every field as a named range; "
                            + "any LabelAnchor entry means the real IPEC template landed — "
                            + "review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useIpecQPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<IpecField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<IpecField>) m).rangeName();
            assertThat(rangeName).startsWith("IPEC_Q_");
        }
    }
}
