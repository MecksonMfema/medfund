package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for POST /api/v1/scheme-changes.
 *
 * <p>V048: {@code changeKind} is optional — when null the service
 * classifies automatically (UPGRADE / DOWNGRADE / CURRENCY_CHANGE
 * / CROSS_GRADE). Callers with domain knowledge can override the
 * auto-classification by supplying the value explicitly.
 */
public record SchemeChangeRequest(
        @NotNull UUID memberId,
        @NotNull UUID fromSchemeId,
        @NotNull UUID toSchemeId,
        @NotNull LocalDate effectiveDate,
        String reason,
        String changeKind
) {
    /** Backwards-compat constructor for existing callers. */
    public SchemeChangeRequest(UUID memberId, UUID fromSchemeId, UUID toSchemeId,
                                LocalDate effectiveDate, String reason) {
        this(memberId, fromSchemeId, toSchemeId, effectiveDate, reason, null);
    }
}
