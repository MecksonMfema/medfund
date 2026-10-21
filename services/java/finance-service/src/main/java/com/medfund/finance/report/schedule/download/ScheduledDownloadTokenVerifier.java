package com.medfund.finance.report.schedule.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 17 §B.2 — verifies HMAC-signed download tokens for scheduled-report
 * links. Rejects on missing/empty secret, malformed token, signature mismatch,
 * or expired {@code exp} claim. Returns the decoded claims on success.
 *
 * <p>Signature comparison uses {@link MessageDigest#isEqual} for constant-time
 * matching so an attacker can't timing-oracle the HMAC.
 */
@Slf4j
@Component
public class ScheduledDownloadTokenVerifier {

    private final String secret;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScheduledDownloadTokenVerifier(
            @Value("${scheduled.report.download.token-secret:}") String secret,
            ObjectMapper objectMapper,
            Clock clock) {
        this.secret = secret != null ? secret : "";
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<ScheduledDownloadTokenClaims> verify(String token) {
        if (secret.isEmpty()) {
            log.warn("[scheduled-download] verify called but token-secret unset");
            return Optional.empty();
        }
        if (token == null || token.isBlank()) return Optional.empty();

        String[] parts = token.split("\\.");
        if (parts.length != 3) return Optional.empty();

        byte[] expectedSig = ScheduledDownloadTokenIssuer.hmacSha256(
                parts[0] + "." + parts[1], secret);
        byte[] providedSig;
        try {
            providedSig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            return Optional.empty();
        }

        JsonNode payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            payload = objectMapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }

        long exp = payload.path("exp").asLong(0);
        if (exp <= 0) return Optional.empty();
        Instant expiresAt = Instant.ofEpochSecond(exp);
        if (Instant.now(clock).isAfter(expiresAt)) return Optional.empty();

        UUID jobId, tenantId;
        try {
            jobId = UUID.fromString(payload.path("jobId").asText());
            tenantId = UUID.fromString(payload.path("tenantId").asText());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String recipientEmail = payload.path("recipientEmail").asText("");
        return Optional.of(new ScheduledDownloadTokenClaims(
                jobId, tenantId, recipientEmail, expiresAt));
    }
}
