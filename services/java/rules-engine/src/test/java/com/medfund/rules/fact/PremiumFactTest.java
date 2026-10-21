package com.medfund.rules.fact;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit assertions for the ACCRUE_PREMIUM action method + the default
 * earning-method fall-back. Covers the code path executed at DRL fire
 * time — the rest of the fact is Lombok getter/setter surface.
 */
class PremiumFactTest {

    @Test
    void defaults_earningMethodToDailyLinear() {
        // A policy with no matching PREMIUM_EARNING rule still earns via
        // the DAILY_LINEAR default.
        PremiumFact fact = new PremiumFact();
        assertThat(fact.getEarningMethod()).isEqualTo("DAILY_LINEAR");
        assertThat(fact.getLoadingPercent()).isNull();
        assertThat(fact.getResults()).isEmpty();
    }

    @Test
    void accrue_overridesMethod_andAppendsRuleResult() {
        PremiumFact fact = new PremiumFact();
        fact.setPolicyId("11111111-1111-1111-1111-111111111111");

        fact.accrue("MONTHLY_24THS", null, "IPEC 24ths");

        assertThat(fact.getEarningMethod()).isEqualTo("MONTHLY_24THS");
        assertThat(fact.getLoadingPercent()).isNull();
        assertThat(fact.getResults()).hasSize(1);
        RuleResult r = fact.getResults().get(0);
        assertThat(r.getType()).isEqualTo("ACCRUE_PREMIUM");
        assertThat(r.getCode()).isEqualTo("MONTHLY_24THS");
        assertThat(r.getMessage()).isEqualTo("IPEC 24ths");
    }

    @Test
    void accrue_carriesLoadingPercent_forLinearWithLoading() {
        PremiumFact fact = new PremiumFact();
        fact.accrue("LINEAR_WITH_LOADING", new BigDecimal("15"), "Whole-life 15%");

        assertThat(fact.getEarningMethod()).isEqualTo("LINEAR_WITH_LOADING");
        assertThat(fact.getLoadingPercent()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(fact.getResults()).hasSize(1);
    }

    @Test
    void gettersAndSetters_roundTripEveryField() {
        PremiumFact fact = new PremiumFact();
        fact.setPolicyId("policy-1");
        fact.setPolicySource("LIFE_POLICY");
        fact.setInsuranceLine("LIFE");
        fact.setProductCode("WHOLE_LIFE");
        fact.setTenantId("tenant-1");
        fact.setWrittenPremium(new BigDecimal("1200.00"));
        fact.setCurrencyCode("USD");
        fact.setCoverageStart(LocalDate.of(2026, 1, 1));
        fact.setCoverageEnd(LocalDate.of(2026, 12, 31));
        fact.setBoundAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        fact.setPortfolioId("portfolio-1");
        fact.setCohortId("cohort-1");

        assertThat(fact.getPolicyId()).isEqualTo("policy-1");
        assertThat(fact.getPolicySource()).isEqualTo("LIFE_POLICY");
        assertThat(fact.getInsuranceLine()).isEqualTo("LIFE");
        assertThat(fact.getProductCode()).isEqualTo("WHOLE_LIFE");
        assertThat(fact.getTenantId()).isEqualTo("tenant-1");
        assertThat(fact.getWrittenPremium()).isEqualByComparingTo("1200.00");
        assertThat(fact.getCurrencyCode()).isEqualTo("USD");
        assertThat(fact.getCoverageStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(fact.getCoverageEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(fact.getBoundAt()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThat(fact.getPortfolioId()).isEqualTo("portfolio-1");
        assertThat(fact.getCohortId()).isEqualTo("cohort-1");
    }
}
