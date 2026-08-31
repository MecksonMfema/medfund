package com.medfund.finance.regulatory.pmb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PmbCategoryTest {

    @Test
    void forCode_respiratoryRange_001_through_019_mapsToRESPIRATORY() {
        assertThat(PmbCategory.forCode("PMB-001")).isEqualTo(PmbCategory.RESPIRATORY);
        assertThat(PmbCategory.forCode("PMB-013")).isEqualTo(PmbCategory.RESPIRATORY);
        assertThat(PmbCategory.forCode("PMB-014")).isEqualTo(PmbCategory.RESPIRATORY);
        assertThat(PmbCategory.forCode("PMB-019")).isEqualTo(PmbCategory.RESPIRATORY);
    }

    @Test
    void forCode_cardiacRange_020_through_029_mapsToCARDIAC() {
        assertThat(PmbCategory.forCode("PMB-020")).isEqualTo(PmbCategory.CARDIAC);
        assertThat(PmbCategory.forCode("PMB-021")).isEqualTo(PmbCategory.CARDIAC);
        assertThat(PmbCategory.forCode("PMB-022")).isEqualTo(PmbCategory.CARDIAC);
    }

    @Test
    void forCode_metabolicRange_030_through_039_mapsToMETABOLIC() {
        assertThat(PmbCategory.forCode("PMB-030")).isEqualTo(PmbCategory.METABOLIC);
        assertThat(PmbCategory.forCode("PMB-031")).isEqualTo(PmbCategory.METABOLIC);
    }

    @Test
    void forCode_oncologyRange_040_through_049_mapsToONCOLOGY() {
        assertThat(PmbCategory.forCode("PMB-040")).isEqualTo(PmbCategory.ONCOLOGY);
        assertThat(PmbCategory.forCode("PMB-041")).isEqualTo(PmbCategory.ONCOLOGY);
        assertThat(PmbCategory.forCode("PMB-042")).isEqualTo(PmbCategory.ONCOLOGY);
    }

    @Test
    void forCode_mentalHealthRange_050_through_059_mapsToMENTAL_HEALTH() {
        assertThat(PmbCategory.forCode("PMB-050")).isEqualTo(PmbCategory.MENTAL_HEALTH);
        assertThat(PmbCategory.forCode("PMB-051")).isEqualTo(PmbCategory.MENTAL_HEALTH);
        assertThat(PmbCategory.forCode("PMB-052")).isEqualTo(PmbCategory.MENTAL_HEALTH);
    }

    @Test
    void forCode_renalRange_060_through_069_mapsToRENAL() {
        assertThat(PmbCategory.forCode("PMB-060")).isEqualTo(PmbCategory.RENAL);
        assertThat(PmbCategory.forCode("PMB-069")).isEqualTo(PmbCategory.RENAL);
    }

    @Test
    void forCode_outOfKnownRanges_mapsToOTHER() {
        assertThat(PmbCategory.forCode("PMB-070")).isEqualTo(PmbCategory.OTHER);
        assertThat(PmbCategory.forCode("PMB-100")).isEqualTo(PmbCategory.OTHER);
        assertThat(PmbCategory.forCode("PMB-999")).isEqualTo(PmbCategory.OTHER);
    }

    @Test
    void forCode_malformedOrNull_yieldsOTHER() {
        assertThat(PmbCategory.forCode(null)).isEqualTo(PmbCategory.OTHER);
        assertThat(PmbCategory.forCode("")).isEqualTo(PmbCategory.OTHER);
        assertThat(PmbCategory.forCode("   ")).isEqualTo(PmbCategory.OTHER);
        assertThat(PmbCategory.forCode("NOT-A-CODE")).isEqualTo(PmbCategory.OTHER);
        assertThat(PmbCategory.forCode("PMB-ABC")).isEqualTo(PmbCategory.OTHER);
    }

    @Test
    void forCode_bareNumericTail_stillClassifies() {
        // Some historical rows might carry raw numeric codes without the PMB- prefix.
        assertThat(PmbCategory.forCode("021")).isEqualTo(PmbCategory.CARDIAC);
        assertThat(PmbCategory.forCode("013")).isEqualTo(PmbCategory.RESPIRATORY);
    }
}
