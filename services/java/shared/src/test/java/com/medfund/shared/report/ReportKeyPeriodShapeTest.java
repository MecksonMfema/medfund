package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportKeyPeriodShapeTest {

    @Test
    void everyCadencedKeyCarriesNonNullPeriodShape() {
        Stream.of(ReportKey.values())
                .filter(ReportKey::isCadenced)
                .forEach(k -> assertThat(k.getPeriodShape())
                        .as("Cadenced key %s must declare a periodShape", k.name())
                        .isNotNull());
    }

    @Test
    void everyNonCadencedKeyHasNullPeriodShape() {
        Stream.of(ReportKey.values())
                .filter(k -> !k.isCadenced())
                .forEach(k -> assertThat(k.getPeriodShape())
                        .as("Non-cadenced key %s must not declare a periodShape", k.name())
                        .isNull());
    }

    @Test
    void snapshotShapedKeysMatchPhase17Mapping() {
        // AS_OF_FIRE_TIME snapshot pair per Phase 17 §S5.
        assertThat(ReportKey.AGED_DEBTORS.getPeriodShape())
                .isEqualTo(ReportPeriodShape.AS_OF_FIRE_TIME);
        assertThat(ReportKey.CASH_FLOW_FORECAST_13W.getPeriodShape())
                .isEqualTo(ReportPeriodShape.AS_OF_FIRE_TIME);
    }

    @Test
    void periodReportKeysMatchPhase17Mapping() {
        assertThat(ReportKey.COMMISSION_STATEMENT.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.LOSS_RATIO.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.COLLECTION_RATE.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.CLAIMS_SUMMARY.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.POLICY_MOVEMENT.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.PERSISTENCY_COHORT.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.GROUP_CENSUS.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.PROVIDER_NETWORK_UTILIZATION.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.REINSURANCE_CESSION_BORDEREAU.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.REINSURANCE_RECOVERIES.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
        assertThat(ReportKey.UPR_MOVEMENT.getPeriodShape())
                .isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
    }
}
