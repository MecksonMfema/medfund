package com.medfund.contributions.service;

import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.repository.BeneficiaryAnnualTotalRepository;
import com.medfund.contributions.repository.BeneficiaryBenefitRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeneficiaryBenefitSeederTest {

    @Mock SchemeRepository schemeRepository;
    @Mock SchemeBenefitRepository schemeBenefitRepository;
    @Mock BeneficiaryBenefitRepository beneficiaryBenefitRepository;
    @Mock BeneficiaryAnnualTotalRepository beneficiaryAnnualTotalRepository;

    private BeneficiaryBenefitSeeder seeder;

    private UUID memberId;
    private UUID schemeId;
    private LocalDate enrollDate;
    private LocalDate dob;

    @BeforeEach
    void setUp() {
        seeder = new BeneficiaryBenefitSeeder(schemeRepository, schemeBenefitRepository,
                beneficiaryBenefitRepository, beneficiaryAnnualTotalRepository);
        memberId = UUID.randomUUID();
        schemeId = UUID.randomUUID();
        enrollDate = LocalDate.of(2026, 7, 1);
        dob = LocalDate.of(1990, 1, 1); // age 36 at enrollDate
        lenient().when(beneficiaryBenefitRepository.seedRow(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1));
        // V062 annual-totals seed default — returns 1 row when called
        // (the specific tests override to assert whether it's called at all).
        lenient().when(beneficiaryAnnualTotalRepository.seedRow(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1));
    }

    @Test
    void skipsSeed_whenSchemeOptsOutOfMemberBalances() {
        var scheme = scheme(false);
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        verify(schemeBenefitRepository, never()).findBySchemeId(any());
        verify(beneficiaryBenefitRepository, never()).seedRow(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsBenefit_whenUsageModeIsNoTracking() {
        var scheme = scheme(true);
        var benefit = benefit("RUNNING_BALANCE"); // one seeded
        var untracked = benefit("NO_TRACKING");   // one skipped
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(benefit, untracked));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        // Only the RUNNING_BALANCE benefit gets seeded.
        verify(beneficiaryBenefitRepository, times(1))
                .seedRow(any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsBenefit_whenBeneficiaryIsOutsideAgeGate() {
        var scheme = scheme(true);
        // Benefit is 40+; beneficiary is 36 at enrollDate → skipped.
        var youthBenefit = benefit("RUNNING_BALANCE");
        youthBenefit.setMinAge((short) 40);
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(youthBenefit));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        verify(beneficiaryBenefitRepository, never())
                .seedRow(any(), any(), any(), any(), any(), any());
    }

    @Test
    void usesPolicyYearZero_forOneTimePerBeneficiary() {
        var scheme = scheme(true);
        var lifetime = benefit("ONE_TIME_PER_BENEFICIARY");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(lifetime));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        ArgumentCaptor<Integer> yearCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(beneficiaryBenefitRepository).seedRow(
                eq(memberId), eq((UUID) null), eq(lifetime.getId()), eq(schemeId), yearCaptor.capture(), any());
        assert yearCaptor.getValue() == 0
                : "ONE_TIME_PER_BENEFICIARY must use policy_year=0 sentinel";
    }

    @Test
    void usesEnrollmentYear_forRunningBalance() {
        var scheme = scheme(true);
        var running = benefit("RUNNING_BALANCE");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(running));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        ArgumentCaptor<Integer> yearCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(beneficiaryBenefitRepository).seedRow(
                any(), any(), any(), any(), yearCaptor.capture(), any());
        assert yearCaptor.getValue() == 2026 : "expected policy_year to match enrollmentDate year";
    }

    @Test
    void skipsAgeGatedBenefit_whenDobIsMissing() {
        var scheme = scheme(true);
        // Age-gated benefit with no DOB → fail-closed (skipped).
        var gated = benefit("RUNNING_BALANCE");
        gated.setMinAge((short) 18);
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(gated));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, null)).verifyComplete();

        verify(beneficiaryBenefitRepository, never())
                .seedRow(any(), any(), any(), any(), any(), any());
    }

    // ── V062 annual-cap ledger seeding ──────────────────────────────────

    /**
     * V062 — a scheme configured with {@code annual_member_cap} seeds a
     * cap-ledger row alongside the per-benefit rows. Without this row
     * the claim-detail annual-cap widget starts empty until the first
     * claim, which reads worse than showing 0 / cap up front.
     */
    @Test
    void seed_schemeWithAnnualCap_alsoSeedsAnnualTotalsRow() {
        var scheme = scheme(true);
        scheme.setAnnualMemberCap(new java.math.BigDecimal("50000"));
        var running = benefit("RUNNING_BALANCE");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(running));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        // Cap-ledger seed fires with the scheme + beneficiary keys and
        // the enrollment year (2026). Currency inherits from the scheme.
        verify(beneficiaryAnnualTotalRepository).seedRow(
                eq(schemeId), eq(memberId), eq((UUID) null), eq(2026), eq("USD"));
    }

    /**
     * V062 — a scheme with a null {@code annual_member_cap} must NOT
     * seed the cap ledger. Single-benefit products (funeral, life) don't
     * carry an aggregate cap.
     */
    @Test
    void seed_schemeWithoutAnnualCap_skipsAnnualTotalsSeed() {
        var scheme = scheme(true); // no cap
        var running = benefit("RUNNING_BALANCE");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(running));

        StepVerifier.create(seeder.seed(memberId, null, schemeId, enrollDate, dob)).verifyComplete();

        verify(beneficiaryAnnualTotalRepository, never())
                .seedRow(any(), any(), any(), any(), any());
    }

    /**
     * V062 — dependants get their own cap ledger row. The dependant_id
     * lands in the row so the claim-detail widget can scope
     * consumed-vs-cap per beneficiary.
     */
    @Test
    void seed_dependant_withCap_seedsAnnualTotalsRowForDependant() {
        UUID dependantId = UUID.randomUUID();
        var scheme = scheme(true);
        scheme.setAnnualMemberCap(new java.math.BigDecimal("50000"));
        var running = benefit("RUNNING_BALANCE");
        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(scheme));
        when(schemeBenefitRepository.findBySchemeId(schemeId)).thenReturn(Flux.just(running));

        StepVerifier.create(seeder.seed(memberId, dependantId, schemeId, enrollDate, dob)).verifyComplete();

        verify(beneficiaryAnnualTotalRepository).seedRow(
                eq(schemeId), eq(memberId), eq(dependantId), eq(2026), any());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private Scheme scheme(boolean tracks) {
        var s = new Scheme();
        s.setId(schemeId);
        s.setName("Test scheme");
        s.setCurrencyCode("USD");
        s.setInsuranceLine("HEALTH");
        s.setTracksMemberBalances(tracks);
        return s;
    }

    private SchemeBenefit benefit(String usageMode) {
        var b = new SchemeBenefit();
        b.setId(UUID.randomUUID());
        b.setSchemeId(schemeId);
        b.setName("Benefit " + usageMode);
        b.setBenefitType("consultation");
        b.setCurrencyCode("USD");
        b.setUsageMode(usageMode);
        b.setStatus("active");
        return b;
    }
}
