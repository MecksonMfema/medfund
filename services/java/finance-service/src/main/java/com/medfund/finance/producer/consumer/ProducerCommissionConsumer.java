package com.medfund.finance.producer.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.service.CommissionCalcService;
import com.medfund.shared.audit.AuditActor;
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
 * Kafka consumer on {@code medfund.contributions.paid} that dispatches
 * per-contribution commission accruals to {@link CommissionCalcService}.
 * Sibling shape to
 * {@link com.medfund.finance.reinsurance.consumer.ReinsurancePremiumCessionConsumer}
 * — same ack-on-success discipline per {@code bug_reactor_kafka_ack_swallow},
 * same malformed-record swallow-and-log defence against poison pills.
 *
 * <p>The pre-Phase-6 payload (missing insuranceLine / currencyCode / tenantId)
 * flows through as null via {@link ContributionPaidEvent#from}; the service
 * layer short-circuits any event that's missing the fields it needs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProducerCommissionConsumer {

    private static final String TOPIC = "medfund.contributions.paid";

    private final ReceiverOptions<String, String> receiverOptions;
    private final CommissionCalcService commissionCalcService;
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
                                        "Failed to process contribution-paid event for commission (full chain): ",
                                        e))
                                .onErrorResume(e -> {
                                    // Ack anyway so we don't loop on a poison pill; the
                                    // service is idempotent so a re-delivery on the
                                    // next event replay wouldn't corrupt state.
                                    record.receiverOffset().acknowledge();
                                    return Mono.empty();
                                });
                    } catch (Exception e) {
                        log.error("Error deserializing contribution-paid event for commission: ", e);
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }
                })
                .doOnError(e -> log.error("Commission accrual consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String eventType = node.path("event").asText();
            if (!"CONTRIBUTION_PAID".equals(eventType)) {
                log.debug("Skipping commission accrual — unexpected event type '{}'", eventType);
                return Mono.empty();
            }
            ContributionPaidEvent event = ContributionPaidEvent.from(node);
            if (event.contributionId() == null || event.tenantId() == null
                    || event.tenantId().isBlank()) {
                log.debug("Skipping commission accrual — missing contribution or tenant id");
                return Mono.empty();
            }
            String[] systemActor = AuditActor.systemActor();
            return commissionCalcService.processPaidContribution(event, systemActor[0], systemActor[1])
                    .then()
                    .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
        } catch (Exception e) {
            log.error("Failed to parse contribution-paid event for commission: ", e);
            return Mono.error(e);
        }
    }
}
