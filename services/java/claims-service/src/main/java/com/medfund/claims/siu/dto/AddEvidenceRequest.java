package com.medfund.claims.siu.dto;

/**
 * Payload for {@code POST /api/v1/siu/cases/{caseId}/evidence} — the
 * caller passes a file-service handle previously obtained by uploading
 * bytes to the file service, plus a description + one of the allowed
 * evidence-type strings.
 *
 * <ul>
 *   <li>{@code fileServiceRef} — opaque URI/handle owned by file-service.</li>
 *   <li>{@code description}    — investigator narrative, required non-blank.</li>
 *   <li>{@code evidenceType}   — one of DOCUMENT | PHOTO | PROVIDER_RECORD |
 *       MEMBER_RECORD | OTHER (matches the DB CHECK constraint).</li>
 * </ul>
 */
public record AddEvidenceRequest(
        String fileServiceRef,
        String description,
        String evidenceType
) {
}
