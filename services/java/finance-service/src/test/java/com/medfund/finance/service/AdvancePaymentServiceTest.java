package com.medfund.finance.service;

import com.medfund.finance.dto.AdvancePaymentDtos.CreateAdvancePaymentRequest;
import com.medfund.finance.entity.AdvancePayment;
import com.medfund.finance.repository.AdvancePaymentRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvancePaymentServiceTest {

    @Mock
    private AdvancePaymentRepository repository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private AdvancePaymentService service;

    @Test
    void create_providerOnly_persistsAndAudits() {
        UUID providerId = UUID.randomUUID();
        var request = new CreateAdvancePaymentRequest(
            providerId, null, new BigDecimal("250.00"), "USD",
            "EFT", "REF-001", "advance for medical supplies");
        when(repository.save(any())).thenAnswer(inv -> {
            AdvancePayment saved = inv.getArgument(0);
            saved.setRecordedAt(Instant.now());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(request, UUID.randomUUID().toString(), "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getProviderId()).isEqualTo(providerId);
                    assertThat(saved.getMemberId()).isNull();
                    assertThat(saved.getAmount()).isEqualByComparingTo("250.00");
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                })
                .verifyComplete();

        verify(repository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_memberOnly_persists() {
        UUID memberId = UUID.randomUUID();
        var request = new CreateAdvancePaymentRequest(
            null, memberId, new BigDecimal("75.50"), "ZWG", null, null, null);
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getMemberId()).isEqualTo(memberId);
                    assertThat(saved.getProviderId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void create_neitherProviderNorMember_errors() {
        var request = new CreateAdvancePaymentRequest(
            null, null, new BigDecimal("100"), "USD", null, null, null);

        StepVerifier.create(
                service.create(request, "system", "actor@test.example")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void findByProvider_delegatesToRepository() {
        UUID providerId = UUID.randomUUID();
        when(repository.findByProviderId(providerId)).thenReturn(Flux.empty());

        StepVerifier.create(service.findByProvider(providerId))
                .verifyComplete();

        verify(repository).findByProviderId(providerId);
    }

    @Test
    void findByMember_delegatesToRepository() {
        UUID memberId = UUID.randomUUID();
        when(repository.findByMemberId(memberId)).thenReturn(Flux.empty());

        StepVerifier.create(service.findByMember(memberId))
                .verifyComplete();

        verify(repository).findByMemberId(memberId);
    }
}
