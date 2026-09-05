package com.medfund.finance.report.schedule.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledDownloadTokenVerifierTest {

    private static final String SECRET = "s3cret-forever";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-01T00:00:00Z");
    private final Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void verify_roundTripsIssuedTokens() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, fixedClock);
        UUID jobId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String token = issuer.issue(jobId, tenantId, "cfo@acme.com");

        Optional<ScheduledDownloadTokenClaims> claims = verifier.verify(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().jobId()).isEqualTo(jobId);
        assertThat(claims.get().tenantId()).isEqualTo(tenantId);
        assertThat(claims.get().recipientEmail()).isEqualTo("cfo@acme.com");
        assertThat(claims.get().expiresAt().getEpochSecond())
                .isEqualTo(now.plusSeconds(7L * 86_400).getEpochSecond());
    }

    @Test
    void verify_rejectsTamperedSignature() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, fixedClock);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "r@x.io");
        String[] parts = token.split("\\.");
        // Mutate the leading char of the signature so the decoded bytes
        // provably differ. (Mutating the tail char of a base64url string
        // can be a no-op — the last char carries only the trailing 4 bits
        // for HMAC-SHA256's 32-byte output, so many substitutions decode
        // to the same signature bytes.)
        char lead = parts[2].charAt(0);
        char replacement = (lead == 'A') ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);
        assertThat(verifier.verify(tampered)).isEmpty();
    }

    @Test
    void verify_rejectsSignatureFromDifferentSecret() {
        var attacker = new ScheduledDownloadTokenIssuer("other-secret", 7, mapper, fixedClock);
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, fixedClock);
        String token = attacker.issue(UUID.randomUUID(), UUID.randomUUID(), "r@x.io");
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void verify_rejectsExpiredToken() {
        var pastClock = Clock.fixed(now.minusSeconds(30L * 86_400), ZoneOffset.UTC);
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, pastClock);
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, fixedClock);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "r@x.io");
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void verify_rejectsMalformedTokens() {
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, fixedClock);
        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify("only-one-segment")).isEmpty();
        assertThat(verifier.verify("two.parts")).isEmpty();
        assertThat(verifier.verify("aaa.bbb.ccc.ddd")).isEmpty();
    }

    @Test
    void verify_rejectsWhenSecretUnset() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        var verifier = new ScheduledDownloadTokenVerifier("", mapper, fixedClock);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "r@x.io");
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void verify_rejectsPayloadWithMissingClaims() {
        // Build a token by hand whose payload lacks jobId/tenantId.
        String header = ScheduledDownloadTokenIssuer.b64u(
                "{\"typ\":\"SDLT\",\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = ScheduledDownloadTokenIssuer.b64u(
                ("{\"exp\":" + now.plusSeconds(3600).getEpochSecond() + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sig = ScheduledDownloadTokenIssuer.b64u(
                ScheduledDownloadTokenIssuer.hmacSha256(header + "." + payload, SECRET));
        String token = header + "." + payload + "." + sig;
        var verifier = new ScheduledDownloadTokenVerifier(SECRET, mapper, fixedClock);
        assertThat(verifier.verify(token)).isEmpty();
    }
}
