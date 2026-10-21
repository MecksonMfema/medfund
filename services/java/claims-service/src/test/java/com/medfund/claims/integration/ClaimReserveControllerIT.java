package com.medfund.claims.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.claims.service.ClaimReserveHistoryService;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 14 §A — end-to-end HTTP tests for the claim reserve endpoint.
 *
 * <p>Covers set → history round-trip, audit event emission on the
 * {@code medfund.audit.events} topic (entityName = "reserve:<claimNumber>"
 * per {@code feedback_audit_entity_name}), validation guards, and the
 * ClaimReserveHistoryService.autoZero() service-side entrypoint used by
 * ClaimService when a claim closes to REJECTED / CANCELLED.
 */
@WithTenant(AbstractClaimsReportIT.TENANT_ID)
class ClaimReserveControllerIT extends AbstractClaimsReportIT {

    private static final String AUDIT_TOPIC = "medfund.audit.events";
    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private ClaimReserveHistoryService reserveHistoryService;

    private UUID seedSimpleClaim(String status) {
        UUID s = seedScheme("Clinical");
        UUID p = seedProvider("Provider A");
        UUID m = seedMember("Ada", "Lovelace", seedGroup("Corporate"), s);
        return seedClaim(m, p, s, status, "USD",
                new BigDecimal("500.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"),
                TODAY.minusDays(3), TODAY.minusDays(3), null);
    }

    @Test
    void set_writesRow_emitsAuditEvent_andSurfacesInHistory() {
        UUID claimId = seedSimpleClaim("submitted");

        // POST /reserve
        JsonNode created = client.post()
                .uri("/api/v1/claims/{id}/reserve", claimId)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "reservedAmount", "250.00",
                        "reasonNote", "Initial estimate on new claim"
                ))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();

        assertNotNull(created);
        assertEquals(claimId.toString(), created.get("claimId").asText());
        assertDecimal("250.00", created.get("reservedAmount"));
        assertEquals("reports-it@medfund.example", created.get("actorEmail").asText());

        // Audit event lands on medfund.audit.events with the friendly entityName
        JsonNode audit = consumeAuditEvent(AUDIT_TOPIC, "CLAIM_RESERVE_HISTORY",
                Duration.ofSeconds(15));
        assertNotNull(audit, "expected CLAIM_RESERVE_HISTORY audit event on " + AUDIT_TOPIC);
        assertEquals("CREATE", audit.path("action").asText());
        String entityName = audit.path("entityName").asText();
        assertTrue(entityName.startsWith("reserve:"),
                "entityName must be human-friendly per feedback_audit_entity_name, got: " + entityName);

        // GET /reserve/history — one row, our just-created value
        JsonNode history = getJson("/api/v1/claims/" + claimId + "/reserve/history");
        assertEquals(1, history.size());
        assertDecimal("250.00", history.get(0).get("reservedAmount"));
        assertEquals("Initial estimate on new claim", history.get(0).get("reasonNote").asText());
    }

    @Test
    void set_twice_returnsHistoryNewestFirst() {
        UUID claimId = seedSimpleClaim("submitted");

        client.post()
                .uri("/api/v1/claims/{id}/reserve", claimId)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reservedAmount", "100.00", "reasonNote", "first estimate"))
                .exchange().expectStatus().isCreated();

        // Small delay so the two rows have distinguishable effective_at
        // values (Postgres NOW() resolution is microseconds — usually fine,
        // but a 5ms sleep hardens the newest-first ordering assertion).
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        client.post()
                .uri("/api/v1/claims/{id}/reserve", claimId)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reservedAmount", "175.50", "reasonNote", "revised after triage"))
                .exchange().expectStatus().isCreated();

        JsonNode history = getJson("/api/v1/claims/" + claimId + "/reserve/history");
        assertEquals(2, history.size());
        assertDecimal("175.50", history.get(0).get("reservedAmount"));
        assertDecimal("100.00", history.get(1).get("reservedAmount"));
    }

    @Test
    void set_negativeAmount_returns400() {
        UUID claimId = seedSimpleClaim("submitted");

        client.post()
                .uri("/api/v1/claims/{id}/reserve", claimId)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reservedAmount", "-1.00", "reasonNote", "invalid entry test"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void set_reasonNoteTooShort_returns400() {
        UUID claimId = seedSimpleClaim("submitted");

        client.post()
                .uri("/api/v1/claims/{id}/reserve", claimId)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reservedAmount", "10.00", "reasonNote", "x"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void set_unknownClaimId_returns404() {
        UUID unknown = UUID.randomUUID();

        client.post()
                .uri("/api/v1/claims/{id}/reserve", unknown)
                .header("X-Tenant-ID", TENANT_ID)
                .headers(h -> h.setBearerAuth("it-token"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reservedAmount", "50.00", "reasonNote", "unknown claim test"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void autoZero_appendsZeroRowWithCanonicalReason() {
        UUID claimId = seedSimpleClaim("submitted");

        // Seed a non-zero reserve first so the auto-zero flip is observable.
        reserveHistoryService.set(claimId, new BigDecimal("300.00"),
                        "estimate before rejection",
                        "it-user", "reports-it@medfund.example")
                .contextWrite(com.medfund.shared.testfixtures.TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        // Simulate the ClaimService reject cascade — same call the hook makes.
        reserveHistoryService.autoZero(claimId, "REJECTED",
                        "it-user", "reports-it@medfund.example")
                .contextWrite(com.medfund.shared.testfixtures.TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        JsonNode history = getJson("/api/v1/claims/" + claimId + "/reserve/history");
        assertEquals(2, history.size());
        assertDecimal("0.00", history.get(0).get("reservedAmount"));
        assertEquals("Auto-zero: claim REJECTED", history.get(0).get("reasonNote").asText());
        assertDecimal("300.00", history.get(1).get("reservedAmount"));
    }

    /** Fallback for tests that need to read multiple audit events in one poll. */
    @SuppressWarnings("unused")
    private List<JsonNode> allReserveAuditEvents(Duration timeout) {
        // Not currently used but left here so the next-phase author has a
        // template if they want to count events per claim.
        return List.of();
    }
}
