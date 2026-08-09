package com.medfund.finance.util;

import io.r2dbc.spi.R2dbcException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Predicates for the R2DBC / Spring exceptions we care about at the
 * consumer layer, where we translate DB errors to idempotent no-ops or
 * bounded retries. Kept as a util (not a Spring bean) — pure functions.
 */
public final class DbErrors {

    /** PostgreSQL {@code 23505 unique_violation}. */
    public static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    private DbErrors() {}

    /**
     * True when the throwable chain represents a unique-constraint violation.
     * Callers use this to turn a duplicate-insert into a Mono.empty() no-op
     * — the row already exists so the effect is already realised.
     */
    public static boolean isUniqueViolation(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof DuplicateKeyException) return true;
            if (cur instanceof R2dbcException r
                    && UNIQUE_VIOLATION_SQLSTATE.equals(r.getSqlState())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
