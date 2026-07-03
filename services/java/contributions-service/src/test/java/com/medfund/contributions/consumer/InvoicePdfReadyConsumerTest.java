package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.R2dbcTransientResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the two bugs that made INV-739913 "stuck rendering" in July 2026:
 *
 * <ol>
 *   <li>The consumer used to acknowledge the Kafka offset via {@code doOnTerminate},
 *       which fires on error too — a failing UPSERT silently advanced the offset
 *       and the record was permanently lost. {@link InvoicePdfReadyConsumer#processEvent}
 *       must now propagate the error so the caller can skip the ack.</li>
 *   <li>A stale pooled R2DBC connection surfaced as a generic
 *       {@link DataAccessResourceFailureException} ("Failed to obtain R2DBC Connection").
 *       The consumer must retry a bounded number of times before giving up so a
 *       single transient blip doesn't tip the record into the lost bucket.</li>
 * </ol>
 *
 * <p>Also covers the two skip paths (missing tenantId / missing bucket) so a
 * malformed publisher can't drag the consumer into an error state.
 */
@ExtendWith(MockitoExtension.class)
class InvoicePdfReadyConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private DatabaseClient db;
    @Mock private DatabaseClient.GenericExecuteSpec spec;
    // FetchSpec is parameterised on Map<String, Object> per DatabaseClient's
    // contract — matching the return type of spec.fetch() so Mockito accepts
    // the stub.
    @Mock private FetchSpec<Map<String, Object>> fetchSpec;

    private InvoicePdfReadyConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InvoicePdfReadyConsumer(null, objectMapper, db);
        // Wire the DatabaseClient fluent chain to route through the same spec
        // stub — each .bind(...) returns itself, .fetch() returns fetchSpec.
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.fetch()).thenReturn(fetchSpec);
    }

    @Test
    void processEvent_missingTenantId_skipsWithoutSql() {
        String invoiceId = UUID.randomUUID().toString();
        String json = """
            {"event":"INVOICE_PDF_READY","invoiceId":"%s","pdfBucket":"b","pdfObjectKey":"k"}
            """.formatted(invoiceId);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        // Skipping must be a no-op — a malformed event with no tenant can't be
        // routed to a schema and mustn't be routed to public either.
        verify(db, never()).sql(anyString());
    }

    @Test
    void processEvent_missingBucket_skipsWithoutSql() {
        String invoiceId = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
            {"event":"INVOICE_PDF_READY","invoiceId":"%s","tenantId":"%s"}
            """.formatted(invoiceId, tenantId);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(db, never()).sql(anyString());
    }

    @Test
    void processEvent_missingObjectKey_skipsWithoutSql() {
        String invoiceId = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
            {"event":"INVOICE_PDF_READY","invoiceId":"%s","tenantId":"%s","pdfBucket":"b"}
            """.formatted(invoiceId, tenantId);

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(db, never()).sql(anyString());
    }

    @Test
    void processEvent_validPayload_upsertsAndCompletes() {
        String invoiceId = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
            {"event":"INVOICE_PDF_READY","invoiceId":"%s","tenantId":"%s",
             "pdfBucket":"medfund-invoices","pdfObjectKey":"invoices/%s/CS-000123.pdf"}
            """.formatted(invoiceId, tenantId, tenantId);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(db).sql(anyString());
        verify(spec).bind("invoiceId", UUID.fromString(invoiceId));
        verify(spec).bind("bucket",    "medfund-invoices");
        verify(spec).bind("objectKey", "invoices/" + tenantId + "/CS-000123.pdf");
    }

    /**
     * The bounded {@code retryWhen} inside {@code processEvent} must give a
     * transient failure at least one more shot before propagating. Uses an
     * atomic counter so the mock succeeds on the third attempt — proving the
     * retry is wired, not just a happy-path replay.
     */
    @Test
    void processEvent_transientR2dbcError_retriesThenSucceeds() {
        String invoiceId = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
            {"event":"INVOICE_PDF_READY","invoiceId":"%s","tenantId":"%s",
             "pdfBucket":"b","pdfObjectKey":"k"}
            """.formatted(invoiceId, tenantId);

        AtomicInteger attempts = new AtomicInteger();
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.defer(() -> {
            // First two acquires simulate the stale-connection error that
            // caused the July 2026 outage. Third attempt succeeds.
            int n = attempts.incrementAndGet();
            if (n < 3) {
                return Mono.error(new DataAccessResourceFailureException(
                        "Failed to obtain R2DBC Connection",
                        new R2dbcTransientResourceException("connection closed")));
            }
            return Mono.just(1L);
        }));

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        assertThat(attempts.get()).isEqualTo(3);
    }

    /**
     * When even the retries can't recover, the error must propagate so the
     * outer consumer chain (in @PostConstruct) can decline to ack the offset
     * and let Kafka redeliver on the next poll. This is the single most
     * important regression guard for the "stuck rendering" outage.
     */
    @Test
    void processEvent_persistentR2dbcError_propagatesErrorAfterRetries() {
        String invoiceId = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
            {"event":"INVOICE_PDF_READY","invoiceId":"%s","tenantId":"%s",
             "pdfBucket":"b","pdfObjectKey":"k"}
            """.formatted(invoiceId, tenantId);

        AtomicInteger attempts = new AtomicInteger();
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new DataAccessResourceFailureException(
                    "Failed to obtain R2DBC Connection",
                    new R2dbcTransientResourceException("connection closed")));
        }));

        // Reactor's Retry.backoff wraps the final failure in RetryExhaustedException
        // once max retries are consumed; the original DataAccessResourceFailureException
        // is preserved as the cause so operators can still see the driver-level reason
        // in the consumer's cause-chain logger.
        StepVerifier.create(consumer.processEvent(json))
                .expectErrorSatisfies(e -> {
                    assertThat(e.getClass().getSimpleName()).isEqualTo("RetryExhaustedException");
                    assertThat(e).hasCauseInstanceOf(DataAccessResourceFailureException.class);
                })
                .verify();

        // 1 initial + 3 retries = 4 total attempts. Any change to the retry
        // count in InvoicePdfReadyConsumer will trip this — deliberate, so a
        // future edit has to consciously widen or narrow the retry envelope.
        assertThat(attempts.get()).isEqualTo(4);
    }

    /**
     * A parse failure inside the try/catch must surface as an error too — the
     * consumer must not swallow malformed JSON as "complete", or the outer
     * chain will ack the offset for a record it never processed.
     */
    @Test
    void processEvent_invalidJson_propagatesParseError() {
        StepVerifier.create(consumer.processEvent("not valid json {{{"))
                .verifyError();

        verify(db, never()).sql(anyString());
    }
}
