package com.medfund.finance.reinsurance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.reinsurance.dto.ContributionPaidEvent;
import com.medfund.finance.reinsurance.service.PremiumCessionService;
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
 * per-contribution premium cessions to {@link PremiumCessionService}.
 * Sibling shape to {@link ReinsuranceLossCessionConsumer} — same ack-on-
 * success discipline per {@code bug_reactor_kafka_ack_swallow}, same
 * malformed-record swallow-and-log defence against poison pills.
 *
 * <p>Pre-Phase-6 payloads on this topic (contributions-service prior to
 * the additive publisher change) carry no {@code insuranceLine} /
 * {@code currencyCode} / {@code tenantId} — the DTO parser lets those
 * come through as null and the service short-circuits on the tenant /
 * line guard, so the consumer stays safe during a rolling deploy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsurancePremiumCessionConsumer {

    private static final String TOPIC = "medfund.contributions.paid";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PremiumCessionService premiumCessionService;
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
                                        "Failed to process contribution-paid event for reinsurance (full chain): ",
                                        e))
                                .onErrorResume(e -> Mono.empty());
                    } catch (Exception e) {
                        log.error("Error deserializing contribution-paid event for reinsurance: ", e);
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }
                })
                .doOnError(e -> log.error("Reinsurance premium cession consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            ContributionPaidEvent event = ContributionPaidEvent.from(node);
            if (event.contributionId() == null || event.insuranceLine() == null
                    || event.tenantId() == null || event.tenantId().isBlank()) {
                log.debug("Skipping reinsurance-side contribution event — missing contributionId/line/tenant");
                return Mono.empty();
            }

            String[] systemActor = AuditActor.systemActor();
            return premiumCessionService.processPaidContribution(event, systemActor[0], systemActor[1])
                    .then()
                    .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
        } catch (Exception e) {
            log.error("Failed to parse contribution-paid event for reinsurance: ", e);
            return Mono.error(e);
        }
    }
}
