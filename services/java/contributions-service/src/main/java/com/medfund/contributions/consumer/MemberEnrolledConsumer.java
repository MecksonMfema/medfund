package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import java.util.Collections;

/**
 * Listens for member-enrolled events. The historical handler tried to
 * insert a placeholder {@code contributions} row immediately on enrollment,
 * but the table requires {@code scheme_id}, {@code amount},
 * {@code period_start}, and {@code period_end} (all NOT NULL) — none of
 * which the event carries. The {@code BILLING_CYCLE} scheduled job creates
 * the real row on the next billing date, so the consumer just logs the
 * enrolment and acks the offset.
 */
@Component
public class MemberEnrolledConsumer {

    private static final Logger log = LoggerFactory.getLogger(MemberEnrolledConsumer.class);
    private static final String TOPIC = "medfund.users.member-enrolled";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;

    public MemberEnrolledConsumer(ReceiverOptions<String, String> receiverOptions,
                                  ObjectMapper objectMapper) {
        this.receiverOptions = receiverOptions;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> processEvent(record.value())
                .doOnTerminate(() -> record.receiverOffset().acknowledge()))
            .doOnError(e -> log.error("Member enrolled consumer error: {}", e.getMessage()))
            .retry()
            .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String memberId = node.get("memberId").asText();
            String memberNumber = node.get("memberNumber").asText();
            String groupId = node.has("groupId") ? node.get("groupId").asText() : null;
            log.info("Member enrolled — billing cycle will pick up at next scheduled run: memberId={}, memberNumber={}, groupId={}",
                memberId, memberNumber, groupId);
            return Mono.empty();
        } catch (Exception e) {
            log.error("Failed to parse member enrolled event: {}", e.getMessage());
            return Mono.error(e);
        }
    }
}
