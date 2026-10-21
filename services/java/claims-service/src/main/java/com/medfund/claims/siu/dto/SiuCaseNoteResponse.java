package com.medfund.claims.siu.dto;

import com.medfund.claims.siu.entity.SiuCaseNote;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SiuCaseNoteResponse(
        UUID id,
        UUID caseId,
        UUID authorId,
        String authorEmail,
        String noteType,
        String body,
        OffsetDateTime createdAt
) {
    public static SiuCaseNoteResponse from(SiuCaseNote note) {
        return new SiuCaseNoteResponse(
                note.getId(),
                note.getCaseId(),
                note.getAuthorId(),
                note.getAuthorEmail(),
                note.getNoteType(),
                note.getBody(),
                note.getCreatedAt()
        );
    }
}
