package com.medfund.finance.service;

import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class CtcPaymentServiceTest {

    @Mock
    private CtcPaymentRepository repository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private CtcPaymentService service;

    @Test
    void create_groupOnly_persistsAsPending() {
        UUID groupId = UUID.randomUUID();
        var request = new CreateCtcPaymentRequest(
            groupId, null, new BigDecimal("1000.00"), "USD", null);
        when(repository.save(any())).thenAnswer(inv -> {
            CtcPayment saved = inv.getArgument(0);
            saved.setCreatedAt(Instant.now());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(request, "system")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getGroupId()).isEqualTo(groupId);
                    assertThat(saved.getMemberId()).isNull();
                    assertThat(saved.getCommitted()).isFalse();
                })
                .verifyComplete();

        verify(repository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_memberOnly_persists() {
        UUID memberId = UUID.randomUUID();
        var request = new CreateCtcPaymentRequest(
            null, memberId, new BigDecimal("50.00"), "ZWG", UUID.randomUUID());
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(request, "system")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getMemberId()).isEqualTo(memberId);
                    assertThat(saved.getContributionId()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void create_neither_errors() {
        var request = new CreateCtcPaymentRequest(null, null, new BigDecimal("10"), "USD", null);

        StepVerifier.create(
                service.create(request, "system")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void commit_pending_flipsToCommitted() {
        var existing = pendingPayment();
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.commit(existing.getId(), "system")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getCommitted()).isTrue())
                .verifyComplete();

        verify(repository).save(any());
    }

    @Test
    void commit_alreadyCommitted_isIdempotentNoSave() {
        var existing = pendingPayment();
        existing.setCommitted(true);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(
                service.commit(existing.getId(), "system")
                       .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getCommitted()).isTrue())
                .verifyComplete();

        verify(repository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    private CtcPayment pendingPayment() {
        var c = new CtcPayment();
        c.setId(UUID.randomUUID());
        c.setGroupId(UUID.randomUUID());
        c.setAmount(new BigDecimal("500.00"));
        c.setCurrencyCode("USD");
        c.setCommitted(false);
        c.setCreatedAt(Instant.now());
        return c;
    }
}
