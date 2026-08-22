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

    /**
     * Notify downstream services that a contribution has been paid. Payload
     * additively carries {@code currencyCode}, {@code insuranceLine},
     * {@code paidAt} and {@code tenantId} from Phase 6 onwards so the
     * reinsurance premium-cession consumer in finance-service can dispatch
     * against the paying member's scheme line without a cross-service
     * lookup. Pre-Phase-6 consumers ignore the new fields.
     *
     * <p>Nullable fields serialize as empty strings so the {@code
     * Map<String,String>} envelope stays uniform; downstream parsers treat
     * blank as null (see {@code ContributionPaidEvent.from} in
     * finance-service).
     */
    public Mono<Void> publishContributionPaid(String contributionId, String memberId, String amount,
                                              String currencyCode, String insuranceLine,
                                              String paidAt, String tenantId) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event",          "CONTRIBUTION_PAID");
        fields.put("contributionId", contributionId);
        fields.put("memberId",       nullSafe(memberId));
        fields.put("amount",         nullSafe(amount));
        fields.put("currencyCode",   nullSafe(currencyCode));
        fields.put("insuranceLine",  nullSafe(insuranceLine));
        fields.put("paidAt",         nullSafe(paidAt));
        fields.put("tenantId",       nullSafe(tenantId));
        return publishEvent("medfund.contributions.paid", contributionId, fields);
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
        // Recipient name resolved by BillingService.persistInvoiceFor before
        // publishing — file-service uses it on the PDF header so the
        // rendered document shows the real group/member name instead of
        // the truncated-UUID fallback "Group abc12345".
        if (p.recipientName() != null && !p.recipientName().isBlank())
            fields.put("recipientName", p.recipientName());
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
            String adjustmentsInWindow,
            /** Friendly group or member name resolved from the DB before
             *  publishing so file-service doesn't have to do its own
             *  cross-schema lookup. */
            String recipientName) {}

    /**
     * Fire-and-forget signal to file-service that an invoice's PDF blob
     * should be removed from object storage. Published from
     * {@link BillingService#revokeBilling} after the invoice row + its
     * cascading {@code invoice_pdfs} pointer have been deleted — the
     * blob itself in MinIO isn't covered by the CASCADE, so we hand the
     * (bucket, objectKey) tuple back to file-service which owns the
     * MinIO client.
     *
     * <p>One event per blob; tenantId is included so future per-tenant
     * MinIO buckets / lifecycle policies have the context they need.
     * Failure is logged at the publisher but doesn't block the revoke —
     * an orphan blob is recoverable (it can be GC'd by a sweeper), an
     * inconsistent ledger is not.
     */
    /**
     * Notify downstream services that a transaction has been recorded so
     * the notification-service can dispatch a receipt to the owning
     * group's liaison or the paying member. Exactly one of
     * {@code groupId} / {@code memberId} is non-null — the recipient
     * resolver picks its channel based on which is present.
     */
    public Mono<Void> publishTransactionRecorded(TransactionRecordedPayload p) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event", "TRANSACTION_RECORDED");
        fields.put("transactionId",     p.transactionId());
        fields.put("transactionNumber", p.transactionNumber());
        fields.put("tenantId",          p.tenantId());
        if (p.groupId()  != null) fields.put("groupId",  p.groupId());
        if (p.memberId() != null) fields.put("memberId", p.memberId());
        fields.put("amount",          p.amount());
        fields.put("currencyCode",    p.currencyCode());
        fields.put("transactionType", p.transactionType());
        if (p.paymentMethod() != null) fields.put("paymentMethod", p.paymentMethod());
        if (p.reference()     != null) fields.put("reference",     p.reference());
        if (p.transactionDate() != null) fields.put("transactionDate", p.transactionDate());
        // Friendly recipient name resolved from the tenant DB before publish
        // so downstream services never emit UUIDs to end users. Field is
        // omitted on legacy events; renderers fall back to a generic label.
        if (p.recipientName() != null && !p.recipientName().isBlank())
            fields.put("recipientName", p.recipientName());
        return publishEvent("medfund.contributions.transaction-recorded",
                p.transactionId(), fields);
    }

    /** Payload for {@link #publishTransactionRecorded}. */
    public record TransactionRecordedPayload(
            String transactionId,
            String transactionNumber,
            String tenantId,
            String groupId,
            String memberId,
            String amount,
            String currencyCode,
            String transactionType,
            String paymentMethod,
            String reference,
            String transactionDate,
            /** Friendly group/member name resolved from the DB before
             *  publishing so no downstream artefact shows a UUID. */
            String recipientName) {}

    /**
     * Notify downstream services that a scheme change has been made
     * effective. The {@code SchemeChangedConsumer} on the contributions
     * side uses the payload to detect a back-dated upgrade/downgrade
     * that overlaps an already-billed period and auto-posts the
     * corresponding SCHEME_UPGRADE_ARREARS / SCHEME_DOWNGRADE_REBATE.
     */
    public Mono<Void> publishSchemeChanged(SchemeChangedPayload p) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event", "SCHEME_CHANGED");
        fields.put("schemeChangeId", p.schemeChangeId());
        fields.put("memberId",       p.memberId());
        fields.put("tenantId",       nullSafe(p.tenantId()));
        fields.put("fromSchemeId",   nullSafe(p.fromSchemeId()));
        fields.put("toSchemeId",     nullSafe(p.toSchemeId()));
        fields.put("effectiveDate",  nullSafe(p.effectiveDate()));
        // V048 fields — routing hints for the consumer. Omitted from
        // pre-V048 events; consumer falls back to sign-based
        // classification for backward compatibility.
        fields.put("changeKind",     nullSafe(p.changeKind()));
        fields.put("backdated",      Boolean.toString(p.backdated()));
        return publishEvent("medfund.contributions.scheme-changed",
                p.schemeChangeId(), fields);
    }

    /** Payload for {@link #publishSchemeChanged}. Consumer resolves the
     *  member's group_id at consume time so this stays a lean domain event.
     *
     *  <p>V048 added {@code changeKind} (UPGRADE / DOWNGRADE / CURRENCY_CHANGE
     *  / CROSS_GRADE) and {@code backdated} to let the consumer route
     *  currency-change adjustments to CURRENCY_CHANGE_ADJUSTMENT and skip
     *  forward-dated changes entirely.
     */
    public record SchemeChangedPayload(
            String schemeChangeId,
            String memberId,
            String tenantId,
            String fromSchemeId,
            String toSchemeId,
            String effectiveDate,
            String changeKind,
            boolean backdated) {
        /** Backwards-compat constructor used by pre-V048 call sites. */
        public SchemeChangedPayload(String schemeChangeId, String memberId, String tenantId,
                                     String fromSchemeId, String toSchemeId, String effectiveDate) {
            this(schemeChangeId, memberId, tenantId, fromSchemeId, toSchemeId, effectiveDate,
                    "CROSS_GRADE", false);
        }
    }

    private static String nullSafe(String v) { return v == null ? "" : v; }

    public Mono<Void> publishInvoicePdfDeleted(String tenantId, String invoiceId,
                                               String bucket, String objectKey) {
        if (bucket == null || objectKey == null || bucket.isBlank() || objectKey.isBlank()) {
            return Mono.empty();
        }
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event", "INVOICE_PDF_DELETED");
        fields.put("tenantId",  tenantId  == null ? "" : tenantId);
        fields.put("invoiceId", invoiceId == null ? "" : invoiceId);
        fields.put("bucket",     bucket);
        fields.put("objectKey",  objectKey);
        return publishEvent("medfund.contributions.invoice-pdf-deleted",
                invoiceId == null ? bucket + "/" + objectKey : invoiceId, fields);
    }

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
