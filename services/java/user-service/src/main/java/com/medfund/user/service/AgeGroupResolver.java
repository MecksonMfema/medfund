package com.medfund.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * Resolves the canonical age-group bucket a person sits in, based on their
 * scheme's age bands and their age on a reference date.
 *
 * <p>Age is computed against {@code today} by default — that's the value
 * stored at enrolment ("they joined as an Adult"). Billing reads the
 * stored bucket; it does not recompute. A separate maintenance job is
 * responsible for moving the stored bucket forward when a person crosses
 * a boundary.
 *
 * <p>Returns {@link Mono#empty()} when no band on the scheme covers the
 * person's age — the caller should treat that as a data-quality issue
 * (gap in the scheme's age bands) rather than silently defaulting to
 * the youngest or oldest bucket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgeGroupResolver {

    private final DatabaseClient db;

    /** Resolve against today's date. The signup-time entrypoint. */
    public Mono<UUID> resolveForSchemeAndDob(UUID schemeId, LocalDate dateOfBirth) {
        return resolveForSchemeAndDob(schemeId, dateOfBirth, LocalDate.now());
    }

    /**
     * Resolve against an arbitrary reference date — used by the maintenance
     * job that re-evaluates everyone as they age forward.
     */
    public Mono<UUID> resolveForSchemeAndDob(UUID schemeId, LocalDate dateOfBirth, LocalDate asOf) {
        if (schemeId == null || dateOfBirth == null) {
            return Mono.empty();
        }
        int age = Period.between(dateOfBirth, asOf).getYears();
        return db.sql("""
                    SELECT id FROM age_groups
                     WHERE scheme_id = :schemeId
                       AND :age BETWEEN min_age AND max_age
                     ORDER BY min_age
                     LIMIT 1
                """)
                .bind("schemeId", schemeId)
                .bind("age", age)
                .map(row -> row.get("id", UUID.class))
                .one()
                .doOnSuccess(id -> {
                    if (id == null) {
                        log.warn("[age-group-resolver] no band covers age {} on scheme {} — caller should treat as data gap",
                                age, schemeId);
                    }
                })
                .onErrorResume(err -> {
                    log.warn("[age-group-resolver] lookup failed for scheme {} dob {}: {}",
                            schemeId, dateOfBirth, err.getMessage());
                    return Mono.empty();
                });
    }
}
