package com.medfund.user.endorsement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.user.endorsement.entity.Endorsement;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.LinkedHashMap;

/**
 * Publishes {@code medfund.user.policy-endorsed} events on endorsement
 * COMMIT so contributions-service {@code PolicyEndorsedConsumer} (Phase 12
 * §C Phase 9) can trigger the retro earning-schedule recompute.
 *
 * <p>Wire shape matches {@link com.medfund.user.service.PolicyIssuedPublisher}:
 * flat {@code Map<String,String>} body over {@link KafkaSender}, empty
 * strings for null optionals so the consumer never sees {@code null}.
 */
@Slf4j
@Component
public class PolicyEndorsedPublisher {

    public static final String TOPIC = "medfund.user.policy-endorsed";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public PolicyEndorsedPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(String tenantId, Endorsement endorsement) {
        var body = new LinkedHashMap<String, String>();
        body.put("event", "POLICY_ENDORSED");
        body.put("tenantId", tenantId != null ? tenantId : "");
        body.put("endorsementId", endorsement.getId().toString());
        body.put("reference", endorsement.getReference());
        body.put("policyId", endorsement.getPolicyId().toString());
        body.put("policySource", endorsement.getPolicySource());
        body.put("insuranceLine", endorsement.getInsuranceLine());
        body.put("changeType", endorsement.getChangeType());
        body.put("effectiveFrom", endorsement.getEffectiveFrom().toString());
        body.put("premiumDelta",
                endorsement.getPremiumDelta() != null ? endorsement.getPremiumDelta().toPlainString() : "");
        body.put("currencyCode",
                endorsement.getCurrencyCode() != null ? endorsement.getCurrencyCode() : "");
        body.put("committedAt",
                endorsement.getCommitAt() != null ? endorsement.getCommitAt().toString() : "");
        try {
            String json = objectMapper.writeValueAsString(body);
            var record = new ProducerRecord<>(TOPIC, endorsement.getPolicyId().toString(), json);
            var senderRecord = SenderRecord.create(record, endorsement.getId().toString());
            return kafkaSender.send(Mono.just(senderRecord))
                    .doOnError(e -> log.error("Failed to publish policy-endorsed for {} (endorsement={}): {}",
                            endorsement.getPolicyId(), endorsement.getReference(), e.getMessage(), e))
                    .then();
        } catch (Exception e) {
            log.error("Failed to serialize policy-endorsed for {} (endorsement={}): {}",
                    endorsement.getPolicyId(), endorsement.getReference(), e.getMessage(), e);
            return Mono.empty();
        }
    }
}
