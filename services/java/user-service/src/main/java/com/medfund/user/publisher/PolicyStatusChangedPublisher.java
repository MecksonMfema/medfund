package com.medfund.user.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Phase 13 §B per L6: publishes {@code medfund.user.policy-status-changed}
 * events whenever an annual-bind policy transitions status through the
 * uniform {@link com.medfund.user.status.PolicyStatusActionController} surface.
 *
 * <p>Consumed by contributions-service {@code PolicyStatusChangedConsumer}
 * (Phase 13 §B Phase 6) to close / freeze / resume / reinstate the affected
 * {@code earning_schedule} rows and keep UPR correct.
 *
 * <p>Wire shape matches {@link com.medfund.user.endorsement.service.PolicyEndorsedPublisher}
 * and {@link com.medfund.user.service.PolicyIssuedPublisher}: a flat
 * {@code Map<String,String>} body over {@link KafkaSender}; nullable fields
 * serialise as empty strings so downstream can treat every payload as
 * {@code Map<String,String>} without null-checks.
 *
 * <p>Partitioning key is {@code policyId} so events for the same policy stay
 * ordered on the same partition — the consumer's earning-schedule state
 * machine relies on seeing {@code lapsed} before a later {@code active}.
 */
@Slf4j
@Component
public class PolicyStatusChangedPublisher {

    public static final String TOPIC = "medfund.user.policy-status-changed";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public PolicyStatusChangedPublisher(KafkaSender<String, String> kafkaSender,
                                        ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(String tenantId, UUID policyId, String policySource,
                              String insuranceLine, String fromStatus, String toStatus,
                              OffsetDateTime effectiveAt, String reasonCode,
                              String actorId, String actorEmail) {
        var body = new LinkedHashMap<String, String>();
        body.put("event", "POLICY_STATUS_CHANGED");
        body.put("tenantId", tenantId != null ? tenantId : "");
        body.put("policyId", policyId.toString());
        body.put("policySource", policySource);
        body.put("insuranceLine", insuranceLine != null ? insuranceLine : "");
        body.put("fromStatus", fromStatus != null ? fromStatus : "");
        body.put("toStatus", toStatus);
        body.put("effectiveAt", effectiveAt != null ? effectiveAt.toString() : "");
        body.put("reasonCode", reasonCode != null ? reasonCode : "");
        body.put("actorId", actorId != null ? actorId : "");
        body.put("actorEmail", actorEmail != null ? actorEmail : "");
        try {
            String json = objectMapper.writeValueAsString(body);
            var record = new ProducerRecord<>(TOPIC, policyId.toString(), json);
            var senderRecord = SenderRecord.create(record, policyId.toString());
            return kafkaSender.send(Mono.just(senderRecord))
                    .doOnError(e -> log.error(
                            "Failed to publish policy-status-changed for {} ({}→{}): {}",
                            policyId, fromStatus, toStatus, e.getMessage(), e))
                    .then();
        } catch (Exception e) {
            log.error("Failed to serialize policy-status-changed for {} ({}→{}): {}",
                    policyId, fromStatus, toStatus, e.getMessage(), e);
            return Mono.empty();
        }
    }
}
