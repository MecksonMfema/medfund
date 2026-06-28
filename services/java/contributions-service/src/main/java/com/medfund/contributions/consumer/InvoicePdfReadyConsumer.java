package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.util.Collections;

/**
 * Subscribes to {@code medfund.contributions.invoice-pdf-ready} (published
 * by file-service after MinIO upload) and UPSERTs the pointer row into
 * {@code invoice_pdfs} on the correct tenant schema. The contributions-
 * service then surfaces the pointer through {@code GET /api/v1/invoices/{id}/pdf}
 * which delegates to file-service for the actual bytes (plan §4).
 *
 * <p>Tenant routing: the event envelope carries {@code tenantId}; we
 * write it into the reactor context so {@code TenantAwareConnectionFactory}
 * routes the UPSERT to the right schema. No ThreadLocal — pure reactor.
 *
 * <p>Idempotency: UPSERT keyed on {@code invoice_id} so a re-render
 * (re-published event) overwrites the bucket/object_key cleanly.
 */
@Component
public class InvoicePdfReadyConsumer {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfReadyConsumer.class);
    private static final String TOPIC = "medfund.contributions.invoice-pdf-ready";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final DatabaseClient db;

    public InvoicePdfReadyConsumer(ReceiverOptions<String, String> receiverOptions,
                                    ObjectMapper objectMapper,
                                    DatabaseClient db) {
        this.receiverOptions = receiverOptions;
        this.objectMapper = objectMapper;
        this.db = db;
    }

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnTerminate(() -> record.receiverOffset().acknowledge()))
                .doOnError(e -> log.error("InvoicePdfReady consumer error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String invoiceId = node.get("invoiceId").asText();
            String tenantId  = node.has("tenantId")  ? node.get("tenantId").asText()  : null;
            String bucket    = node.has("pdfBucket") ? node.get("pdfBucket").asText() : null;
            String objectKey = node.has("pdfObjectKey") ? node.get("pdfObjectKey").asText() : null;

            if (tenantId == null || tenantId.isBlank()) {
                log.warn("[invoice-pdf-ready] missing tenantId — skipping invoiceId={}", invoiceId);
                return Mono.empty();
            }
            if (bucket == null || objectKey == null) {
                log.warn("[invoice-pdf-ready] missing bucket/object_key — invoiceId={} tenant={}",
                        invoiceId, tenantId);
                return Mono.empty();
            }

            return db.sql("""
                    INSERT INTO invoice_pdfs (invoice_id, bucket, object_key, rendered_at)
                    VALUES (:invoiceId, :bucket, :objectKey, NOW())
                    ON CONFLICT (invoice_id) DO UPDATE SET
                        bucket      = EXCLUDED.bucket,
                        object_key  = EXCLUDED.object_key,
                        rendered_at = EXCLUDED.rendered_at
                    """)
                    .bind("invoiceId", java.util.UUID.fromString(invoiceId))
                    .bind("bucket",    bucket)
                    .bind("objectKey", objectKey)
                    .fetch().rowsUpdated()
                    .doOnNext(n -> log.info("[invoice-pdf-ready] tenant={} invoice={} UPSERTed pointer rows={}",
                            tenantId, invoiceId, n))
                    .then()
                    .contextWrite(ctx -> TenantContext.put(ctx, tenantId));
        } catch (Exception e) {
            log.error("Failed to parse invoice-pdf-ready event: {}", e.getMessage());
            return Mono.error(e);
        }
    }
}
