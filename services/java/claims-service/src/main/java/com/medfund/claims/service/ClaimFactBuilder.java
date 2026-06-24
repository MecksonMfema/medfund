package com.medfund.claims.service;

import com.medfund.claims.entity.Claim;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.ProviderFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Translates a {@link Claim} entity into the fact triple ({@link ClaimFact},
 * {@link MemberFact}, {@link ProviderFact}) consumed by the rules engine.
 *
 * <p>Kept deliberately narrow: the {@code AdjudicationPipeline} already runs
 * its own queries for the six hardcoded stages; this builder issues one
 * additional lightweight read against the tenant {@code members} table (and an
 * optional one against {@code public.providers}) to populate the
 * member-and-provider context the engine needs. We can fold these reads
 * into the upstream stages later if profiling shows it's worth it.
 */
@Slf4j
@Component
public class ClaimFactBuilder {

    private final DatabaseClient db;

    public ClaimFactBuilder(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Facts> build(Claim claim) {
        ClaimFact claimFact = toClaimFact(claim);

        Mono<MemberFact> memberFact = claim.getMemberId() == null
                ? Mono.just(new MemberFact())
                : fetchMember(claim.getMemberId().toString());

        Mono<ProviderFact> providerFact = claim.getProviderId() == null
                ? Mono.just(emptyProvider(null))
                : fetchProvider(claim.getProviderId().toString());

        return Mono.zip(memberFact, providerFact)
                .map(t -> new Facts(claimFact, t.getT1(), t.getT2()));
    }

    // ── ClaimFact ────────────────────────────────────────────────────────────

    private ClaimFact toClaimFact(Claim claim) {
        ClaimFact f = new ClaimFact();
        f.setClaimId(claim.getId() != null ? claim.getId().toString() : null);
        f.setMemberId(claim.getMemberId() != null ? claim.getMemberId().toString() : null);
        f.setProviderId(claim.getProviderId() != null ? claim.getProviderId().toString() : null);
        f.setSchemeId(claim.getSchemeId() != null ? claim.getSchemeId().toString() : null);
        f.setAmount(claim.getClaimedAmount());
        f.setDateOfService(claim.getServiceDate());
        LocalDate submission = claim.getCreatedAt() != null
                ? claim.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate()
                : null;
        f.setSubmissionDate(submission);
        if (claim.getServiceDate() != null) {
            LocalDate base = submission != null ? submission : LocalDate.now();
            f.setDaysSinceService((int) ChronoUnit.DAYS.between(claim.getServiceDate(), base));
        }
        return f;
    }

    // ── MemberFact ───────────────────────────────────────────────────────────

    private Mono<MemberFact> fetchMember(String memberId) {
        return db.sql("SELECT status, enrollment_date, date_of_birth, gender FROM members WHERE id = :id")
                .bind("id", java.util.UUID.fromString(memberId))
                .fetch().one()
                .map(row -> {
                    MemberFact m = new MemberFact();
                    m.setMemberId(memberId);
                    m.setStatus(asString(row.get("status")));
                    m.setGender(asString(row.get("gender")));
                    if (row.get("enrollment_date") instanceof LocalDate enroll) {
                        m.setDaysSinceEnrollment((int) ChronoUnit.DAYS.between(enroll, LocalDate.now()));
                    }
                    if (row.get("date_of_birth") instanceof LocalDate dob) {
                        m.setAge((int) ChronoUnit.YEARS.between(dob, LocalDate.now()));
                    }
                    // Other fields (benefitRemaining, arrearsMonths, …) stay at defaults
                    // until upstream stages start populating them. Safe — Drools rules
                    // only fire when their conditions match, so default values don't
                    // produce spurious rejections.
                    return m;
                })
                .defaultIfEmpty(emptyMember(memberId))
                .onErrorResume(err -> {
                    log.debug("[fact-builder] member lookup failed for {}: {}", memberId, err.getMessage());
                    return Mono.just(emptyMember(memberId));
                });
    }

    private MemberFact emptyMember(String memberId) {
        MemberFact m = new MemberFact();
        m.setMemberId(memberId);
        return m;
    }

    // ── ProviderFact ─────────────────────────────────────────────────────────

    private Mono<ProviderFact> fetchProvider(String providerId) {
        return db.sql("SELECT id, status, provider_type FROM public.providers WHERE id = :id")
                .bind("id", java.util.UUID.fromString(providerId))
                .fetch().one()
                .map(row -> {
                    ProviderFact p = new ProviderFact();
                    p.setProviderId(providerId);
                    return p;
                })
                .defaultIfEmpty(emptyProvider(providerId))
                .onErrorResume(err -> {
                    log.debug("[fact-builder] provider lookup failed for {}: {}", providerId, err.getMessage());
                    return Mono.just(emptyProvider(providerId));
                });
    }

    private ProviderFact emptyProvider(String providerId) {
        ProviderFact p = new ProviderFact();
        p.setProviderId(providerId);
        return p;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    /** Small triple so the pipeline can pass all three facts through the chain. */
    public record Facts(ClaimFact claim, MemberFact member, ProviderFact provider) {}
}
