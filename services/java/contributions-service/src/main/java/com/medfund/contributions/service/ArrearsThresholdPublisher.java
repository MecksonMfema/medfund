package com.medfund.contributions.service;

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

/**
 * Publishes state-transition events on the aged-balance ladder for the
 * auto-lapse chain (Phase 11 §B). Two topics, both consumed by user-service:
 *
 * <ul>
 *   <li>{@code medfund.contributions.arrears-threshold-breached} — emitted
 *   by {@link com.medfund.contributions.job.ArrearsEscalationExecutor}
 *   when a subject crosses into the {@code SUSPENDED} (or {@code WRITE_OFF})
 *   bucket. Downstream: {@code ArrearsBreachedConsumer} schedules a
 *   {@code LAPSED} member status after the tenant-configured grace window.
 *   </li>
 *   <li>{@code medfund.contributions.arrears-cleared} — emitted by
 *   {@link BillingService#recordPayment} when a payment drops the subject
 *   from {@code SUSPENDED} / {@code WRITE_OFF} back to {@code GRACE}.
 *   Downstream: {@code ArrearsClearedConsumer} cancels the pending
 *   scheduled lapse if the effective date hasn't passed yet.</li>
 * </ul>
 *
 * <p>Fire-and-forget; failures are logged but never fail the caller. The
 * escalation job runs daily and re-emits the same breach next tick if the
 * consumer missed it — idempotency lives in the consumer.
 *
 * <p>Sibling of {@link ArrearsNoticePublisher} which pushes reminder emails;
 * this publisher owns the state-transition Kafka contract that user-service
 * consumes to drive the auto-lapse pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsThresholdPublisher {

    public static final String TOPIC_BREACHED = "medfund.contributions.arrears-threshold-breached";
    public static final String TOPIC_CLEARED  = "medfund.contributions.arrears-cleared";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    /**
     * @param tenantId       string form of the tenant UUID, propagated so the
     *                       consumer can populate {@link com.medfund.shared.tenant.TenantContext}
     * @param subjectType    {@code "MEMBER"} or {@code "GROUP"}
     * @param subjectId      string form of the aged subject id
     * @param arrearsMonths  coarse months-in-arrears at breach time (used for
     *                       audit + reporting; consumer does not gate on it)
     * @param balance        current outstanding, positive amount
     * @param currencyCode   ISO 4217
     */
    public Mono<Void> publishBreached(String tenantId, String subjectType, String subjectId,
                                       int arrearsMonths, BigDecimal balance, String currencyCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event",         "ARREARS_THRESHOLD_BREACHED");
        fields.put("tenantId",      nullSafe(tenantId));
        fields.put("subjectType",   subjectType);
        fields.put("subjectId",     subjectId);
        fields.put("arrearsMonths", String.valueOf(arrearsMonths));
        fields.put("balance",       moneyDisplay(balance));
        fields.put("currencyCode",  nullSafe(currencyCode));
        fields.put("breachedAt",    Instant.now().toString());
        return send(TOPIC_BREACHED, subjectId, fields);
    }

    public Mono<Void> publishCleared(String tenantId, String subjectType, String subjectId,
                                      BigDecimal balance, String currencyCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event",        "ARREARS_CLEARED");
        fields.put("tenantId",     nullSafe(tenantId));
        fields.put("subjectType",  subjectType);
        fields.put("subjectId",    subjectId);
        fields.put("balance",      moneyDisplay(balance));
        fields.put("currencyCode", nullSafe(currencyCode));
        fields.put("clearedAt",    Instant.now().toString());
        return send(TOPIC_CLEARED, subjectId, fields);
    }

    private Mono<Void> send(String topic, String key, Map<String, String> fields) {
        try {
            String json = objectMapper.writeValueAsString(fields);
            var record = new ProducerRecord<>(topic, key, json);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .doOnError(e -> log.warn("Failed to publish {} for subject {}: {}",
                            topic, key, e.getMessage()))
                    .then();
        } catch (Exception e) {
            log.warn("Failed to serialize {} for subject {}: {}",
                    topic, key, e.getMessage());
            return Mono.empty();
        }
    }

    private static String moneyDisplay(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String nullSafe(String v) { return v == null ? "" : v; }
}
