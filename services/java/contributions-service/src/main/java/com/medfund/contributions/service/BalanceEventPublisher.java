package com.medfund.contributions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceEventPublisher {

    private static final String MEMBER_TOPIC = "medfund.balances.member-updated";
    private static final String GROUP_TOPIC  = "medfund.balances.group-updated";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publishMemberBalance(UUID memberId, String currency, BigDecimal newBalance,
                                           String reason, Instant timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "MEMBER_BALANCE_UPDATED");
        payload.put("memberId", memberId.toString());
        payload.put("currencyCode", currency);
        payload.put("balance", newBalance != null ? newBalance.toPlainString() : "0");
        payload.put("reason", reason);
        payload.put("timestamp", timestamp.toString());
        return publish(MEMBER_TOPIC, memberId.toString(), payload);
    }

    public Mono<Void> publishGroupBalance(UUID groupId, String currency, BigDecimal newBalance,
                                          String reason, Instant timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "GROUP_BALANCE_UPDATED");
        payload.put("groupId", groupId.toString());
        payload.put("currencyCode", currency);
        payload.put("balance", newBalance != null ? newBalance.toPlainString() : "0");
        payload.put("reason", reason);
        payload.put("timestamp", timestamp.toString());
        return publish(GROUP_TOPIC, groupId.toString(), payload);
    }

    private Mono<Void> publish(String topic, String key, Map<String, Object> payload) {
        try {
            String value = objectMapper.writeValueAsString(payload);
            var record = new ProducerRecord<>(topic, key, value);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .doOnError(e -> log.error("Failed to publish balance event to {}: {}", topic, e.getMessage()))
                    .then();
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
