package com.medfund.claims.dto;

/**
 * Metadata for a document attached to a claim. Kept intentionally
 * skinny — filename, MIME type, and byte size are enough to render the
 * capture confirmation UI and to build the storage key later. Actual
 * bytes are held out-of-band; the storage integration wires them in
 * once file-service is switched off its MockStorage backend.
 */
public record ClaimAttachment(
        String filename,
        String contentType,
        Long sizeBytes
) {}
