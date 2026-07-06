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
 * issuance scheme (V120) and shape (V126).
 *
 * <ul>
 *   <li>{@code INDEPENDENT} — members and dependants get independently
 *       generated numbers keyed on the tenant's member/dependant
 *       prefixes and configured random-digit length.</li>
 *   <li>{@code SHARED_WITH_SUFFIX} — members get a
 *       {@code <memberPrefix><random><separator><suffix>} number; the
 *       first dependant inherits the parent's base and gets the next
 *       suffix. Suffixes are monotonically increasing — the next-
 *       assigned suffix is {@code MAX(existing) + 1} for that member,
 *       so removing a middle dependant does NOT free up its suffix.</li>
 * </ul>
 *
 * <p>Cross-table uniqueness (a dependant's number can't collide with a
 * member's number, and vice versa) is enforced by OR-ing the existence
 * checks against both {@code members.member_number} and
 * {@code dependants.member_number}. The DB-level UNIQUE indexes guard
 * within each table.
 *
 * <p>Cross-tenant lookup of the config: queries {@code public.tenants}
 * explicitly schema-qualified because the connection's search_path is
 * set to the tenant schema by {@code TenantAwareConnectionFactory}.
 */
@Slf4j
@Service
public class MemberNumberService {

    /**
     * Fallback used when no tenant is in context or the config lookup
     * fails. Matches the byte-for-byte defaults that
     * V126 stamps on every tenant row so a fallback path in production
     * behaves the same as a happy-path lookup.
     */
    static final MemberNumberConfig DEFAULT_CONFIG = new MemberNumberConfig(
            "INDEPENDENT", "MBR-", "DEP-", 6, "-", 2, 1);

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
        return loadConfig()
                .flatMap(cfg -> "SHARED_WITH_SUFFIX".equals(cfg.scheme())
                        ? generateMemberSharedWithSuffix(cfg)
                        : generateMemberIndependent(cfg));
    }

    /**
     * Allocate a unique member_number for a new dependant under the
     * given parent member. Behaviour depends on the tenant's scheme:
     *
     * <ul>
     *   <li>INDEPENDENT — {@code <dependantPrefix><random>}, independent
     *       of the parent.</li>
     *   <li>SHARED_WITH_SUFFIX — parent base + separator + suffix
     *       (zero-padded) where the suffix is one greater than the
     *       highest suffix currently in use under this member.
     *       Soft-deleted dependants keep their row and are still
     *       counted, so a removed dependant's suffix cannot be
     *       reused.</li>
     * </ul>
     */
    public Mono<String> nextDependantNumber(Member parent) {
        if (parent == null) {
            return Mono.error(new IllegalArgumentException("parent member is required"));
        }
        return loadConfig()
                .flatMap(cfg -> "SHARED_WITH_SUFFIX".equals(cfg.scheme())
                        ? generateDependantSharedWithSuffix(parent, cfg)
                        : generateDependantIndependent(cfg));
    }

    // ── Config loader ────────────────────────────────────────────────

    Mono<MemberNumberConfig> loadConfig() {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null || tenantId.isBlank()) return Mono.just(DEFAULT_CONFIG);
            UUID tenantUuid;
            try { tenantUuid = UUID.fromString(tenantId); }
            catch (IllegalArgumentException e) { return Mono.just(DEFAULT_CONFIG); }
            // public.tenants is schema-qualified — TenantAwareConnectionFactory
            // sets search_path to the tenant schema, so unqualified "tenants"
            // would miss.
            return db.sql("""
                    SELECT member_number_scheme,
                           member_number_prefix,
                           dependant_number_prefix,
                           member_number_random_length,
                           member_number_suffix_separator,
                           member_number_suffix_padding,
                           member_number_suffix_start
                      FROM public.tenants WHERE id = :id
                    """)
                    .bind("id", tenantUuid)
                    .map(row -> new MemberNumberConfig(
                            defaultString(row.get("member_number_scheme", String.class),
                                    DEFAULT_CONFIG.scheme()),
                            defaultString(row.get("member_number_prefix", String.class),
                                    DEFAULT_CONFIG.memberPrefix()),
                            defaultString(row.get("dependant_number_prefix", String.class),
                                    DEFAULT_CONFIG.dependantPrefix()),
                            defaultInt(row.get("member_number_random_length", Integer.class),
                                    DEFAULT_CONFIG.randomLength()),
                            defaultString(row.get("member_number_suffix_separator", String.class),
                                    DEFAULT_CONFIG.suffixSeparator()),
                            defaultInt(row.get("member_number_suffix_padding", Integer.class),
                                    DEFAULT_CONFIG.suffixPadding()),
                            defaultInt(row.get("member_number_suffix_start", Integer.class),
                                    DEFAULT_CONFIG.suffixStart())))
                    .one()
                    .defaultIfEmpty(DEFAULT_CONFIG)
                    .onErrorResume(err -> {
                        log.warn("[member-number] config lookup failed for tenant {}: {} — using defaults",
                                tenantId, err.getMessage());
                        return Mono.just(DEFAULT_CONFIG);
                    });
        });
    }

    private static String defaultString(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static int defaultInt(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    // ── INDEPENDENT scheme ────────────────────────────────────────────

    private Mono<String> generateMemberIndependent(MemberNumberConfig cfg) {
        String candidate = cfg.memberPrefix() + randomBlock(cfg.randomLength());
        return existsAcross(candidate)
                .flatMap(taken -> taken ? generateMemberIndependent(cfg) : Mono.just(candidate));
    }

    private Mono<String> generateDependantIndependent(MemberNumberConfig cfg) {
        String candidate = cfg.dependantPrefix() + randomBlock(cfg.randomLength());
        return existsAcross(candidate)
                .flatMap(taken -> taken ? generateDependantIndependent(cfg) : Mono.just(candidate));
    }

    // ── SHARED_WITH_SUFFIX scheme ────────────────────────────────────

    private Mono<String> generateMemberSharedWithSuffix(MemberNumberConfig cfg) {
        String base = cfg.memberPrefix() + randomBlock(cfg.randomLength());
        String candidate = base + cfg.suffixSeparator()
                + padSuffix(cfg.suffixStart(), cfg.suffixPadding());
        return existsAcross(candidate)
                .flatMap(taken -> taken ? generateMemberSharedWithSuffix(cfg) : Mono.just(candidate));
    }

    private Mono<String> generateDependantSharedWithSuffix(Member parent, MemberNumberConfig cfg) {
        String basePrefix = stripSuffix(parent.getMemberNumber(), cfg);
        if (basePrefix == null) {
            // Parent has no suffixed member_number (legacy / INDEPENDENT-issued
            // parent that was switched to SHARED_WITH_SUFFIX after creation).
            // Fall back to INDEPENDENT so the dependant still gets a number.
            log.warn("[member-number] parent {} member_number={} lacks SHARED_WITH_SUFFIX base; falling back to INDEPENDENT for dependant",
                    parent.getId(), parent.getMemberNumber());
            return generateDependantIndependent(cfg);
        }
        return dependantRepository.maxSuffixForBase(basePrefix)
                .defaultIfEmpty(cfg.suffixStart())
                .map(maxSuffix -> basePrefix + cfg.suffixSeparator()
                        + padSuffix(maxSuffix + 1, cfg.suffixPadding()));
    }

    /**
     * Uniform random integer in {@code [10^(N-1), 10^N)} — printed
     * width is always exactly N digits (no leading-zero-loss). Mirrors
     * V125's semantics for group numbers.
     */
    static String randomBlock(int length) {
        long lower = pow10(length - 1);
        long upper = pow10(length);
        return String.valueOf(ThreadLocalRandom.current().nextLong(lower, upper));
    }

    private static long pow10(int n) {
        long v = 1;
        for (int i = 0; i < n; i++) v *= 10;
        return v;
    }

    private static String padSuffix(int suffix, int padding) {
        return String.format("%0" + padding + "d", suffix);
    }

    /**
     * Strip the trailing {@code <separator><NN>} from a member_number
     * to get the base portion used for sibling-dependant lookups.
     * Returns null when the input doesn't match the suffix pattern
     * (legacy / INDEPENDENT-issued numbers).
     */
    static String stripSuffix(String memberNumber, MemberNumberConfig cfg) {
        if (memberNumber == null) return null;
        String sep = cfg.suffixSeparator();
        int idx = memberNumber.lastIndexOf(sep);
        if (idx <= 0) return null;
        String tail = memberNumber.substring(idx + sep.length());
        if (tail.length() != cfg.suffixPadding()) return null;
        if (!tail.chars().allMatch(Character::isDigit)) return null;
        return memberNumber.substring(0, idx);
    }

    // ── Cross-table uniqueness ───────────────────────────────────────

    private Mono<Boolean> existsAcross(String memberNumber) {
        return memberRepository.existsByMemberNumber(memberNumber)
                .flatMap(inMembers -> inMembers
                        ? Mono.just(true)
                        : dependantRepository.existsByMemberNumber(memberNumber));
    }

    /**
     * Per-tenant issuance config resolved from V120 (scheme) + V126
     * (shape). Fields are non-null; the loader coerces DB nulls to
     * {@link #DEFAULT_CONFIG} values so downstream code doesn't need
     * to null-guard.
     */
    public record MemberNumberConfig(
            String scheme,
            String memberPrefix,
            String dependantPrefix,
            int randomLength,
            String suffixSeparator,
            int suffixPadding,
            int suffixStart
    ) {}
}
