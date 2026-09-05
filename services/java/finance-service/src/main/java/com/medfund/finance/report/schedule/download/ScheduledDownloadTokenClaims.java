package com.medfund.finance.report.schedule.download;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 17 §B.2 — verified claims lifted from a scheduled-report download
 * token. Minted by notification-service's Go {@code SignedURLBuilder} (see
 * {@code services/go/notification-service/internal/report/signed_url.go})
 * or by {@link ScheduledDownloadTokenIssuer} — both use the shared HMAC
 * secret {@code SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET}.
 */
public record ScheduledDownloadTokenClaims(
        UUID jobId,
        UUID tenantId,
        String recipientEmail,
        Instant expiresAt) {
}
