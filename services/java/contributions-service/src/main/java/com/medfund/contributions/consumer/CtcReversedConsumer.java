package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.service.TransactionService;
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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

/**
 * Mirror of {@link CtcCommittedConsumer} for the reversal path.
 * Consumes {@code medfund.finance.ctc.reversed} and posts a
 * {@code CTC_OFFSET_REVERSAL} transaction — the type is seeded with
 * sign {@code '+'} in V069 so the balance moves back up by the
 * absolute {@code amount}. The reference points at the ORIGINAL CTC
 * so operators can locate the pair from either side of the ledger.
 *
 * <p>Same ack posture as {@link CtcCommittedConsumer} — {@code .doOnSuccess}
 * only, per {@code bug_reactor_kafka_ack_swallow}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CtcReversedConsumer {

    private static final String TOPIC = "medfund.finance.ctc.reversed";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final TransactionService transactionService;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .doOnError(e -> log.error("Failed to process CTC reversed event (full chain): ", e))
                        .onErrorResume(e -> Mono.empty()))
                .doOnError(e -> log.error("CTC reversed consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String memberIdStr        = textOrNull(node, "memberId");
            String amountStr          = textOrNull(node, "amount");
            String currency           = textOrNull(node, "currencyCode");
            String originalCtcId      = textOrNull(node, "originalCtcId");
            String compensatingCtcId  = textOrNull(node, "compensatingCtcId");
            String reason             = textOrNull(node, "reason");
            String tenantId           = textOrNull(node, "tenantId");
            if (memberIdStr == null || amountStr == null || currency == null || originalCtcId == null) {
                log.info("Skipping CTC_REVERSED with missing fields: memberId={}, amount={}, originalCtcId={}",
                        memberIdStr, amountStr, originalCtcId);
                return Mono.empty();
            }
            BigDecimal amount = new BigDecimal(amountStr);
            if (amount.signum() <= 0) {
                return Mono.empty();
            }
            String ref = compensatingCtcId != null
                    ? "CTC-REV:" + compensatingCtcId + " (of " + originalCtcId + ")"
                    : "CTC-REV: " + originalCtcId;
            var request = new RecordTransactionRequest(
                /* groupId */         null,
                /* memberId */        UUID.fromString(memberIdStr),
                /* amount */          amount,
                /* currencyCode */    currency,
                /* transactionType */ "CTC_OFFSET_REVERSAL",
                /* paymentMethod */   "CTC",
                /* reference */       ref,
                /* reason */          reason != null && !reason.isBlank()
                                        ? "Claims-to-Contributions reversal — " + reason
                                        : "Claims-to-Contributions reversal"
            );
            Mono<Void> work = transactionService.recordFromCtcOffset(
                    request, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL).then();
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse CTC reversed event: ", e);
            return Mono.error(e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
