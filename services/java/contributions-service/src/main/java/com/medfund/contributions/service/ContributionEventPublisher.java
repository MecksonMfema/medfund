package com.medfund.contributions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.Map;

@Service
public class ContributionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ContributionEventPublisher.class);

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public ContributionEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publishBillingGenerated(String schemeId, String periodStart, String periodEnd, int count) {
        return publishEvent("medfund.contributions.billing-generated", schemeId, Map.of(
            "event", "BILLING_GENERATED",
            "schemeId", schemeId,
            "periodStart", periodStart,
            "periodEnd", periodEnd,
            "count", String.valueOf(count)
        ));
    }

    public Mono<Void> publishContributionPaid(String contributionId, String memberId, String amount) {
        return publishEvent("medfund.contributions.paid", contributionId, Map.of(
            "event", "CONTRIBUTION_PAID",
            "contributionId", contributionId,
            "memberId", memberId,
            "amount", amount
        ));
    }

    /**
     * Notify downstream services (file-service for PDF rendering, then
     * notification-service for email delivery) that an invoice has been
     * issued. Carries everything those consumers need to render and
     * dispatch without round-tripping back into contributions-service —
     * keeps PDF generation tolerant to the source service being briefly
     * unreachable.
     *
     * <p>Exactly one of {@code groupId} / {@code memberId} is non-null:
     * group invoices route the email to the group's liaison; individual
     * invoices route to the member directly.
     */
    public Mono<Void> publishInvoiceIssued(InvoiceIssuedPayload p) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event", "INVOICE_ISSUED");
        fields.put("invoiceId", p.invoiceId());
        fields.put("invoiceNumber", p.invoiceNumber());
        fields.put("tenantId", p.tenantId());
        if (p.groupId() != null)  fields.put("groupId",  p.groupId());
        if (p.memberId() != null) fields.put("memberId", p.memberId());
        fields.put("currencyCode", p.currencyCode());
        fields.put("totalAmount",  p.totalAmount());
        fields.put("periodStart",  p.periodStart());
        fields.put("periodEnd",    p.periodEnd());
        fields.put("dueDate",      p.dueDate());
        // Snapshot fields (plan §4c) so the file-service renderer can put
        // the numbers on the PDF without re-querying the DB. Stringified
        // BigDecimals so the Map<String,String> envelope stays uniform.
        if (p.committedAt() != null)         fields.put("committedAt",         p.committedAt());
        if (p.openingBalance() != null)      fields.put("openingBalance",      p.openingBalance());
        if (p.closingBalance() != null)      fields.put("closingBalance",      p.closingBalance());
        if (p.paymentsInWindow() != null)    fields.put("paymentsInWindow",    p.paymentsInWindow());
        if (p.adjustmentsInWindow() != null) fields.put("adjustmentsInWindow", p.adjustmentsInWindow());
        return publishEvent("medfund.contributions.invoice-issued", p.invoiceId(), fields);
    }

    /**
     * Payload for {@link #publishInvoiceIssued(InvoiceIssuedPayload)}. Exactly
     * one of {@code groupId} / {@code memberId} should be set — the publisher
     * elides whichever is null from the wire payload so consumers can
     * disambiguate "no recipient" (a data anomaly) from "the other kind".
     */
    public record InvoiceIssuedPayload(
            String invoiceId,
            String invoiceNumber,
            String tenantId,
            String groupId,
            String memberId,
            String currencyCode,
            String totalAmount,
            String periodStart,
            String periodEnd,
            String dueDate,
            String committedAt,
            String openingBalance,
            String closingBalance,
            String paymentsInWindow,
            String adjustmentsInWindow) {}

    private Mono<Void> publishEvent(String topic, String key, Map<String, String> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            var record = new ProducerRecord<>(topic, key, json);
            var senderRecord = SenderRecord.create(record, key);
            return kafkaSender.send(Mono.just(senderRecord))
                .doOnError(e -> log.error("Failed to publish event to {}: {}", topic, e.getMessage()))
                .then();
        } catch (Exception e) {
            log.error("Failed to serialize event for {}: {}", topic, e.getMessage());
            return Mono.empty();
        }
    }
}
