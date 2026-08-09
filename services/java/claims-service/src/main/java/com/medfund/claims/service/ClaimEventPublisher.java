package com.medfund.claims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.Map;

@Service
public class ClaimEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventPublisher.class);

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public ClaimEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publishClaimSubmitted(String claimId, String claimNumber, String memberId,
                                              String insuranceLine) {
        return publishEvent("medfund.claims.submitted", claimId, Map.of(
            "event", "CLAIM_SUBMITTED",
            "claimId", claimId,
            "claimNumber", claimNumber,
            "memberId", memberId,
            "insuranceLine", nz(insuranceLine)
        ));
    }

    /**
     * Emitted when a claim is captured with {@code preVerified=true} —
     * the operator has already confirmed the code out-of-band, so the
     * claim skips the SUBMITTED → VERIFIED transition. Consumers that
     * only care about "ready for adjudication" can listen on this
     * topic plus lifecycle CLAIM_STATUS_CHANGED (status=VERIFIED).
     */
    public Mono<Void> publishClaimCaptured(String claimId, String claimNumber, String memberId,
                                            String insuranceLine) {
        return publishEvent("medfund.claims.captured", claimId, Map.of(
            "event", "CLAIM_CAPTURED",
            "claimId", claimId,
            "claimNumber", claimNumber,
            "memberId", memberId,
            "insuranceLine", nz(insuranceLine)
        ));
    }

    public Mono<Void> publishClaimAdjudicated(String claimId, String claimNumber, String decision,
                                                String providerId, String approvedAmount, String currencyCode,
                                                String insuranceLine,
                                                String memberId, String dependantId,
                                                String benefitId, String policyYear,
                                                String payeeType, String tenantId) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("event", "CLAIM_ADJUDICATED");
        payload.put("claimId", claimId);
        payload.put("claimNumber", claimNumber);
        payload.put("decision", decision);
        payload.put("providerId", providerId != null ? providerId : "");
        payload.put("approvedAmount", approvedAmount != null ? approvedAmount : "0");
        payload.put("currencyCode", currencyCode != null ? currencyCode : "USD");
        payload.put("insuranceLine", nz(insuranceLine));
        // V061: informational fields for downstream consumers (audit,
        // notifications, analytics). The authoritative balance decrement
        // happens synchronously in ClaimService.applyLineDecisions —
        // consumers must NOT decrement the ledger.
        payload.put("memberId",     nz(memberId));
        payload.put("dependantId",  nz(dependantId));
        payload.put("benefitId",    nz(benefitId));
        payload.put("policyYear",   nz(policyYear));
        // V069: payeeType routes downstream ledger updates. MEMBER writes a
        // member_payables row in finance-service; PROVIDER updates the
        // provider_balances row. Empty string preserved for old-shape
        // consumers that don't read the field.
        payload.put("payeeType",    nz(payeeType));
        // V069: tenantId lets consumers switch the R2DBC search_path to the
        // originating tenant schema when they write follow-on rows
        // (TenantAwareConnectionFactory reads TenantContext from the
        // Reactor context). Without it the finance-side member_payables
        // insert lands in public and returns silent errors.
        payload.put("tenantId",     nz(tenantId));
        return publishEvent("medfund.claims.adjudicated", claimId, payload);
    }

    public Mono<Void> publishClaimStatusChanged(String claimId, String status, String insuranceLine) {
        return publishEvent("medfund.claims.lifecycle", claimId, Map.of(
            "event", "CLAIM_STATUS_CHANGED",
            "claimId", claimId,
            "status", status,
            "insuranceLine", nz(insuranceLine)
        ));
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    public Mono<Void> publishPreAuthDecision(String authId, String authNumber, String decision) {
        return publishEvent("medfund.claims.pre-auth-decision", authId, Map.of(
            "event", "PRE_AUTH_DECISION",
            "authId", authId,
            "authNumber", authNumber,
            "decision", decision
        ));
    }

    private Mono<Void> publishEvent(String topic, String key, Map<String, String> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            var record = new ProducerRecord<>(topic, key, json);
            var senderRecord = SenderRecord.create(record, key);
            return kafkaSender.send(Mono.just(senderRecord))
                .doOnError(e -> log.error("Failed to publish event to {}: {}", topic, e.getMessage()))
                .then();
        } catch (Exception e) {
            log.error("Failed to serialize event for {}: {}", topic, e.getMessage());
            return Mono.empty();
        }
    }
}
