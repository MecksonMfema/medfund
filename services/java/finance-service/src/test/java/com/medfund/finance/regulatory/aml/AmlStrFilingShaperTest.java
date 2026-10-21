package com.medfund.finance.regulatory.aml;

import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AmlStrFilingShaperTest {

    private final AmlStrFilingShaper shaper = new AmlStrFilingShaper();

    @Test
    void compose_populatesEveryEnumKey_forFullyPopulatedAlert() {
        SuspiciousTransactionAlert alert = filedAlert();

        Map<AmlStrField, Object> data = shaper.compose(
                alert, "Acme Insurance ZA (Pty) Ltd", "FIC-REG-2026-0000042", "ZA");

        assertThat(data).containsOnlyKeys(AmlStrField.values());
        assertThat(data.get(AmlStrField.META_COUNTRY)).isEqualTo("ZA");
        assertThat(data.get(AmlStrField.META_TEMPLATE_KEY)).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZA);
        assertThat(data.get(AmlStrField.META_REPORTING_ENTITY_NAME))
                .isEqualTo("Acme Insurance ZA (Pty) Ltd");
        assertThat(data.get(AmlStrField.META_REGULATOR_REFERENCE))
                .isEqualTo("FIC-REG-2026-0000042");
        assertThat(data.get(AmlStrField.ALERT_TRANSACTION_REF)).isEqualTo("TXN-2026-000042");
        assertThat(data.get(AmlStrField.ALERT_AMOUNT)).isEqualTo(new BigDecimal("125000.00"));
        assertThat(data.get(AmlStrField.ALERT_FILED_REF)).isEqualTo("FIC-STR-2026-0009999");
    }

    @Test
    void templateKeyFor_mapsThreeCountries_andDefaultsToFic() {
        assertThat(AmlStrFilingShaper.templateKeyFor("ZW")).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZW);
        assertThat(AmlStrFilingShaper.templateKeyFor("ZA")).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZA);
        assertThat(AmlStrFilingShaper.templateKeyFor("US")).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_US);
        assertThat(AmlStrFilingShaper.templateKeyFor("KE")).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZA);
        assertThat(AmlStrFilingShaper.templateKeyFor(null)).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZA);
    }

    @Test
    void compose_nullOptionalFields_writeEmptyString_notNpe() {
        SuspiciousTransactionAlert alert = new SuspiciousTransactionAlert();
        alert.setId(UUID.randomUUID());
        alert.setTransactionRef("TXN-EDGE");
        alert.setTransactionType("PREMIUM");
        alert.setAmountNative(new BigDecimal("100.00"));
        alert.setCurrency("USD");
        // memberId / providerId / description / review / file trail all null

        Map<AmlStrField, Object> data = shaper.compose(alert, null, null, "US");

        assertThat(data.get(AmlStrField.META_REPORTING_ENTITY_NAME)).isEqualTo("");
        assertThat(data.get(AmlStrField.META_REGULATOR_REFERENCE)).isEqualTo("");
        assertThat(data.get(AmlStrField.ALERT_MEMBER_ID)).isEqualTo("");
        assertThat(data.get(AmlStrField.ALERT_PROVIDER_ID)).isEqualTo("");
        assertThat(data.get(AmlStrField.ALERT_DESCRIPTION)).isEqualTo("");
        assertThat(data.get(AmlStrField.ALERT_REVIEW_NOTE)).isEqualTo("");
        assertThat(data.get(AmlStrField.ALERT_FILED_REF)).isEqualTo("");
        assertThat(data.get(AmlStrField.META_COUNTRY)).isEqualTo("US");
        assertThat(data.get(AmlStrField.META_TEMPLATE_KEY)).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_US);
    }

    @Test
    void compose_alertNull_isRejected() {
        try {
            shaper.compose(null, "Entity", "REG-1", "ZA");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("alert required");
            return;
        }
        throw new AssertionError("expected IllegalArgumentException for null alert");
    }

    @Test
    void compose_lowercaseCountry_isUppercased() {
        SuspiciousTransactionAlert alert = filedAlert();
        Map<AmlStrField, Object> data = shaper.compose(alert, "Acme ZW", "FIU-1", "zw");

        assertThat(data.get(AmlStrField.META_COUNTRY)).isEqualTo("ZW");
        assertThat(data.get(AmlStrField.META_TEMPLATE_KEY)).isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZW);
    }

    public static SuspiciousTransactionAlert filedAlert() {
        SuspiciousTransactionAlert a = new SuspiciousTransactionAlert();
        a.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        a.setStatus("FILED");
        a.setTransactionRef("TXN-2026-000042");
        a.setTransactionType("PREMIUM");
        a.setAmountNative(new BigDecimal("125000.00"));
        a.setCurrency("ZAR");
        a.setMemberId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        a.setProviderId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        a.setDescription("Large round-number premium via cash on newly onboarded member; "
                + "warrants further review per FIC guidance.");
        a.setRaisedByActorId(UUID.randomUUID());
        a.setRaisedByActorEmail("raiser@medfund");
        a.setRaisedAt(OffsetDateTime.parse("2026-04-01T09:00:00Z"));
        a.setReviewerActorId(UUID.randomUUID());
        a.setReviewerActorEmail("reviewer@medfund");
        a.setReviewedAt(OffsetDateTime.parse("2026-04-02T10:00:00Z"));
        a.setReviewNote("Confirmed suspicious — matches Rand-round-number pattern");
        a.setFilerActorId(UUID.randomUUID());
        a.setFilerActorEmail("filer@medfund");
        a.setFiledAt(OffsetDateTime.parse("2026-04-03T14:30:00Z"));
        a.setFiledRef("FIC-STR-2026-0009999");
        return a;
    }
}
