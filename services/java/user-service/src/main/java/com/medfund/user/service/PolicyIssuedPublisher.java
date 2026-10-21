package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Publishes {@code medfund.user.policy-issued} events at policy bind time so
 * contributions-service {@code PolicyIssuedConsumer} (Phase 12 §A Phase 5) can
 * project one or more {@code earning_schedule} rows per policy.
 *
 * <p>Fires from the six annual-bind services on {@code create()} and on
 * {@code update()} when the {@code writtenPremium} changes materially. Skips
 * emit when {@code writtenPremium} is null — the row is a
 * {@code LEGACY_NO_PREMIUM} backfill placeholder that the retrofit UI must
 * populate before it starts earning.
 *
 * <p>Uses the same {@code KafkaSender}/{@code ObjectMapper} plumbing as
 * {@link UserEventPublisher} to keep the wire shape uniform; nullable fields
 * serialize as empty strings so consumers can treat every payload as
 * {@code Map<String,String>}.
 */
@Slf4j
@Component
public class PolicyIssuedPublisher {

    public static final String TOPIC = "medfund.user.policy-issued";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public PolicyIssuedPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(PolicyIssuedPayload payload) {
        if (payload.writtenPremium() == null) {
            // LEGACY_NO_PREMIUM placeholder — retrofit UI populates later and re-fires.
            return Mono.empty();
        }
        var body = new LinkedHashMap<String, String>();
        body.put("event", "POLICY_ISSUED");
        body.put("tenantId", payload.tenantId() != null ? payload.tenantId() : "");
        body.put("policyId", payload.policyId().toString());
        body.put("policySource", payload.policySource());
        body.put("insuranceLine", payload.insuranceLine());
        body.put("policyNumber", payload.policyNumber() != null ? payload.policyNumber() : "");
        body.put("writtenPremium", payload.writtenPremium().toPlainString());
        body.put("currencyCode", payload.currencyCode() != null ? payload.currencyCode() : "");
        body.put("coverageStart", payload.coverageStart() != null ? payload.coverageStart().toString() : "");
        body.put("coverageEnd", payload.coverageEnd() != null ? payload.coverageEnd().toString() : "");
        body.put("boundAt", payload.boundAt() != null ? payload.boundAt().toString() : "");
        body.put("memberId", payload.memberId() != null ? payload.memberId().toString() : "");
        body.put("portfolioId", payload.portfolioId() != null ? payload.portfolioId().toString() : "");
        body.put("cohortId", payload.cohortId() != null ? payload.cohortId().toString() : "");
        body.put("renewedFromPolicyId",
                payload.renewedFromPolicyId() != null ? payload.renewedFromPolicyId().toString() : "");
        try {
            String json = objectMapper.writeValueAsString(body);
            var record = new ProducerRecord<>(TOPIC, payload.policyId().toString(), json);
            var senderRecord = SenderRecord.create(record, payload.policyId().toString());
            return kafkaSender.send(Mono.just(senderRecord))
                    .doOnError(e -> log.error("Failed to publish policy-issued for {}: {}",
                            payload.policyId(), e.getMessage(), e))
                    .then();
        } catch (Exception e) {
            log.error("Failed to serialize policy-issued for {}: {}",
                    payload.policyId(), e.getMessage(), e);
            return Mono.empty();
        }
    }

    public record PolicyIssuedPayload(
            String tenantId,
            UUID policyId,
            String policyNumber,
            String policySource,
            String insuranceLine,
            BigDecimal writtenPremium,
            String currencyCode,
            LocalDate coverageStart,
            LocalDate coverageEnd,
            Instant boundAt,
            UUID memberId,
            UUID portfolioId,
            UUID cohortId,
            UUID renewedFromPolicyId
    ) {}
}
