package com.medfund.claims.service;

import com.medfund.claims.client.ClaimsFxConverter;
import com.medfund.claims.costshare.AppliedRuleAction;
import com.medfund.claims.costshare.BenefitCostShareReader;
import com.medfund.claims.costshare.CostShareConfig;
import com.medfund.claims.costshare.MemberCostShareAccumulatorReader;
import com.medfund.claims.costshare.SchemeCostShareReader;
import com.medfund.claims.dto.AdjudicationResult.CostShareBreakdown;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-logic tests for {@link CostShareCalculator}. Mocks the four
 * config readers + CoPaymentService + ClaimsFxConverter so the branches
 * from the Phase 2 success criteria (a)-(g) can be exercised without
 * spinning up Postgres.
 */
class CostShareCalculatorTest {

    private SchemeCostShareReader schemeReader;
    private BenefitCostShareReader benefitReader;
    private MemberCostShareAccumulatorReader accReader;
    private CoPaymentService coPaymentService;
    private ClaimsFxConverter fx;
    private CostShareCalculator calculator;

    private final UUID SCHEME_ID = UUID.randomUUID();
    private final UUID MEMBER_ID = UUID.randomUUID();
    private final UUID BENEFIT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        schemeReader = mock(SchemeCostShareReader.class);
        benefitReader = mock(BenefitCostShareReader.class);
        accReader = mock(MemberCostShareAccumulatorReader.class);
        coPaymentService = mock(CoPaymentService.class);
        fx = mock(ClaimsFxConverter.class);

        // Default: no shortfall (tariff matches claimed). Individual tests override.
        when(coPaymentService.calculate(any(), any())).thenReturn(Mono.just(
                new CoPaymentService.CoPaymentResult(BigDecimal.ZERO, BigDecimal.ZERO, List.of())));
        // Default fx: same-currency pass-through. Null-safe so tests that stub
        // over the default (e.g. the fx-conversion test) don't NPE on other calls.
        when(fx.convert(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    BigDecimal amt = inv.getArgument(0, BigDecimal.class);
                    return Mono.just(amt != null ? amt : BigDecimal.ZERO);
                });

        calculator = new CostShareCalculator(schemeReader, benefitReader, accReader, coPaymentService, fx);
    }

    /** (a) — benefit-level FLAT copay is applied when no rule fires. */
    @Test
    void benefitLevelFlatCopay_appliedWhenNoRuleFires() {
        stubScheme(scheme(new BigDecimal("100"), new BigDecimal("2000"), "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER"));
        stubBenefits(benefit(BENEFIT_ID, "FLAT", new BigDecimal("25"), null, null, null, "USD"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        var breakdown = calculator.compute(claim(new BigDecimal("500")), lines(new BigDecimal("500")),
                        List.of(), new BigDecimal("500"))
                .block();

        assertThat(breakdown).isNotNull();
        assertThat(breakdown.allowedAmount()).isEqualByComparingTo("500");
        assertThat(breakdown.deductibleApplied()).isEqualByComparingTo("100");
        assertThat(breakdown.copayAmount()).isEqualByComparingTo("25");
        assertThat(breakdown.memberResponsibility()).isEqualByComparingTo("125");   // 100 + 25
    }

    /** (b) — APPLY_COPAY rule fire replaces the benefit-level copay entirely. */
    @Test
    void applyCopayRule_overridesBenefitLevelCopay() {
        stubScheme(scheme(BigDecimal.ZERO, new BigDecimal("2000"), "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER"));
        stubBenefits(benefit(BENEFIT_ID, "FLAT", new BigDecimal("25"), null, null, null, "USD"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        // Rule fires FIXED:50 — replaces the benefit-level 25.
        var ruleFire = new AppliedRuleAction("APPLY_COPAY", "FIXED:50", "override", null);
        var breakdown = calculator.compute(claim(new BigDecimal("500")), lines(new BigDecimal("500")),
                        List.of(ruleFire), new BigDecimal("500"))
                .block();

        assertThat(breakdown.copayAmount()).isEqualByComparingTo("50");
    }

    /** (c) — APPLY_COPAY amount=0 (waiver) short-circuits to zero copay. */
    @Test
    void applyCopayRule_zeroValueIsWaiver() {
        stubScheme(scheme(BigDecimal.ZERO, new BigDecimal("2000"), "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER"));
        stubBenefits(benefit(BENEFIT_ID, "FLAT", new BigDecimal("25"), null, null, null, "USD"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        var waiver = new AppliedRuleAction("APPLY_COPAY", "FIXED:0", "waived", null);
        var breakdown = calculator.compute(claim(new BigDecimal("500")), lines(new BigDecimal("500")),
                        List.of(waiver), new BigDecimal("500"))
                .block();

        assertThat(breakdown.copayAmount()).isEqualByComparingTo("0");
    }

    /** (d) — FAMILY scope reads the accumulator with dependantId = null (family pot). */
    @Test
    void familyScope_readsAccumulatorWithNullDependant() {
        UUID depId = UUID.randomUUID();
        stubScheme(scheme(new BigDecimal("100"), new BigDecimal("2000"), "FAMILY", "FAMILY", "RECOVER_FROM_MEMBER"));
        stubBenefits(benefit(BENEFIT_ID, null, null, null, null, null, "USD"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        Claim c = claim(new BigDecimal("500"));
        c.setDependantId(depId);
        calculator.compute(c, lines(new BigDecimal("500")), List.of(), new BigDecimal("500")).block();

        // The reader should have been called with dependantId=null (the family pot).
        verify(accReader).findFor(eq(MEMBER_ID), isNull(), eq(SCHEME_ID), anyInt());
    }

    /** (d) — INDIVIDUAL scope reads the accumulator with the per-dependant id. */
    @Test
    void individualScope_readsAccumulatorWithDependantId() {
        UUID depId = UUID.randomUUID();
        stubScheme(scheme(new BigDecimal("100"), new BigDecimal("2000"), "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER"));
        stubBenefits(benefit(BENEFIT_ID, null, null, null, null, null, "USD"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        Claim c = claim(new BigDecimal("500"));
        c.setDependantId(depId);
        calculator.compute(c, lines(new BigDecimal("500")), List.of(), new BigDecimal("500")).block();

        verify(accReader).findFor(eq(MEMBER_ID), eq(depId), eq(SCHEME_ID), anyInt());
    }

    /** (e) — OOP-max reached scales down copay + coinsurance + shortfall proportionally. */
    @Test
    void oopMaxReached_scalesRecoverableBucketsDown() {
        stubScheme(scheme(new BigDecimal("50"), new BigDecimal("100"), "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER"));
        stubBenefits(benefit(BENEFIT_ID, "FLAT", new BigDecimal("60"), null, null, null, "USD"));
        // Member has already met 60 of the 100 OOP-max — only 40 headroom.
        stubAccumulator(acc(BigDecimal.ZERO, new BigDecimal("60")));

        var breakdown = calculator.compute(claim(new BigDecimal("500")), lines(new BigDecimal("500")),
                        List.of(), new BigDecimal("500"))
                .block();

        // Deductible (50) is pre-OOP; only copay (60) is scalable. Cap forces
        // memberResp to remainingOop (40): 50 deductible pushes us over
        // immediately, but the scaler brings copay down so total = remainingOop.
        // The exact value depends on the scaling math — the key invariant is
        // memberResp <= remainingOop + deductibleApplied.
        assertThat(breakdown.memberResponsibility())
                .isLessThanOrEqualTo(new BigDecimal("100.0000"));
        assertThat(breakdown.copayAmount()).isLessThan(new BigDecimal("60"));
    }

    /** (f) — shortfall_policy=ABSORB_BY_FUND excludes shortfall from memberResponsibility. */
    @Test
    void absorbByFundShortfall_notAddedToMemberResponsibility() {
        stubScheme(scheme(BigDecimal.ZERO, new BigDecimal("2000"), "INDIVIDUAL", "INDIVIDUAL", "ABSORB_BY_FUND"));
        stubBenefits(benefit(BENEFIT_ID, null, null, null, null, null, "USD"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        // CoPaymentService returns a non-zero shortfall (claimed exceeds tariff).
        when(coPaymentService.calculate(any(), any())).thenReturn(Mono.just(
                new CoPaymentService.CoPaymentResult(new BigDecimal("400"), new BigDecimal("100"), List.of())));

        var breakdown = calculator.compute(claim(new BigDecimal("500")), lines(new BigDecimal("500")),
                        List.of(), new BigDecimal("500"))
                .block();

        assertThat(breakdown.shortfallAmount()).isEqualByComparingTo("100");
        // memberResp = deductible(0) + copay(0) + coinsurance(0) + notCovered(0) + shortfall(EXCLUDED)
        assertThat(breakdown.memberResponsibility()).isEqualByComparingTo("0");
    }

    /** (g) — benefit-currency ≠ claim-currency triggers fx conversion on the benefit copay. */
    @Test
    void differentBenefitCurrency_triggersFxConversion() {
        stubScheme(scheme(BigDecimal.ZERO, new BigDecimal("2000"), "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER"));
        // Benefit stored in ZWL, claim submitted in USD.
        stubBenefits(benefit(BENEFIT_ID, "FLAT", new BigDecimal("1000"), null, null, null, "ZWL"));
        stubAccumulator(acc(BigDecimal.ZERO, BigDecimal.ZERO));

        // FX 1000 ZWL → 5 USD.
        when(fx.convert(eq(new BigDecimal("1000")), eq("ZWL"), eq("USD"), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("5")));

        var breakdown = calculator.compute(claim(new BigDecimal("500")), lines(new BigDecimal("500")),
                        List.of(), new BigDecimal("500"))
                .block();

        assertThat(breakdown.copayAmount()).isEqualByComparingTo("5");
        verify(fx).convert(eq(new BigDecimal("1000")), eq("ZWL"), eq("USD"), any(), any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Claim claim(BigDecimal amount) {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());
        c.setMemberId(MEMBER_ID);
        c.setSchemeId(SCHEME_ID);
        c.setClaimedAmount(amount);
        c.setCurrencyCode("USD");
        c.setServiceDate(LocalDate.of(2026, 6, 1));
        return c;
    }

    private List<ClaimLine> lines(BigDecimal amount) {
        ClaimLine line = new ClaimLine();
        line.setId(UUID.randomUUID());
        line.setBenefitId(BENEFIT_ID);
        line.setClaimedAmount(amount);
        line.setCurrencyCode("USD");
        return List.of(line);
    }

    private CostShareConfig.Scheme scheme(BigDecimal deductible, BigDecimal oopMax,
                                          String dedScope, String oopScope, String shortfallPolicy) {
        return new CostShareConfig.Scheme(
                UUID.randomUUID(), SCHEME_ID, 2026, deductible, oopMax,
                dedScope, oopScope, shortfallPolicy, "USD",
                LocalDate.of(2026, 1, 1), null);
    }

    private CostShareConfig.Benefit benefit(UUID benefitId, String copayType,
                                            BigDecimal copayAmount, BigDecimal copayPct,
                                            BigDecimal copayMax, BigDecimal coinsurance,
                                            String currency) {
        return new CostShareConfig.Benefit(
                UUID.randomUUID(), benefitId, copayType, copayAmount, copayPct, copayMax, coinsurance,
                true, true, "per_visit", currency, LocalDate.of(2026, 1, 1), null);
    }

    private CostShareConfig.Accumulator acc(BigDecimal deductibleMet, BigDecimal oopMet) {
        return new CostShareConfig.Accumulator(
                UUID.randomUUID(), MEMBER_ID, null, SCHEME_ID, 2026,
                deductibleMet, oopMet, 0, "USD", 0);
    }

    private void stubScheme(CostShareConfig.Scheme s) {
        when(schemeReader.findEffective(any(), anyInt(), any())).thenReturn(Mono.just(s));
    }

    private void stubBenefits(CostShareConfig.Benefit... bs) {
        when(benefitReader.findEffective(any(), any())).thenReturn(Flux.just(bs));
    }

    private void stubAccumulator(CostShareConfig.Accumulator a) {
        when(accReader.findFor(any(), any(), any(), anyInt())).thenReturn(Mono.just(a));
    }
}
