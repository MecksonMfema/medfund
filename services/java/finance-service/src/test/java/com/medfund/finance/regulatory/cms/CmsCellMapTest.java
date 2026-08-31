package com.medfund.finance.regulatory.cms;

import com.medfund.shared.report.regulatory.RegulatoryCellMap;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CmsCellMapTest {

    private final CmsCellMap cellMap = new CmsCellMap();

    @Test
    void keyClass_isCmsField() {
        assertThat(cellMap.keyClass()).isEqualTo(CmsField.class);
    }

    @Test
    void mappings_coverEveryCmsFieldExactlyOnce() {
        List<RegulatoryCellMap.Mapping<CmsField>> mappings = cellMap.mappings();
        Set<CmsField> covered = new HashSet<>();
        for (RegulatoryCellMap.Mapping<CmsField> m : mappings) {
            boolean added = covered.add(m.key());
            assertThat(added)
                    .as("duplicate mapping for %s", m.key())
                    .isTrue();
        }
        assertThat(covered).isEqualTo(EnumSet.allOf(CmsField.class));
    }

    @Test
    void mappings_areAllNamedRanges_matchingBundledSyntheticTemplate() {
        List<RegulatoryCellMap.Mapping<CmsField>> mappings = cellMap.mappings();
        for (RegulatoryCellMap.Mapping<CmsField> m : mappings) {
            assertThat(m)
                    .as("Phase 11 SYNTHETIC template ships every field as a named range; "
                            + "any LabelAnchor entry means the real CMS template landed — "
                            + "review this test then.")
                    .isInstanceOf(RegulatoryCellMap.NamedRange.class);
        }
    }

    @Test
    void mappings_useCmsAsrPrefix_conventionEnforced() {
        for (RegulatoryCellMap.Mapping<CmsField> m : cellMap.mappings()) {
            String rangeName = ((RegulatoryCellMap.NamedRange<CmsField>) m).rangeName();
            assertThat(rangeName).startsWith("CMS_ASR_");
        }
    }
}
