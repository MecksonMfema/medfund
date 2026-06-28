package com.medfund.user.service;

import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.entity.Member;
import com.medfund.user.repository.DependantRepository;
import com.medfund.user.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates structured member numbers per the tenant's configured
 * issuance scheme (V120 / V036).
 *
 * <ul>
 *   <li>{@code INDEPENDENT} — members get "MBR-XXXXXX", dependants get
 *       "DEP-XXXXXX". Independent unique numbers each.</li>
 *   <li>{@code SHARED_WITH_SUFFIX} — members get "MBR-XXXXXX-01"; the
 *       first dependant gets "MBR-XXXXXX-02", the next "-03", etc.
 *       Suffixes are monotonically increasing — the next-assigned
 *       suffix is {@code MAX(existing) + 1} for that member, so
 *       removing a middle dependant does NOT free up its suffix.</li>
 * </ul>
 *
 * <p>Cross-table uniqueness (a dependant's number can't collide with a
 * member's number, and vice versa) is enforced by OR-ing the existence
 * checks against both {@code members.member_number} and
 * {@code dependants.member_number}. The DB-level UNIQUE indexes guard
 * within each table.
 *
 * <p>Cross-tenant lookup of the scheme: queries {@code public.tenants}
 * explicitly schema-qualified because the connection's search_path is
 * set to the tenant schema by {@code TenantAwareConnectionFactory}.
 */
@Slf4j
@Service
public class MemberNumberService {

    private static final String DEFAULT_SCHEME = "INDEPENDENT";

    private final DatabaseClient db;
    private final MemberRepository memberRepository;
    private final DependantRepository dependantRepository;

    public MemberNumberService(DatabaseClient db,
                                MemberRepository memberRepository,
                                DependantRepository dependantRepository) {
        this.db = db;
        this.memberRepository = memberRepository;
        this.dependantRepository = dependantRepository;
    }

    /**
     * Allocate a unique member_number for a brand-new member. The
     * returned number is guaranteed unique across both members and
     * dependants in the tenant schema (checked at issue time; DB
     * UNIQUE indexes catch any race-window duplicates as a backstop).
     */
    public Mono<String> nextMemberNumber() {
        return loadScheme()
                .flatMap(scheme -> "SHARED_WITH_SUFFIX".equals(scheme)
                        ? generateMemberSharedWithSuffix()
                        : generateMemberIndependent());
    }

    /**
     * Allocate a unique member_number for a new dependant under the
     * given parent member. Behaviour depends on the tenant's scheme:
     *
     * <ul>
     *   <li>INDEPENDENT — "DEP-XXXXXX", independent of the parent.</li>
     *   <li>SHARED_WITH_SUFFIX — parent base + "-NN" where NN is one
     *       greater than the highest suffix currently in use under
     *       this member. Soft-deleted dependants keep their row and
     *       are still counted, so a removed dependant's suffix
     *       cannot be reused.</li>
     * </ul>
     */
    public Mono<String> nextDependantNumber(Member parent) {
        if (parent == null) {
            return Mono.error(new IllegalArgumentException("parent member is required"));
        }
        return loadScheme()
                .flatMap(scheme -> "SHARED_WITH_SUFFIX".equals(scheme)
                        ? generateDependantSharedWithSuffix(parent)
                        : generateDependantIndependent());
    }

    // ── Scheme loader ────────────────────────────────────────────────

    private Mono<String> loadScheme() {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null || tenantId.isBlank()) return Mono.just(DEFAULT_SCHEME);
            UUID tenantUuid;
            try { tenantUuid = UUID.fromString(tenantId); }
            catch (IllegalArgumentException e) { return Mono.just(DEFAULT_SCHEME); }
            // public.tenants is schema-qualified — TenantAwareConnectionFactory
            // sets search_path to the tenant schema, so unqualified "tenants"
            // would miss.
            return db.sql("SELECT member_number_scheme FROM public.tenants WHERE id = :id")
                    .bind("id", tenantUuid)
                    .map(row -> row.get("member_number_scheme", String.class))
                    .one()
                    .defaultIfEmpty(DEFAULT_SCHEME)
                    .onErrorResume(err -> {
                        log.warn("[member-number] scheme lookup failed for tenant {}: {} — using {}",
                                tenantId, err.getMessage(), DEFAULT_SCHEME);
                        return Mono.just(DEFAULT_SCHEME);
                    });
        });
    }

    // ── INDEPENDENT scheme ────────────────────────────────────────────

    private Mono<String> generateMemberIndependent() {
        String candidate = "MBR-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return existsAcross(candidate)
                .flatMap(taken -> taken ? generateMemberIndependent() : Mono.just(candidate));
    }

    private Mono<String> generateDependantIndependent() {
        String candidate = "DEP-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return existsAcross(candidate)
                .flatMap(taken -> taken ? generateDependantIndependent() : Mono.just(candidate));
    }

    // ── SHARED_WITH_SUFFIX scheme ────────────────────────────────────

    private Mono<String> generateMemberSharedWithSuffix() {
        int base = ThreadLocalRandom.current().nextInt(100000, 999999);
        String candidate = String.format("MBR-%06d-01", base);
        return existsAcross(candidate)
                .flatMap(taken -> taken ? generateMemberSharedWithSuffix() : Mono.just(candidate));
    }

    private Mono<String> generateDependantSharedWithSuffix(Member parent) {
        String basePrefix = stripSuffix(parent.getMemberNumber());
        if (basePrefix == null) {
            // Parent has no suffixed member_number (legacy / INDEPENDENT-issued
            // parent that was switched to SHARED_WITH_SUFFIX after creation).
            // Fall back to INDEPENDENT so the dependant still gets a number.
            log.warn("[member-number] parent {} member_number={} lacks SHARED_WITH_SUFFIX base; falling back to INDEPENDENT for dependant",
                    parent.getId(), parent.getMemberNumber());
            return generateDependantIndependent();
        }
        return dependantRepository.maxSuffixForBase(basePrefix)
                .defaultIfEmpty(1)
                .map(maxSuffix -> String.format("%s-%02d", basePrefix, maxSuffix + 1));
    }

    /**
     * Strip the trailing {@code -NN} from a member_number to get the
     * base portion used for sibling-dependant lookups. Returns null
     * when the input doesn't match the suffix pattern (legacy /
     * INDEPENDENT-issued numbers).
     */
    private static String stripSuffix(String memberNumber) {
        if (memberNumber == null) return null;
        int idx = memberNumber.lastIndexOf('-');
        if (idx <= 0) return null;
        String tail = memberNumber.substring(idx + 1);
        if (tail.length() != 2 || !tail.chars().allMatch(Character::isDigit)) return null;
        return memberNumber.substring(0, idx);
    }

    // ── Cross-table uniqueness ───────────────────────────────────────

    private Mono<Boolean> existsAcross(String memberNumber) {
        return memberRepository.existsByMemberNumber(memberNumber)
                .flatMap(inMembers -> inMembers
                        ? Mono.just(true)
                        : dependantRepository.existsByMemberNumber(memberNumber));
    }
}
