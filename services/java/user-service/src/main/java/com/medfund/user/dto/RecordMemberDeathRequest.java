package com.medfund.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body for {@code POST /api/v1/members/{id}/record-death} (actuarial Phase 5).
 * {@code causeOfDeath} is optional: it accepts an ICD-10 chapter code
 * (e.g. {@code "I00-I99"} for circulatory diseases) or free text — the
 * MORTALITY_STUDY groups on the chapter code when present and treats
 * free-text rows as {@code unclassified}.
 */
public record RecordMemberDeathRequest(
        @NotNull LocalDate deathDate,
        @Size(max = 80) String causeOfDeath
) {}
