package com.medfund.finance.regulatory.dto;

import com.medfund.finance.regulatory.entity.RegulatorySubmission;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Metadata-only response — the XLSX blob is fetched via the download endpoint. */
public record RegulatorySubmissionResponse(
        UUID id,
        UUID tenantId,
        String reportKey,
        LocalDate periodStart,
        LocalDate periodEnd,
        int submissionNumber,
        UUID supersedesId,
        UUID sourceRunId,
        OffsetDateTime submittedAt,
        UUID submittedByActorId,
        String submittedByActorEmail,
        long xlsxSizeBytes,
        String xlsxContentHash,
        String filingRef,
        String status,
        String attestationNote,
        String reasonNote,
        OffsetDateTime createdAt
) {
    public static RegulatorySubmissionResponse from(RegulatorySubmission r) {
        return new RegulatorySubmissionResponse(
                r.getId(),
                r.getTenantId(),
                r.getReportKey(),
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getSubmissionNumber() != null ? r.getSubmissionNumber() : 0,
                r.getSupersedesId(),
                r.getSourceRunId(),
                r.getSubmittedAt(),
                r.getSubmittedByActorId(),
                r.getSubmittedByActorEmail(),
                r.getXlsxSizeBytes() != null ? r.getXlsxSizeBytes() : 0L,
                r.getXlsxContentHash(),
                r.getFilingRef(),
                r.getStatus(),
                r.getAttestationNote(),
                r.getReasonNote(),
                r.getCreatedAt()
        );
    }
}
