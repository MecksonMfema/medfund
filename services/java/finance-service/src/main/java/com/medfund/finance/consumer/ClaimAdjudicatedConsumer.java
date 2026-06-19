package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.service.ProviderBalanceService;
import com.medfund.shared.audit.AuditActor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

@Component
public class ClaimAdjudicatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClaimAdjudicatedConsumer.class);
    private static final String TOPIC = "medfund.claims.adjudicated";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ProviderBalanceService providerBalanceService;
    private final ObjectMapper objectMapper;

    public ClaimAdjudicatedConsumer(ReceiverOptions<String, String> receiverOptions,
                                    ProviderBalanceService providerBalanceService,
                                    ObjectMapper objectMapper) {
        this.receiverOptions = receiverOptions;
        this.providerBalanceService = providerBalanceService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> {
                try {
                    return processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .doOnError(e -> log.error("Failed to process claim adjudicated event: {}", e.getMessage()));
                } catch (Exception e) {
                    log.error("Error deserializing claim adjudicated event: {}", e.getMessage());
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }
            })
            .doOnError(e -> log.error("Claim adjudicated consumer error: {}", e.getMessage()))
            .retry()
            .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String decision = node.get("decision").asText();

            // Pull common fields. providerId / currency / claimedAmount /
            // approvedAmount may be missing for some decisions; treat null
            // as "no delta" rather than failing the whole event.
            String providerId = textOrNull(node, "providerId");
            String currencyCode = textOrNull(node, "currencyCode");
            String claimedAmount = textOrNull(node, "claimedAmount");
            String approvedAmount = textOrNull(node, "approvedAmount");

            if (providerId == null || currencyCode == null) {
                log.info("Skipping claim adjudicated event without provider context: decision={}", decision);
                return Mono.empty();
            }

            BigDecimal claimedDelta = claimedAmount != null ? new BigDecimal(claimedAmount) : null;
            BigDecimal approvedDelta = null;
            BigDecimal paidDelta = null;

            switch (decision == null ? "" : decision.toUpperCase()) {
                case "APPROVED":
                case "PARTIAL_APPROVED":
                    // Approved amount lifts both totalClaimed (we saw the claim)
                    // and totalApproved (we owe this much). outstandingBalance
                    // is recomputed from approved - paid downstream.
                    if (approvedAmount != null) approvedDelta = new BigDecimal(approvedAmount);
                    break;
                case "REJECTED":
                    // Rejection still counts toward the provider's totalClaimed
                    // (they sent us a claim) but adds nothing to approved/paid.
                    // Without this branch the provider's claim history would
                    // under-count rejections.
                    break;
                case "COMMITTED":
                case "PAID":
                    // Payment-run flow fires these. claim.adjudicated may not
                    // be the right topic for them in steady state, but we
                    // tolerate them here so the consumer doesn't drop work
                    // if upstream multiplexes events.
                    if (approvedAmount != null) paidDelta = new BigDecimal(approvedAmount);
                    break;
                default:
                    log.info("Skipping claim adjudicated event with decision={}", decision);
                    return Mono.empty();
            }

            log.info("Processing claim adjudicated event: decision={}, provider={}, currency={}, " +
                            "claimedDelta={}, approvedDelta={}, paidDelta={}",
                     decision, providerId, currencyCode, claimedDelta, approvedDelta, paidDelta);

            return providerBalanceService.updateBalance(
                UUID.fromString(providerId),
                currencyCode,
                claimedDelta,
                approvedDelta,
                paidDelta,
                AuditActor.SYSTEM_ID,
                AuditActor.SYSTEM_EMAIL
            ).then();
        } catch (Exception e) {
            log.error("Failed to parse claim adjudicated event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
