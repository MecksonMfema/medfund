package com.medfund.claims.service;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.rules.fact.ClaimDetailFact;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
    private final ClaimLineRepository claimLineRepository;

    public ClaimFactBuilder(DatabaseClient db, ClaimLineRepository claimLineRepository) {
        this.db = db;
        this.claimLineRepository = claimLineRepository;
    }

    public Mono<Facts> build(Claim claim) {
        ClaimFact claimFact = toClaimFact(claim);

        Mono<MemberFact> memberFact = claim.getMemberId() == null
                ? Mono.just(new MemberFact())
                : fetchMember(claim.getMemberId().toString());

        Mono<ProviderFact> providerFact = claim.getProviderId() == null
                ? Mono.just(emptyProvider(null))
                : fetchProvider(claim.getProviderId().toString());

        // ClaimLine → ClaimDetailFact list, so MODIFIER_ADJUSTMENT (and any
        // other future per-line category) has facts to fire against. Empty
        // list — not null — when the claim has no lines, so Drools rules can
        // safely iterate without a null check.
        Mono<List<ClaimDetailFact>> detailFacts = claim.getId() == null
                ? Mono.just(List.<ClaimDetailFact>of())
                : claimLineRepository.findByClaimId(claim.getId())
                        .collectList()
                        .map(ClaimFactBuilder::toDetailFacts)
                        .onErrorResume(err -> {
                            log.debug("[fact-builder] claim-line lookup failed for {}: {}",
                                    claim.getId(), err.getMessage());
                            return Mono.just(List.<ClaimDetailFact>of());
                        });

        return Mono.zip(memberFact, providerFact, detailFacts)
                .map(t -> {
                    claimFact.setDetails(t.getT3());
                    return new Facts(claimFact, t.getT1(), t.getT2());
                });
    }

    /**
     * Build a {@link ClaimDetailFact} per {@link ClaimLine}, preserving the
     * order the caller supplied. Exposed as a public static so callers who
     * already hold the lines in memory (e.g. {@code ClaimService.applyLineDecisions})
     * can reuse the same conversion without re-querying the DB.
     *
     * <p>Rank: 1-based position in the input list. Modifiers: parsed from
     * the comma-delimited {@code modifier_codes} column, trimmed, blanks
     * dropped. approvedAmount is seeded to the {@code claimedAmount} so a
     * rule can multiply/reduce it — with no matching rule the value flows
     * through unchanged, matching pre-modifier behaviour.
     */
    public static List<ClaimDetailFact> toDetailFacts(List<ClaimLine> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<ClaimDetailFact> facts = new ArrayList<>(lines.size());
        int rank = 1;
        for (ClaimLine line : lines) {
            ClaimDetailFact d = new ClaimDetailFact();
            d.setTariffCode(line.getTariffCode());
            d.setBilledAmount(line.getClaimedAmount());
            d.setModifiers(parseModifiers(line.getModifierCodes()));
            d.setProcedureRank(rank++);
            // approvedAmount seeded to billed so pass-through with no rule
            // hit yields the same amount adjudication would have picked anyway.
            d.setApprovedAmount(line.getClaimedAmount());
            facts.add(d);
        }
        return facts;
    }

    /** Split a comma-delimited modifier column into an immutable list. */
    static List<String> parseModifiers(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
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
        java.util.UUID memberUuid = java.util.UUID.fromString(memberId);
        Mono<MemberFact> baseMono = db.sql("SELECT status, enrollment_date, date_of_birth, gender FROM members WHERE id = :id")
                .bind("id", memberUuid)
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

        return baseMono.flatMap(m -> latestSchemeChangeDate(memberUuid)
                .map(effective -> {
                    m.setDaysSinceSchemeChange((int) ChronoUnit.DAYS.between(effective, LocalDate.now()));
                    return m;
                })
                .defaultIfEmpty(m)
                .onErrorResume(err -> {
                    log.debug("[fact-builder] scheme-change lookup failed for {}: {}", memberId, err.getMessage());
                    return Mono.just(m);
                }));
    }

    /** Most recent EFFECTIVE scheme change on or before today. Empty when member is on their first scheme. */
    private Mono<LocalDate> latestSchemeChangeDate(java.util.UUID memberId) {
        return db.sql("""
                SELECT effective_date
                  FROM scheme_changes
                 WHERE member_id = :id
                   AND status = 'EFFECTIVE'
                   AND effective_date <= CURRENT_DATE
                 ORDER BY effective_date DESC
                 LIMIT 1
                """)
                .bind("id", memberId)
                .map(row -> row.get("effective_date", LocalDate.class))
                .one();
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
