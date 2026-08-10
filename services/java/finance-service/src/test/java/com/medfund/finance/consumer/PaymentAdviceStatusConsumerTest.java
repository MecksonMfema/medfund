package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.repository.PaymentAdviceRecordRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAdviceStatusConsumerTest {

    @Mock private PaymentAdviceRecordRepository adviceRepository;
    @Mock private AuditPublisher auditPublisher;
    @Mock private reactor.kafka.receiver.ReceiverOptions<String, String> receiverOptions;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentAdviceStatusConsumer consumer;

    @BeforeEach
    void setup() {
        consumer = new PaymentAdviceStatusConsumer(
                receiverOptions, adviceRepository, auditPublisher, objectMapper);
    }

    @Test
    void processEvent_sentStatus_flipsToSentAndAudits() {
        UUID adviceId = UUID.randomUUID();
        var record = shellRecord(adviceId, "generated", "ADV-000001");

        when(adviceRepository.findById(adviceId)).thenReturn(Mono.just(record));
        when(adviceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        String json = "{"
                + "\"adviceId\":\"" + adviceId + "\","
                + "\"adviceNumber\":\"ADV-000001\","
                + "\"tenantId\":\"" + UUID.randomUUID() + "\","
                + "\"recipient\":\"billing@clinic.test\","
                + "\"status\":\"SENT\","
                + "\"attempts\":\"1\""
                + "}";

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        ArgumentCaptor<PaymentAdviceRecord> saveCap = ArgumentCaptor.forClass(PaymentAdviceRecord.class);
        verify(adviceRepository).save(saveCap.capture());
        assertThat(saveCap.getValue().getStatus()).isEqualTo("sent");
        // issuedAt is preserved from the original generate — the status flip
        // must not overwrite it, otherwise "when was this advice generated"
        // becomes "when was it last delivered".
        assertThat(saveCap.getValue().getIssuedAt()).isBefore(Instant.now().minusSeconds(30));

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(auditCap.capture());
        assertThat(auditCap.getValue().entityName()).contains("ADV-000001");
        assertThat(auditCap.getValue().newValue().get("deliveryOutcome")).isEqualTo("SENT");
        assertThat(auditCap.getValue().newValue().get("recipient")).isEqualTo("billing@clinic.test");
    }

    @Test
    void processEvent_deadLetteredStatus_flipsToFailed() {
        UUID adviceId = UUID.randomUUID();
        var record = shellRecord(adviceId, "generated", "ADV-000002");

        when(adviceRepository.findById(adviceId)).thenReturn(Mono.just(record));
        when(adviceRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        String json = "{"
                + "\"adviceId\":\"" + adviceId + "\","
                + "\"adviceNumber\":\"ADV-000002\","
                + "\"status\":\"DEAD_LETTERED\","
                + "\"attempts\":\"4\","
                + "\"error\":\"smtp timeout\""
                + "}";

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        ArgumentCaptor<PaymentAdviceRecord> saveCap = ArgumentCaptor.forClass(PaymentAdviceRecord.class);
        verify(adviceRepository).save(saveCap.capture());
        assertThat(saveCap.getValue().getStatus()).isEqualTo("failed");

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(auditCap.capture());
        assertThat(auditCap.getValue().newValue().get("deliveryOutcome")).isEqualTo("DEAD_LETTERED");
        assertThat(auditCap.getValue().newValue().get("error")).isEqualTo("smtp timeout");
        assertThat(auditCap.getValue().newValue().get("attempts")).isEqualTo("4");
    }

    @Test
    void processEvent_interimFailedStatus_leavesStatusButAudits() {
        UUID adviceId = UUID.randomUUID();
        var record = shellRecord(adviceId, "generated", "ADV-000003");

        when(adviceRepository.findById(adviceId)).thenReturn(Mono.just(record));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        String json = "{"
                + "\"adviceId\":\"" + adviceId + "\","
                + "\"adviceNumber\":\"ADV-000003\","
                + "\"status\":\"FAILED\","
                + "\"attempts\":\"1\","
                + "\"error\":\"transient\""
                + "}";

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        // Status stays 'generated' because retry is in-flight.
        verify(adviceRepository, never()).save(any());
        verify(auditPublisher, times(1)).publish(any());
    }

    @Test
    void processEvent_reDeliveryOfSameSentOutcome_isIdempotent() {
        UUID adviceId = UUID.randomUUID();
        var record = shellRecord(adviceId, "sent", "ADV-000004");

        when(adviceRepository.findById(adviceId)).thenReturn(Mono.just(record));

        String json = "{"
                + "\"adviceId\":\"" + adviceId + "\","
                + "\"status\":\"SENT\","
                + "\"attempts\":\"1\""
                + "}";

        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        // No save, no audit — same terminal state, nothing to record.
        verify(adviceRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processEvent_missingAdviceId_isSkipped() {
        StepVerifier.create(consumer.processEvent("{\"status\":\"SENT\"}")).verifyComplete();
        verify(adviceRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_nonUuidAdviceId_isSkipped() {
        String json = "{\"adviceId\":\"not-a-uuid\",\"status\":\"SENT\"}";
        StepVerifier.create(consumer.processEvent(json)).verifyComplete();
        verify(adviceRepository, never()).findById(any(UUID.class));
    }

    @Test
    void processEvent_unknownAdvice_isSkipped() {
        UUID adviceId = UUID.randomUUID();
        when(adviceRepository.findById(adviceId)).thenReturn(Mono.empty());

        String json = "{\"adviceId\":\"" + adviceId + "\",\"status\":\"SENT\"}";
        StepVerifier.create(consumer.processEvent(json)).verifyComplete();

        verify(adviceRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    private PaymentAdviceRecord shellRecord(UUID id, String status, String adviceNumber) {
        var r = new PaymentAdviceRecord();
        r.setId(id);
        r.setStatus(status);
        r.setAdviceNumber(adviceNumber);
        r.setIssuedAt(Instant.now().minusSeconds(60));
        return r;
    }
}
