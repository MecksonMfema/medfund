package com.medfund.user.dto;

import com.medfund.user.entity.Dependant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DependantResponse(
    UUID id,
    UUID memberId,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String gender,
    String relationship,
    String nationalId,
    String status,
    /** Present iff status = 'deactivated'. Billing stops from the
     *  cycle AFTER this date. See {@link Dependant#getDeactivationEffectiveDate}. */
    LocalDate deactivationEffectiveDate,
    /** Effective start-of-cover date (V047). Always the 1st of a month. */
    LocalDate enrollmentDate,
    Instant createdAt,
    Instant updatedAt
) {
    public static DependantResponse from(Dependant d) {
        return new DependantResponse(
            d.getId(), d.getMemberId(), d.getFirstName(), d.getLastName(),
            d.getDateOfBirth(), d.getGender(), d.getRelationship(),
            d.getNationalId(), d.getStatus(),
            d.getDeactivationEffectiveDate(),
            d.getEnrollmentDate(),
            d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}
