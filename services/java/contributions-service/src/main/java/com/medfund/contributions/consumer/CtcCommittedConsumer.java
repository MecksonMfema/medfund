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
 * Consumes {@code medfund.finance.ctc.committed} and posts a
 * {@code CTC_OFFSET} transaction against the member's contribution
 * ledger. The transaction-type's sign is {@code '-'} (seeded in V069),
 * so the balance moves down by {@code amount} in the CTC's currency —
 * the CTC's own currency is used directly for MVP; a follow-up ticket
 * can add ledger-currency FX conversion once contributions-service
 * grows its own {@code FxConverter}.
 *
 * <p>Uses {@code .doOnSuccess} for the offset ack — never
 * {@code .doOnTerminate} — per {@code bug_reactor_kafka_ack_swallow}:
 * a failed downstream write must NOT ack, otherwise Kafka at-least-once
 * turns into at-most-once for the offending record. Errors are logged
 * full-chain (with the {@link Throwable}) so the causal exception is
 * visible in the operator's log stream.
 *
 * <p>Uses {@link TransactionService#recordFromCtcOffset} rather than
 * plain {@code record()} — that variant bypasses
 * {@code rejectIfMemberIsGrouped} because a CTC is an internal
 * system-initiated offset, not a payment made by the member. See design
 * decision #3 in the plan and {@code feedback_grouped_members_cannot_pay}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CtcCommittedConsumer {

    private static final String TOPIC = "medfund.finance.ctc.committed";

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
                        .doOnError(e -> log.error("Failed to process CTC committed event (full chain): ", e))
                        .onErrorResume(e -> Mono.empty()))
                .doOnError(e -> log.error("CTC committed consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String memberIdStr      = textOrNull(node, "memberId");
            String amountStr        = textOrNull(node, "amount");
            String currency         = textOrNull(node, "currencyCode");
            String ctcId            = textOrNull(node, "ctcId");
            String memberPayableId  = textOrNull(node, "memberPayableId");
            String tenantId         = textOrNull(node, "tenantId");
            if (memberIdStr == null || amountStr == null || currency == null || ctcId == null) {
                log.info("Skipping CTC_COMMITTED with missing fields: memberId={}, amount={}, ctcId={}",
                        memberIdStr, amountStr, ctcId);
                return Mono.empty();
            }
            BigDecimal amount = new BigDecimal(amountStr);
            if (amount.signum() <= 0) {
                return Mono.empty();
            }
            var request = new RecordTransactionRequest(
                /* groupId */         null,
                /* memberId */        UUID.fromString(memberIdStr),
                /* amount */          amount,
                /* currencyCode */    currency,
                /* transactionType */ "CTC_OFFSET",
                /* paymentMethod */   "CTC",
                /* reference */       "CTC:" + ctcId,
                /* reason */          memberPayableId != null
                                        ? "Claims-to-Contributions offset — payable " + memberPayableId
                                        : "Claims-to-Contributions offset"
            );
            Mono<Void> work = transactionService.recordFromCtcOffset(
                    request, AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL).then();
            return tenantId != null && !tenantId.isBlank()
                    ? work.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : work;
        } catch (Exception e) {
            log.error("Failed to parse CTC committed event: ", e);
            return Mono.error(e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
