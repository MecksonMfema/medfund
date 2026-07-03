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
import reactor.util.retry.Retry;

import java.time.Duration;
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
                        // Only ack on success. If processing errors — even after
                        // the bounded retry inside processEvent — leave the offset
                        // untouched so the record is redelivered on the next poll.
                        // Previous version used doOnTerminate which fires on both
                        // success and error, causing failed events (e.g. a stale
                        // pooled R2DBC connection) to be permanently lost.
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            logCauseChain("processing offset=" + record.receiverOffset().topicPartition()
                                    + "@" + record.offset(), e);
                            return Mono.empty();
                        }))
                .doOnError(e -> logCauseChain("consumer stream", e))
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
                    // Bounded backoff for transient acquire failures. The r2dbc-pool
                    // occasionally hands back a stale connection after a long idle
                    // window (a real observed failure was "Failed to obtain R2DBC
                    // Connection" ~7 min after the previous successful UPSERT).
                    // 250 ms → 2 s → capped total ≈ 5 s. Errors that survive the
                    // retry propagate up, the offset stays un-acked, and the
                    // partition is redelivered on the next poll.
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(250))
                            .maxBackoff(Duration.ofSeconds(2))
                            .filter(InvoicePdfReadyConsumer::isTransient)
                            .doBeforeRetry(sig -> log.warn(
                                    "[invoice-pdf-ready] retry {} for invoice={} tenant={} — {}",
                                    sig.totalRetries() + 1, invoiceId, tenantId,
                                    sig.failure().getClass().getSimpleName())))
                    .contextWrite(ctx -> TenantContext.put(ctx, tenantId));
        } catch (Exception e) {
            log.error("Failed to parse invoice-pdf-ready event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    /**
     * Any acquire / query / driver issue is worth one more shot — most of these
     * are stale-pool or transient network blips. Parse / validation errors
     * (thrown before we reach the DB) fall through {@link #processEvent}'s
     * catch and never enter this retry path.
     */
    private static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getName();
            if (n.startsWith("io.r2dbc")
                    || n.startsWith("org.springframework.r2dbc")
                    || n.startsWith("org.springframework.dao")
                    || n.equals("java.util.concurrent.TimeoutException")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the entire cause chain so we don't lose the real reason inside
     * a wrapping "Failed to obtain R2DBC Connection". The previous logger
     * printed only e.getMessage() on the top exception, which erased the
     * caused-by SQLState / driver message we actually needed to diagnose.
     */
    private static void logCauseChain(String context, Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (sb.length() > 0) sb.append(" ← ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            if (t.getCause() == t) break; // guard against self-referential cause loops
        }
        log.error("[invoice-pdf-ready] {} failed: {}", context, sb, e);
    }
}
