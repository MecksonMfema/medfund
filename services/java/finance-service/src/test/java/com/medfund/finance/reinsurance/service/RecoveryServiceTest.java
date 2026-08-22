package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    @Mock RecoveryRepository recoveryRepository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks RecoveryService service;

    @Test
    void markReceived_fromExpected_moves_toReceived() {
        Recovery r = seed("EXPECTED", null);
        when(recoveryRepository.findById(r.getId())).thenReturn(Mono.just(r));
        when(recoveryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.markReceived(r.getId(), new BigDecimal("120.00"),
                                OffsetDateTime.parse("2026-08-15T10:00:00Z"),
                                "sys", "sys@test")
        )
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("RECEIVED");
                    assertThat(resp.receivedAmount()).isEqualByComparingTo("120.00");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("RECEIVED");
        assertThat(List.of(cap.getValue().changedFields())).contains("status", "receivedAmount");
    }

    @Test
    void markReceived_fromInvoiced_alsoAllowed() {
        Recovery r = seed("INVOICED", null);
        when(recoveryRepository.findById(r.getId())).thenReturn(Mono.just(r));
        when(recoveryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.markReceived(r.getId(), new BigDecimal("120.00"), null, "sys", "sys@test")
        )
                .assertNext(resp -> assertThat(resp.status()).isEqualTo("RECEIVED"))
                .verifyComplete();
    }

    @Test
    void markReceived_alreadyReceived_errors() {
        Recovery r = seed("RECEIVED", new BigDecimal("100.00"));
        when(recoveryRepository.findById(r.getId())).thenReturn(Mono.just(r));

        StepVerifier.create(
                service.markReceived(r.getId(), new BigDecimal("120.00"), null, "sys", "sys@test")
        )
                .expectError(IllegalStateException.class)
                .verify();

        verify(recoveryRepository, never()).save(any());
    }

    @Test
    void markReceived_negativeAmount_errors() {
        StepVerifier.create(
                service.markReceived(UUID.randomUUID(), new BigDecimal("-1"), null, "sys", "sys@test")
        )
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void writeOff_fromExpected_recordsReason() {
        Recovery r = seed("EXPECTED", null);
        when(recoveryRepository.findById(r.getId())).thenReturn(Mono.just(r));
        when(recoveryRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.writeOff(r.getId(), "reinsurer disputed", "sys", "sys@test")
        )
                .assertNext(resp -> {
                    assertThat(resp.status()).isEqualTo("WRITTEN_OFF");
                    assertThat(resp.writeOffReason()).isEqualTo("reinsurer disputed");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("WRITE_OFF");
    }

    @Test
    void writeOff_alreadyWrittenOff_errors() {
        Recovery r = seed("WRITTEN_OFF", null);
        when(recoveryRepository.findById(r.getId())).thenReturn(Mono.just(r));

        StepVerifier.create(service.writeOff(r.getId(), "reason", "sys", "sys@test"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void writeOff_missingReason_errors() {
        StepVerifier.create(service.writeOff(UUID.randomUUID(), " ", "sys", "sys@test"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void markReceived_missingRecovery_errors() {
        UUID id = UUID.randomUUID();
        when(recoveryRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.markReceived(id, BigDecimal.TEN, null, "sys", "sys@test"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    private static Recovery seed(String status, BigDecimal receivedAmount) {
        Recovery r = new Recovery();
        r.setId(UUID.randomUUID());
        r.setCessionId(UUID.randomUUID());
        r.setStatus(status);
        r.setExpectedAmount(new BigDecimal("120.00"));
        r.setReceivedAmount(receivedAmount);
        r.setCurrencyCode("USD");
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(OffsetDateTime.now());
        return r;
    }
}
