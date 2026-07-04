package com.medfund.user.dto;

import com.medfund.user.entity.Member;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MemberResponse(
    UUID id,
    String memberNumber,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String gender,
    String nationalId,
    String email,
    String phone,
    String address,
    UUID groupId,
    UUID schemeId,
    String keycloakUserId,
    String status,
    LocalDate enrollmentDate,
    LocalDate terminationDate,
    /** Non-null when the member has a future-dated status change queued
     *  (V042 scheduled trio). Cleared when the SCHEDULED_STATUS_ROLL
     *  job applies it. */
    String scheduledStatus,
    LocalDate scheduledStatusEffectiveFrom,
    String scheduledStatusReason,
    BigDecimal billingOverrideAmount,
    String billingOverrideReason,
    LocalDate billingOverrideEffectiveFrom,
    Instant createdAt,
    Instant updatedAt
) {
    public static MemberResponse from(Member m) {
        return new MemberResponse(
            m.getId(), m.getMemberNumber(), m.getFirstName(), m.getLastName(),
            m.getDateOfBirth(), m.getGender(), m.getNationalId(),
            m.getEmail(), m.getPhone(), m.getAddress(),
            m.getGroupId(), m.getSchemeId(), m.getKeycloakUserId(),
            m.getStatus(), m.getEnrollmentDate(), m.getTerminationDate(),
            m.getScheduledStatus(),
            m.getScheduledStatusEffectiveFrom(),
            m.getScheduledStatusReason(),
            m.getBillingOverrideAmount(),
            m.getBillingOverrideReason(),
            m.getBillingOverrideEffectiveFrom(),
            m.getCreatedAt(), m.getUpdatedAt()
        );
    }
}
