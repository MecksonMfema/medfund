package com.medfund.finance.producer.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.producer.dto.ContributionRevokedEvent;
import com.medfund.finance.producer.service.CommissionClawbackService;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.util.Collections;

/**
 * Kafka consumer on {@code medfund.contributions.revoked} that fires a
 * commission clawback for the revoked contribution. Same ack-on-success
 * discipline as {@link ProducerCommissionConsumer} —
 * {@link org.springframework.dao.DuplicateKeyException} on replay is caught
 * inside {@link CommissionClawbackService} and treated as success.
 *
 * <p>The topic itself is added by contributions-service Phase 3 §A; during a
 * rolling deploy where contributions-service ships the publisher before
 * finance-service subscribes here, events queue up in Kafka and get
 * processed as soon as this consumer comes online. No back-fill needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionRevokeConsumer {

    private static final String TOPIC = "medfund.contributions.revoked";

    private final ReceiverOptions<String, String> receiverOptions;
    private final CommissionClawbackService commissionClawbackService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> {
                    try {
                        return processEvent(record.value())
                                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                                .doOnError(e -> log.error(
                                        "Failed to process contribution-revoked event for commission (full chain): ",
                                        e))
                                .onErrorResume(e -> {
                                    record.receiverOffset().acknowledge();
                                    return Mono.empty();
                                });
                    } catch (Exception e) {
                        log.error("Error deserializing contribution-revoked event: ", e);
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }
                })
                .doOnError(e -> log.error("Commission revoke consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String eventType = node.path("event").asText();
            if (!"CONTRIBUTION_REVOKED".equals(eventType)) {
                log.debug("Skipping revoke clawback — unexpected event type '{}'", eventType);
                return Mono.empty();
            }
            ContributionRevokedEvent event = ContributionRevokedEvent.from(node);
            if (event.contributionId() == null || event.tenantId() == null
                    || event.tenantId().isBlank()) {
                log.debug("Skipping revoke clawback — missing contribution or tenant id");
                return Mono.empty();
            }
            return commissionClawbackService.processContributionRevoke(
                            event.contributionId(),
                            event.memberId(),
                            event.revokedAt() != null ? event.revokedAt().toInstant() : null,
                            "Contribution revoked",
                            event.actorId(), event.actorEmail())
                    .then()
                    .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
        } catch (Exception e) {
            log.error("Failed to parse contribution-revoked event: ", e);
            return Mono.error(e);
        }
    }
}
