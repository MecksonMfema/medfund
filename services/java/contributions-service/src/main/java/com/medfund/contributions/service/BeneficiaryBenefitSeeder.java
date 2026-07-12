package com.medfund.contributions.service;

import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.repository.BeneficiaryAnnualTotalRepository;
import com.medfund.contributions.repository.BeneficiaryBenefitRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * V061 — seeds per-beneficiary ledger rows at enrolment time so
 * {@code beneficiary_benefits} is populated from the moment a member or
 * dependant is enrolled, not only when the V060 backfill was applied.
 *
 * <p>Called from {@code MemberEnrolledConsumer} and
 * {@code DependantEnrolledConsumer} so ordering, tenant context, and
 * idempotent-offset ack are inherited from the single Kafka consumer
 * loop each of them already owns.
 *
 * <h4>Filters</h4>
 * <ul>
 *   <li>Scheme-level opt-out — {@code scheme.tracksMemberBalances = false}
 *       skips the whole seed. Typical for pure indemnity products
 *       (VEHICLE, PROPERTY).</li>
 *   <li>Benefit-level opt-out — {@code usageMode = NO_TRACKING}
 *       benefits skip. They still show on the scheme catalogue but
 *       nothing accrues on the beneficiary.</li>
 *   <li>Age-gate — benefits with {@code min_age}/{@code max_age} outside
 *       the beneficiary's age at enrolment are skipped so the ledger
 *       matches what {@code AdjudicationPipeline} Stage 3 enforces.</li>
 *   <li>Only {@code status = 'active'} benefits are seeded — deactivated
 *       benefits are excluded to avoid stale rows on the utilization
 *       page.</li>
 * </ul>
 *
 * <p>{@code ONE_TIME_PER_BENEFICIARY} rows use {@code policy_year = 0}
 * as a sentinel so the annual rollover job skips them: those benefits
 * pay out once for the beneficiary's lifetime and must not reset.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BeneficiaryBenefitSeeder {

    static final int LIFETIME_POLICY_YEAR = 0;

    private final SchemeRepository schemeRepository;
    private final SchemeBenefitRepository schemeBenefitRepository;
    private final BeneficiaryBenefitRepository beneficiaryBenefitRepository;
    private final BeneficiaryAnnualTotalRepository beneficiaryAnnualTotalRepository;

    /**
     * Seed benefit rows for a newly-enrolled beneficiary.
     *
     * @param memberId       always required — either the beneficiary or
     *                       the dependant's sponsor.
     * @param dependantId    null when seeding the member's own rows,
     *                       populated when seeding a dependant's.
     * @param schemeId       resolved scheme; null-check happens upstream
     *                       in the consumer.
     * @param enrollmentDate anchors the policy_year and drives age-gate
     *                       arithmetic.
     * @param dateOfBirth    optional — when null, age-gated benefits are
     *                       skipped (fail-closed).
     */
    public Mono<Void> seed(UUID memberId, UUID dependantId, UUID schemeId,
                           LocalDate enrollmentDate, LocalDate dateOfBirth) {
        if (memberId == null || schemeId == null || enrollmentDate == null) {
            log.debug("BeneficiaryBenefitSeeder: missing required field, skipping");
            return Mono.empty();
        }
        int policyYear = enrollmentDate.getYear();

        return schemeRepository.findById(schemeId)
                .flatMap(scheme -> {
                    if (Boolean.FALSE.equals(scheme.getTracksMemberBalances())) {
                        log.debug("Scheme {} opts out of member balance tracking — no seed", schemeId);
                        return Mono.empty();
                    }
                    return seedForScheme(memberId, dependantId, scheme, policyYear,
                                         dateOfBirth, enrollmentDate);
                });
    }

    private Mono<Void> seedForScheme(UUID memberId, UUID dependantId, Scheme scheme,
                                     int policyYear, LocalDate dateOfBirth,
                                     LocalDate enrollmentDate) {
        Mono<Void> perBenefit = schemeBenefitRepository.findBySchemeId(scheme.getId())
                .filter(this::isSeedable)
                .filter(b -> passesAgeGate(b, dateOfBirth, enrollmentDate))
                .flatMap(b -> seedOne(memberId, dependantId, scheme, b, policyYear))
                .then();
        // V062: annual cap ledger row — only seeded when the scheme has
        // a cap set (typical for multi-benefit medical-aid schemes).
        Mono<Void> capSeed = seedAnnualTotal(memberId, dependantId, scheme, policyYear);
        return perBenefit.then(capSeed);
    }

    /**
     * V062 cap ledger seed. Idempotent — ON CONFLICT DO NOTHING inside
     * the repo. Uses the scheme's currency; falls back to USD if unset.
     */
    private Mono<Void> seedAnnualTotal(UUID memberId, UUID dependantId, Scheme scheme, int policyYear) {
        if (scheme.getAnnualMemberCap() == null) return Mono.empty();
        String currency = scheme.getCurrencyCode() != null ? scheme.getCurrencyCode() : "USD";
        return beneficiaryAnnualTotalRepository.seedRow(
                        scheme.getId(), memberId, dependantId, policyYear, currency)
                .doOnNext(rows -> {
                    if (rows == 0) {
                        log.debug("BeneficiaryAnnualTotal already exists for scheme {} year {} — no-op",
                                scheme.getId(), policyYear);
                    }
                })
                .then();
    }

    private boolean isSeedable(SchemeBenefit b) {
        if (b.getStatus() != null && !"active".equalsIgnoreCase(b.getStatus())) return false;
        String mode = b.getUsageMode() != null ? b.getUsageMode() : "RUNNING_BALANCE";
        return !"NO_TRACKING".equals(mode);
    }

    /**
     * Fail-closed: when we can't compute an age but the benefit has a
     * gate configured, skip. AdjudicationPipeline Stage 3 would reject
     * an out-of-range claim anyway; seeding a row we can't verify would
     * mislead the utilization card.
     */
    private boolean passesAgeGate(SchemeBenefit b, LocalDate dateOfBirth, LocalDate enrollmentDate) {
        Short min = b.getMinAge();
        Short max = b.getMaxAge();
        if (min == null && max == null) return true;
        if (dateOfBirth == null) return false;
        int age = Period.between(dateOfBirth, enrollmentDate).getYears();
        if (min != null && age < min) return false;
        if (max != null && age > max) return false;
        return true;
    }

    private Mono<Integer> seedOne(UUID memberId, UUID dependantId, Scheme scheme,
                                  SchemeBenefit benefit, int policyYear) {
        int year = "ONE_TIME_PER_BENEFICIARY".equals(benefit.getUsageMode())
                ? LIFETIME_POLICY_YEAR
                : policyYear;
        String currency = benefit.getCurrencyCode() != null
                ? benefit.getCurrencyCode()
                : scheme.getCurrencyCode();
        return beneficiaryBenefitRepository.seedRow(
                        memberId, dependantId, benefit.getId(),
                        scheme.getId(), year, currency)
                .doOnNext(rows -> {
                    if (rows == 0) {
                        log.debug("BeneficiaryBenefit already exists for benefit {} year {} — no-op",
                                benefit.getId(), year);
                    }
                });
    }
}
