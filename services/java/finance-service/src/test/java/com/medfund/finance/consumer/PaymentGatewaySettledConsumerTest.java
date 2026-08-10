package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.service.FinanceEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests exercising {@link PaymentGatewaySettledConsumer#processEvent(String)}
 * directly — bypasses the KafkaReceiver wiring which needs a broker.
 */
@ExtendWith(MockitoExtension.class)
class PaymentGatewaySettledConsumerTest {

    @Mock
    private ReceiverOptions<String, String> receiverOptions;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private FinanceEventPublisher eventPublisher;

    private PaymentGatewaySettledConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new PaymentGatewaySettledConsumer(
                receiverOptions, paymentRepository, eventPublisher, objectMapper);
    }

    @Test
    void completed_flipsPaymentToPaidAndPublishesCommitted() {
        UUID paymentId = UUID.randomUUID();
        var payment = new Payment();
        payment.setId(paymentId);
        payment.setStatus("pending");
        payment.setAmount(new BigDecimal("150.00"));
        payment.setCurrencyCode("USD");
        payment.setProviderId(UUID.randomUUID());

        when(paymentRepository.findById(paymentId)).thenReturn(Mono.just(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventPublisher.publishPaymentCommitted(any(), any(), any(), any())).thenReturn(Mono.empty());

        String json = """
                {
                  "event": "PAYMENT_GATEWAY_SETTLED",
                  "itemId": "item-1",
                  "paymentId": "%s",
                  "tenantId": "%s",
                  "status": "completed"
                }
                """.formatted(paymentId, UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json))
                .verifyComplete();

        var cap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("paid");
        assertThat(cap.getValue().getPaidAt()).isNotNull();
        verify(eventPublisher).publishPaymentCommitted(any(), any(), any(), any());
    }

    @Test
    void nonCompletedStatus_isNoOp() {
        String json = """
                { "event": "PAYMENT_GATEWAY_SETTLED",
                  "paymentId": "%s",
                  "status": "failed" }
                """.formatted(UUID.randomUUID());

        StepVerifier.create(consumer.processEvent(json))
                .verifyComplete();

        verify(paymentRepository, never()).findById(any(UUID.class));
        verify(paymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishPaymentCommitted(any(), any(), any(), any());
    }

    @Test
    void missingPaymentId_isNoOp() {
        String json = """
                { "event": "PAYMENT_GATEWAY_SETTLED",
                  "status": "completed" }
                """;

        StepVerifier.create(consumer.processEvent(json))
                .verifyComplete();

        verify(paymentRepository, never()).findById(any(UUID.class));
    }

    @Test
    void alreadyPaid_isIdempotent() {
        UUID paymentId = UUID.randomUUID();
        var payment = new Payment();
        payment.setId(paymentId);
        payment.setStatus("paid");
        payment.setPaidAt(Instant.now());
        payment.setAmount(new BigDecimal("150.00"));
        payment.setCurrencyCode("USD");

        when(paymentRepository.findById(paymentId)).thenReturn(Mono.just(payment));

        String json = """
                { "event": "PAYMENT_GATEWAY_SETTLED",
                  "paymentId": "%s",
                  "status": "completed" }
                """.formatted(paymentId);

        StepVerifier.create(consumer.processEvent(json))
                .verifyComplete();

        verify(paymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishPaymentCommitted(any(), any(), any(), any());
    }

    @Test
    void unknownPaymentId_isNoOp() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Mono.empty());

        String json = """
                { "event": "PAYMENT_GATEWAY_SETTLED",
                  "paymentId": "%s",
                  "status": "completed" }
                """.formatted(paymentId);

        StepVerifier.create(consumer.processEvent(json))
                .verifyComplete();

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void nonUuidPaymentId_isNoOp() {
        String json = """
                { "event": "PAYMENT_GATEWAY_SETTLED",
                  "paymentId": "not-a-uuid",
                  "status": "completed" }
                """;

        StepVerifier.create(consumer.processEvent(json))
                .verifyComplete();

        verify(paymentRepository, never()).findById(any(UUID.class));
    }

    @Test
    void malformedJson_errorsSoOffsetIsNotAcked() {
        String badJson = "{not valid json";

        StepVerifier.create(consumer.processEvent(badJson))
                .expectError()
                .verify();

        verify(paymentRepository, never()).findById(any(UUID.class));
    }
}
