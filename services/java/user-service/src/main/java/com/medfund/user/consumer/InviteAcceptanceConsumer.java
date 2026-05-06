package com.medfund.user.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.user.service.StaffUserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * Consumes Keycloak security events from {@code medfund.security.events} and
 * flips a staff user's status from {@code invited} to {@code active} the first
 * time they successfully complete the password-set flow that the invite email
 * directs them to.
 *
 * <p>Errors processing a single record are swallowed so the consumer can keep
 * draining the topic — see {@link TenantProvisionedConsumer} for the same
 * shape and rationale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InviteAcceptanceConsumer {

    private static final String TOPIC = "medfund.security.events";

    /**
     * Keycloak event types that signify the invite recipient has demonstrated
     * control of the account. UPDATE_PASSWORD is the primary signal (set on
     * the password-reset action). VERIFY_EMAIL is treated as equivalent in
     * case the user verifies via the link before setting a password.
     */
    private static final Set<String> ACCEPTANCE_EVENTS = Set.of(
            "UPDATE_PASSWORD",
            "VERIFY_EMAIL"
    );

    private final ReceiverOptions<String, String> receiverOptions;
    private final StaffUserService staffUserService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> processEvent(record.value())
                    .doOnSuccess(v -> record.receiverOffset().acknowledge())
                    .onErrorResume(e -> {
                        log.error("[invite-acceptance] Failed to process record offset={}: {}",
                                record.receiverOffset().offset(), e.getMessage());
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                    .maxBackoff(Duration.ofMinutes(2))
                    .doBeforeRetry(sig -> log.warn("[invite-acceptance] Consumer restarting after error: {}",
                            sig.failure().getMessage())))
            .subscribe();
    }

    private Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String eventType = node.path("eventType").asText("");
            if (!ACCEPTANCE_EVENTS.contains(eventType)) return Mono.empty();

            String keycloakUserId = node.path("userId").asText("");
            if (keycloakUserId.isBlank()) return Mono.empty();

            return staffUserService.markInviteAccepted(keycloakUserId);
        } catch (Exception e) {
            log.error("[invite-acceptance] Failed to parse event payload: {}", e.getMessage());
            return Mono.error(e);
        }
    }
}
