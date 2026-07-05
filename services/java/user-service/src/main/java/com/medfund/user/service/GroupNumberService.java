package com.medfund.user.service;

import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.repository.GroupRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates unique group registration numbers per the tenant's
 * configured shape: {@code <prefix><N random digits><suffix>}. The
 * three knobs live on {@code public.tenants} (V125):
 *
 * <ul>
 *   <li>{@code group_number_prefix} — static text prepended to every
 *       number (default {@code "GRP-"}).</li>
 *   <li>{@code group_number_suffix} — static text appended (default
 *       empty).</li>
 *   <li>{@code group_number_random_length} — digit count for the
 *       random block (default 6, constrained [3, 12]).</li>
 * </ul>
 *
 * <p>Uniqueness is enforced two ways: this service probes
 * {@code groups.registration_number} before returning and retries on
 * collision (up to {@code MAX_ATTEMPTS} times), and the tenant-side
 * migration V045 adds a UNIQUE index as a defense-in-depth backstop
 * against the tiny race window between probe and insert.
 *
 * <p>The scheme is loaded from {@code public.tenants} on every call —
 * cheap in practice because group creation is low-frequency and the
 * tenant row is heavily cached. Follows the {@code MemberNumberService}
 * pattern verbatim so a tenant admin switching between the two knobs
 * sees consistent behaviour.
 */
@Slf4j
@Service
public class GroupNumberService {

    /** Default digit count when the tenant column is missing / null. */
    private static final int DEFAULT_RANDOM_LENGTH = 6;

    /** Default prefix when the tenant column is missing / null. */
    private static final String DEFAULT_PREFIX = "GRP-";

    /**
     * Retry ceiling on candidate collisions. Six is generous —
     * with the default 6-digit random block a tenant needs ~1000
     * existing groups before the collision probability exceeds 1% per
     * candidate, and even at 10k groups the probability of six
     * consecutive misses is ~1e-12. If we ever hit this, something
     * more fundamental has gone wrong.
     */
    private static final int MAX_ATTEMPTS = 6;

    private final DatabaseClient db;
    private final GroupRepository groupRepository;

    public GroupNumberService(DatabaseClient db, GroupRepository groupRepository) {
        this.db = db;
        this.groupRepository = groupRepository;
    }

    /**
     * Allocate a unique registration number for a brand-new group.
     * The returned value is guaranteed unique across the tenant's
     * groups table at issue time; the DB UNIQUE index (V045) catches
     * any race-window duplicates as a backstop.
     */
    public Mono<String> nextRegistrationNumber() {
        return loadScheme()
                .flatMap(scheme -> attemptGeneration(scheme, MAX_ATTEMPTS));
    }

    private Mono<String> attemptGeneration(NumberScheme scheme, int attemptsRemaining) {
        if (attemptsRemaining <= 0) {
            // Vanishingly unlikely — logged as an error so ops sees it
            // in the alerting pipeline. Fall back to a wide random tail
            // (12 digits) rather than fail the whole group create;
            // uniqueness is still checked by the DB UNIQUE index.
            String fallback = scheme.prefix()
                    + String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L,
                            999_999_999_999L))
                    + scheme.suffix();
            log.error("[group-number] exhausted {} attempts; returning wide-random fallback {}",
                    MAX_ATTEMPTS, fallback);
            return Mono.just(fallback);
        }
        String candidate = format(scheme);
        return groupRepository.existsByRegistrationNumber(candidate)
                .flatMap(taken -> taken
                        ? attemptGeneration(scheme, attemptsRemaining - 1)
                        : Mono.just(candidate));
    }

    private String format(NumberScheme scheme) {
        return formatCandidate(scheme);
    }

    /**
     * Format a single random candidate for the given scheme.
     * Package-private + static so a targeted unit test can drive the
     * prefix/suffix/length matrix without a Spring context — the
     * pow10 math is where subtle width-off-by-one regressions would
     * land and this is the only seam that surfaces them.
     */
    static String formatCandidate(NumberScheme scheme) {
        int length = scheme.randomLength();
        long min = pow10(length - 1);
        long max = pow10(length) - 1;
        long value = ThreadLocalRandom.current().nextLong(min, max + 1);
        return scheme.prefix() + value + scheme.suffix();
    }

    private static long pow10(int n) {
        long result = 1;
        for (int i = 0; i < n; i++) result *= 10L;
        return result;
    }

    // ── Scheme loader ────────────────────────────────────────────────

    private Mono<NumberScheme> loadScheme() {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null || tenantId.isBlank()) return Mono.just(NumberScheme.defaults());
            UUID tenantUuid;
            try { tenantUuid = UUID.fromString(tenantId); }
            catch (IllegalArgumentException e) { return Mono.just(NumberScheme.defaults()); }
            // NB: no onErrorResume here. GroupService.create is
            // @Transactional, so a SQL error inside this Mono (e.g.
            // "column group_number_prefix does not exist" when V125
            // hasn't been applied) transitions the underlying R2DBC
            // transaction to ABORTED. Swallowing the error with a
            // Mono-level fallback lets the pipeline continue, but the
            // subsequent INSERT then fails with a 25P02
            // in_failed_sql_transaction and surfaces as an opaque 500
            // upstream. Letting the real error propagate up gives the
            // operator a clear "your migration is missing" signal
            // instead. See memory: bug_public_prefix_silent_rollback
            // for the related class of pitfall.
            return db.sql("""
                        SELECT group_number_prefix,
                               group_number_suffix,
                               group_number_random_length
                          FROM public.tenants
                         WHERE id = :id
                        """)
                    .bind("id", tenantUuid)
                    .map(row -> new NumberScheme(
                            nullToDefault(row.get("group_number_prefix", String.class), DEFAULT_PREFIX),
                            nullToEmpty(row.get("group_number_suffix", String.class)),
                            row.get("group_number_random_length", Integer.class) != null
                                    ? row.get("group_number_random_length", Integer.class)
                                    : DEFAULT_RANDOM_LENGTH))
                    .one()
                    // A tenant row without any custom config still
                    // resolves to defaults — that's the legitimate
                    // "fresh tenant" path and stays intact.
                    .defaultIfEmpty(NumberScheme.defaults());
        });
    }

    private static String nullToDefault(String v, String fallback) {
        // Null → fallback (defensive; the column is NOT NULL per V125,
        // but a stale row inserted before the migration might exist).
        // Empty string is a legitimate config for a tenant that wants
        // raw digits with no decoration — respect it explicitly.
        return v == null ? fallback : v;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /**
     * Resolved per-tenant scheme values used by {@link #format}.
     * Package-private so a targeted unit test can construct fixtures
     * without a DB round-trip. Immutable.
     */
    record NumberScheme(String prefix, String suffix, int randomLength) {
        static NumberScheme defaults() {
            return new NumberScheme(DEFAULT_PREFIX, "", DEFAULT_RANDOM_LENGTH);
        }
    }
}
