package com.medfund.shared.report;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportKeyTest {

    @Test
    void parse_matchesUpperCaseName() {
        assertThat(ReportKey.parse("BILLING_REPORT"))
                .hasValueSatisfying(k -> assertThat(k).isEqualTo(ReportKey.BILLING_REPORT));
    }

    @Test
    void parse_isCaseInsensitive() {
        assertThat(ReportKey.parse("billing_report"))
                .hasValueSatisfying(k -> assertThat(k).isEqualTo(ReportKey.BILLING_REPORT));
    }

    @Test
    void parse_returnsEmptyOnUnknown() {
        assertThat(ReportKey.parse("UNKNOWN_KEY")).isEmpty();
    }

    @Test
    void parse_returnsEmptyOnNull() {
        assertThat(ReportKey.parse(null)).isEmpty();
    }

    @Test
    void key_matchesEnumName() {
        Stream.of(ReportKey.values()).forEach(k ->
                assertThat(k.key()).isEqualTo(k.name()));
    }

    @Test
    void labelAndFamilyAreNeverBlank() {
        Stream.of(ReportKey.values()).forEach(k -> {
            assertThat(k.getLabel()).isNotBlank();
            assertThat(k.getFamily()).isNotNull();
        });
    }

    @Test
    void cadencedFlagSurfacesForKnownScheduledReports() {
        assertThat(ReportKey.CASH_FLOW_FORECAST_13W.isCadenced()).isTrue();
        assertThat(ReportKey.COMMISSION_STATEMENT.isCadenced()).isTrue();
        assertThat(ReportKey.MEMBER_STATEMENT.isCadenced()).isFalse();
        assertThat(ReportKey.CREDITORS.isCadenced()).isFalse();
    }

    @Test
    void preAuthKeyWasRenamedToActivityPerG43() {
        // G43: PRE_AUTH_UTILIZATION reshaped to PRE_AUTH_ACTIVITY — the old
        // name must not resurface (no tenant config row exists for either key).
        assertThat(ReportKey.parse("PRE_AUTH_ACTIVITY"))
                .hasValueSatisfying(k -> assertThat(k).isEqualTo(ReportKey.PRE_AUTH_ACTIVITY));
        assertThat(ReportKey.parse("PRE_AUTH_UTILIZATION")).isEmpty();
    }

    @Test
    void everyReportKeyIsUnique() {
        Set<String> names = Stream.of(ReportKey.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        assertThat(names).hasSameSizeAs(ReportKey.values());
    }

    @Test
    void assertsFamilyReassignment_forThreeKeys() {
        // Phase 13 §C L8 — POLICY_MOVEMENT / PERSISTENCY_COHORT / GROUP_CENSUS
        // moved from CLAIMS_FINANCIAL into POLICY_LIFECYCLE; cadence flipped
        // per L14 so tenant admins can schedule them for recurring email.
        assertThat(ReportKey.POLICY_MOVEMENT.getFamily()).isEqualTo(ReportFamily.POLICY_LIFECYCLE);
        assertThat(ReportKey.POLICY_MOVEMENT.isCadenced()).isTrue();
        assertThat(ReportKey.PERSISTENCY_COHORT.getFamily()).isEqualTo(ReportFamily.POLICY_LIFECYCLE);
        assertThat(ReportKey.PERSISTENCY_COHORT.isCadenced()).isTrue();
        assertThat(ReportKey.GROUP_CENSUS.getFamily()).isEqualTo(ReportFamily.POLICY_LIFECYCLE);
        assertThat(ReportKey.GROUP_CENSUS.isCadenced()).isTrue();
        // PROVIDER_NETWORK_UTILIZATION stays in CLAIMS_FINANCIAL; cadence flipped to true per L14.
        assertThat(ReportKey.PROVIDER_NETWORK_UTILIZATION.getFamily())
                .isEqualTo(ReportFamily.CLAIMS_FINANCIAL);
        assertThat(ReportKey.PROVIDER_NETWORK_UTILIZATION.isCadenced()).isTrue();
    }

    @Test
    void familyBucketsAreCoveredByAtLeastOneKey() {
        Set<ReportFamily> covered = Stream.of(ReportKey.values())
                .map(ReportKey::getFamily)
                .collect(java.util.stream.Collectors.toSet());
        // Every declared family has at least one report — otherwise the
        // family enum would carry a dead entry.
        assertThat(covered).containsAll(Set.of(ReportFamily.values()));
    }
}
