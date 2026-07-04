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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes arrears-notification events for {@code notification-service}
 * to consume. Three kinds, all sharing the same wire shape so a single
 * consumer + template handles them:
 *
 * <ul>
 *   <li>{@code REMINDER_PRE_SUSPENSION} — cadence-driven reminder in the
 *   window between {@code suspension_days - reminder_lead_days} and the
 *   day the row would actually be suspended. Payer still active.</li>
 *   <li>{@code REMINDER_PRE_DEACTIVATION} — cadence-driven reminder AFTER
 *   the row is suspended and before it hits the deactivation threshold
 *   (only fires when {@code reminder_continue_past_suspension} is on).</li>
 *   <li>{@code SUSPENDED_NOTICE} / {@code DEACTIVATED_NOTICE} — fired at
 *   the moment the executor flips the status. One-shot per transition.</li>
 * </ul>
 *
 * <p>Fire-and-forget; failures logged. The arrears escalator's own
 * status flip is what matters — a missed email is recoverable, a
 * doubly-flipped balance is not.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsNoticePublisher {

    public static final String TOPIC = "medfund.contributions.arrears-notice";

    public static final String KIND_REMINDER_PRE_SUSPENSION   = "REMINDER_PRE_SUSPENSION";
    public static final String KIND_REMINDER_PRE_DEACTIVATION = "REMINDER_PRE_DEACTIVATION";
    public static final String KIND_SUSPENDED_NOTICE          = "SUSPENDED_NOTICE";
    public static final String KIND_DEACTIVATED_NOTICE        = "DEACTIVATED_NOTICE";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(ArrearsNoticePayload p) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", "ARREARS_NOTICE");
        fields.put("kind", p.kind());
        fields.put("tenantId", nullSafe(p.tenantId()));
        fields.put("subjectType", p.subjectType());
        fields.put("subjectId", p.subjectId());
        fields.put("currencyCode", p.currencyCode());
        fields.put("balance", moneyDisplay(p.balance()));
        fields.put("daysOverdue", String.valueOf(p.daysOverdue()));
        fields.put("daysUntilNextStep", p.daysUntilNextStep() != null ? String.valueOf(p.daysUntilNextStep()) : "");
        fields.put("nextStep", p.nextStep() != null ? p.nextStep() : "");
        try {
            String json = objectMapper.writeValueAsString(fields);
            String key = p.subjectId() + ":" + p.kind();
            var record = new ProducerRecord<>(TOPIC, key, json);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .doOnError(e -> log.warn("Failed to publish {} for subject {}: {}",
                            p.kind(), p.subjectId(), e.getMessage()))
                    .then();
        } catch (Exception e) {
            log.warn("Failed to serialize arrears notice for subject {}: {}",
                    p.subjectId(), e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * @param kind             one of the {@code KIND_*} constants
     * @param subjectType      MEMBER or GROUP (matches BadDebtRow shape)
     * @param subjectId        the aged owner id as string
     * @param currencyCode     ISO 4217
     * @param balance          current outstanding, positive amount
     * @param daysOverdue      days since last activity on the aged row
     * @param daysUntilNextStep optional — days remaining until the next
     *                          state change (suspension or deactivation)
     * @param nextStep         optional label of the next state change
     *                         ("SUSPENSION" / "DEACTIVATION")
     */
    public record ArrearsNoticePayload(
            String tenantId,
            String kind,
            String subjectType,
            String subjectId,
            String currencyCode,
            BigDecimal balance,
            long daysOverdue,
            Long daysUntilNextStep,
            String nextStep) {}

    private static String moneyDisplay(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String nullSafe(String v) { return v == null ? "" : v; }
}
