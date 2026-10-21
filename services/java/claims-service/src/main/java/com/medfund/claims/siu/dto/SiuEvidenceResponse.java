package com.medfund.claims.siu.dto;

import com.medfund.claims.siu.entity.SiuEvidence;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SiuEvidenceResponse(
        UUID id,
        UUID caseId,
        String fileServiceRef,
        String description,
        String evidenceType,
        UUID uploadedBy,
        String uploadedByEmail,
        OffsetDateTime uploadedAt
) {
    public static SiuEvidenceResponse from(SiuEvidence ev) {
        return new SiuEvidenceResponse(
                ev.getId(),
                ev.getCaseId(),
                ev.getFileServiceRef(),
                ev.getDescription(),
                ev.getEvidenceType(),
                ev.getUploadedBy(),
                ev.getUploadedByEmail(),
                ev.getUploadedAt()
        );
    }
}
