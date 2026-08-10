package com.medfund.finance.dto;

import com.medfund.finance.entity.Note;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record NoteResponse(
        UUID id,
        String noteNumber,
        UUID providerId,
        UUID memberId,
        String direction,
        String noteType,
        String type,
        UUID reversesNoteId,
        BigDecimal amount,
        String currencyCode,
        String reason,
        String status,
        UUID approvedBy,
        Instant approvedAt,
        Instant postedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy
) {
    public static NoteResponse from(Note n) {
        return new NoteResponse(
                n.getId(),
                n.getNoteNumber(),
                n.getProviderId(),
                n.getMemberId(),
                n.getDirection(),
                n.getNoteType(),
                n.getType(),
                n.getReversesNoteId(),
                n.getAmount(),
                n.getCurrencyCode(),
                n.getReason(),
                n.getStatus(),
                n.getApprovedBy(),
                n.getApprovedAt(),
                n.getPostedAt(),
                n.getCreatedAt(),
                n.getUpdatedAt(),
                n.getCreatedBy()
        );
    }
}
