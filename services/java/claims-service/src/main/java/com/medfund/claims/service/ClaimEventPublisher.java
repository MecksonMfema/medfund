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
                                                String insuranceLine) {
        return publishEvent("medfund.claims.adjudicated", claimId, Map.of(
            "event", "CLAIM_ADJUDICATED",
            "claimId", claimId,
            "claimNumber", claimNumber,
            "decision", decision,
            "providerId", providerId != null ? providerId : "",
            "approvedAmount", approvedAmount != null ? approvedAmount : "0",
            "currencyCode", currencyCode != null ? currencyCode : "USD",
            "insuranceLine", nz(insuranceLine)
        ));
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
