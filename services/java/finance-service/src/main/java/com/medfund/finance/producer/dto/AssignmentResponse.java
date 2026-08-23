package com.medfund.finance.producer.dto;

import com.medfund.finance.producer.entity.MemberProducerAssignment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AssignmentResponse(
        UUID id,
        UUID memberId,
        UUID producerId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String changeReason,
        OffsetDateTime createdAt
) {
    public static AssignmentResponse from(MemberProducerAssignment m) {
        return new AssignmentResponse(
                m.getId(), m.getMemberId(), m.getProducerId(),
                m.getEffectiveFrom(), m.getEffectiveTo(), m.getChangeReason(),
                m.getCreatedAt());
    }
}
