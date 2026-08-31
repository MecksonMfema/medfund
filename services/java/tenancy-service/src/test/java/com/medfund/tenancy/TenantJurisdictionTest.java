package com.medfund.tenancy;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TenantJurisdictionTest {

    @Test
    void parse_roundTripsEveryDeclaredValue() {
        for (TenantJurisdiction j : TenantJurisdiction.values()) {
            assertThat(TenantJurisdiction.parse(j.name()))
                    .as("parse should recognise its own name for %s", j.name())
                    .contains(j);
        }
    }

    @Test
    void parse_returnsEmptyForNullAndBlank() {
        assertThat(TenantJurisdiction.parse(null)).isEmpty();
        assertThat(TenantJurisdiction.parse("")).isEmpty();
        assertThat(TenantJurisdiction.parse("   ")).isEmpty();
    }

    @Test
    void parse_returnsEmptyForUnknownValue() {
        assertThat(TenantJurisdiction.parse("XX_INVALID")).isEmpty();
        assertThat(TenantJurisdiction.parse("zw_ipec_short_term")).isEmpty(); // case-sensitive
    }

    @Test
    void displayLabels_areNotBlank() {
        for (TenantJurisdiction j : TenantJurisdiction.values()) {
            assertThat(j.displayLabel()).isNotBlank();
        }
    }

    @Test
    void validValues_listsEveryEnumName() {
        String csv = TenantJurisdiction.validValues();
        for (TenantJurisdiction j : TenantJurisdiction.values()) {
            assertThat(csv).contains(j.name());
        }
    }

    @Test
    void enum_carriesExpectedSixValues() {
        // Locked in by Phase 1 of the regulatory-format reports plan; widening
        // requires a matching Angular JURISDICTIONS change (settings.component.ts).
        assertThat(TenantJurisdiction.values()).hasSize(6);
        Optional.of(TenantJurisdiction.values()).ifPresent(vals -> {
            assertThat(vals).containsExactly(
                    TenantJurisdiction.ZW_IPEC_SHORT_TERM,
                    TenantJurisdiction.ZW_IPEC_LIFE,
                    TenantJurisdiction.ZA_CMS_MEDICAL_SCHEME,
                    TenantJurisdiction.ZA_FSCA_SHORT_TERM,
                    TenantJurisdiction.ZA_FSCA_LONG_TERM,
                    TenantJurisdiction.US_NAIC);
        });
    }
}
