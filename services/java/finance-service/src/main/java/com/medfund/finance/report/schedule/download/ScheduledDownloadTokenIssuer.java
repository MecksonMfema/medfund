package com.medfund.finance.report.schedule.download;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 17 §B.2 — mints HMAC-signed download tokens for scheduled-report
 * XLSX links embedded in delivery emails. Matches the Go
 * {@code SignedURLBuilder} scheme exactly so tokens minted on either side
 * verify on the other:
 * <pre>
 *   token   = base64url(header) . base64url(payload) . base64url(sig)
 *   header  = {"typ":"SDLT","alg":"HS256"}
 *   payload = {"jobId":..,"tenantId":..,"recipientEmail":..,"exp":..}
 *   sig     = HMAC-SHA256(secret, header + "." + payload)
 * </pre>
 * Empty secret leaves the issuer dormant — the caller degrades the email
 * to an "attachment only" body rather than shipping a broken link.
 */
@Slf4j
@Component
public class ScheduledDownloadTokenIssuer {

    static final String HEADER_JSON = "{\"typ\":\"SDLT\",\"alg\":\"HS256\"}";
    static final String HMAC_ALGO = "HmacSHA256";

    private final String secret;
    private final int expiryDays;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScheduledDownloadTokenIssuer(
            @Value("${scheduled.report.download.token-secret:}") String secret,
            @Value("${scheduled.report.download.expiry-days:7}") int expiryDays,
            ObjectMapper objectMapper,
            Clock clock) {
        this.secret = secret != null ? secret : "";
        this.expiryDays = expiryDays;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return !secret.isEmpty();
    }

    public String issue(UUID jobId, UUID tenantId, String recipientEmail) {
        if (secret.isEmpty()) {
            throw new IllegalStateException(
                    "scheduled.report.download.token-secret is not configured");
        }
        Instant expiresAt = Instant.now(clock).plus(expiryDays, ChronoUnit.DAYS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId.toString());
        payload.put("tenantId", tenantId.toString());
        payload.put("recipientEmail", recipientEmail != null ? recipientEmail : "");
        payload.put("exp", expiresAt.getEpochSecond());

        String headerB64 = b64u(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String payloadB64;
        try {
            payloadB64 = b64u(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise download token payload", e);
        }
        String sigB64 = b64u(hmacSha256(headerB64 + "." + payloadB64, secret));
        return headerB64 + "." + payloadB64 + "." + sigB64;
    }

    static byte[] hmacSha256(String message, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        } catch (java.security.InvalidKeyException e) {
            throw new IllegalStateException("Invalid HMAC secret", e);
        }
    }

    static String b64u(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
