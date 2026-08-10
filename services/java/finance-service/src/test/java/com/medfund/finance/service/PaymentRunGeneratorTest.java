package com.medfund.finance.service;

import com.medfund.finance.entity.MemberBalance;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.repository.MemberBalanceRepository;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.ProviderBalanceRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRunGeneratorTest {

    @Mock private ProviderBalanceRepository providerBalanceRepository;
    @Mock private MemberBalanceRepository memberBalanceRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentRunItemRepository paymentRunItemRepository;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private PaymentRunGenerator generator;

    @Test
    void populate_provider_noBalances_returnsZero() {
        var run = draftRun("USD", "PROVIDER");

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.empty());

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(0)
            .verifyComplete();

        verify(paymentRepository, times(0)).save(any());
        verify(paymentRunItemRepository, times(0)).save(any());
        verifyNoInteractions(memberBalanceRepository);
    }

    @Test
    void populate_provider_createsProviderItems() {
        var run = draftRun("USD", "PROVIDER");
        UUID providerId = UUID.randomUUID();

        var bal = new ProviderBalance();
        bal.setProviderId(providerId);
        bal.setCurrencyCode("USD");
        bal.setOutstandingBalance(new BigDecimal("450.00"));

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.just(bal));
        when(paymentRepository.existsByPaymentNumber(anyString())).thenReturn(Mono.just(false));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(paymentRunItemRepository.save(any())).thenAnswer(inv -> {
            PaymentRunItem saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(1)
            .verifyComplete();

        ArgumentCaptor<Payment> paymentCap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCap.capture());
        assertThat(paymentCap.getValue().getProviderId()).isEqualTo(providerId);
        assertThat(paymentCap.getValue().getMemberId()).isNull();
        assertThat(paymentCap.getValue().getPayeeType()).isEqualTo("PROVIDER");
        assertThat(paymentCap.getValue().getAmount()).isEqualByComparingTo("450.00");

        ArgumentCaptor<PaymentRunItem> itemCap = ArgumentCaptor.forClass(PaymentRunItem.class);
        verify(paymentRunItemRepository).save(itemCap.capture());
        assertThat(itemCap.getValue().getPayeeType()).isEqualTo("PROVIDER");
        assertThat(itemCap.getValue().getProviderId()).isEqualTo(providerId);
        assertThat(itemCap.getValue().getPaymentRunId()).isEqualTo(run.getId());
        verifyNoInteractions(memberBalanceRepository);
    }

    @Test
    void populate_member_createsMemberItems() {
        var run = draftRun("USD", "MEMBER");
        UUID memberId = UUID.randomUUID();

        var bal = new MemberBalance();
        bal.setMemberId(memberId);
        bal.setCurrencyCode("USD");
        bal.setOutstandingBalance(new BigDecimal("120.00"));

        when(memberBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.just(bal));
        when(paymentRepository.existsByPaymentNumber(anyString())).thenReturn(Mono.just(false));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            Payment saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(paymentRunItemRepository.save(any())).thenAnswer(inv -> {
            PaymentRunItem saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(1)
            .verifyComplete();

        ArgumentCaptor<Payment> paymentCap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCap.capture());
        assertThat(paymentCap.getValue().getMemberId()).isEqualTo(memberId);
        assertThat(paymentCap.getValue().getProviderId()).isNull();
        assertThat(paymentCap.getValue().getPayeeType()).isEqualTo("MEMBER");
        assertThat(paymentCap.getValue().getAmount()).isEqualByComparingTo("120.00");

        ArgumentCaptor<PaymentRunItem> itemCap = ArgumentCaptor.forClass(PaymentRunItem.class);
        verify(paymentRunItemRepository).save(itemCap.capture());
        assertThat(itemCap.getValue().getPayeeType()).isEqualTo("MEMBER");
        assertThat(itemCap.getValue().getMemberId()).isEqualTo(memberId);
        verifyNoInteractions(providerBalanceRepository);
    }

    @Test
    void populate_provider_zeroBalanceRow_isSkipped() {
        var run = draftRun("USD", "PROVIDER");

        var zeroBal = new ProviderBalance();
        zeroBal.setProviderId(UUID.randomUUID());
        zeroBal.setCurrencyCode("USD");
        zeroBal.setOutstandingBalance(BigDecimal.ZERO);

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.just(zeroBal));

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(0)
            .verifyComplete();

        verify(paymentRepository, times(0)).save(any());
    }

    private PaymentRun draftRun(String currency, String payeeType) {
        var run = new PaymentRun();
        run.setId(UUID.randomUUID());
        run.setRunNumber("RUN-777777");
        run.setStatus("draft");
        run.setCurrencyCode(currency);
        run.setPayeeType(payeeType);
        return run;
    }
}
