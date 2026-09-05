package com.medfund.finance.report.schedule.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduledDownloadTokenIssuerTest {

    private static final String SECRET = "s3cret-forever";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void issue_producesThreeSegmentToken() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "cfo@acme.com");
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void issue_payloadCarriesClaimsAndExp() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        String token = issuer.issue(jobId, tenantId, "cfo@acme.com");
        String[] parts = token.split("\\.");
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        JsonNode node = mapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
        assertThat(node.path("jobId").asText()).isEqualTo(jobId.toString());
        assertThat(node.path("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(node.path("recipientEmail").asText()).isEqualTo("cfo@acme.com");
        long expected = fixedClock.instant().plusSeconds(7L * 86_400).getEpochSecond();
        assertThat(node.path("exp").asLong()).isEqualTo(expected);
    }

    @Test
    void issue_headerIsSdltHS256() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "r@x.io");
        String headerJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]),
                StandardCharsets.UTF_8);
        assertThat(headerJson).isEqualTo("{\"typ\":\"SDLT\",\"alg\":\"HS256\"}");
    }

    @Test
    void issue_deterministicWithFixedClock() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        UUID jobId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String first = issuer.issue(jobId, tenantId, "r@x.io");
        String second = issuer.issue(jobId, tenantId, "r@x.io");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void issue_differentRecipientsProduceDifferentSignatures() {
        var issuer = new ScheduledDownloadTokenIssuer(SECRET, 7, mapper, fixedClock);
        UUID jobId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String a = issuer.issue(jobId, tenantId, "a@x.io");
        String b = issuer.issue(jobId, tenantId, "b@x.io");
        assertThat(a).isNotEqualTo(b);
        assertThat(a.split("\\.")[2]).isNotEqualTo(b.split("\\.")[2]);
    }

    @Test
    void issue_emptySecretThrows() {
        var issuer = new ScheduledDownloadTokenIssuer("", 7, mapper, fixedClock);
        assertThatThrownBy(() -> issuer.issue(UUID.randomUUID(), UUID.randomUUID(), "r@x.io"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isConfigured_reflectsSecretPresence() {
        assertThat(new ScheduledDownloadTokenIssuer("", 7, mapper, fixedClock).isConfigured()).isFalse();
        assertThat(new ScheduledDownloadTokenIssuer("k", 7, mapper, fixedClock).isConfigured()).isTrue();
    }
}
