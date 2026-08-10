package com.medfund.finance.service;

import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository.OutstandingMemberPayableByMember;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRunGeneratorTest {

    @Mock private ProviderBalanceRepository providerBalanceRepository;
    @Mock private MemberPayableBalanceRepository memberPayableBalanceRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentRunItemRepository paymentRunItemRepository;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private PaymentRunGenerator generator;

    @Test
    void populate_noBalances_returnsZero() {
        var run = draftRun("USD");

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.empty());
        when(memberPayableBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.empty());

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(0)
            .verifyComplete();

        verify(paymentRepository, times(0)).save(any());
        verify(paymentRunItemRepository, times(0)).save(any());
    }

    @Test
    void populate_providerBalancesOnly_createsProviderItems() {
        var run = draftRun("USD");
        UUID providerId = UUID.randomUUID();

        var bal = new ProviderBalance();
        bal.setProviderId(providerId);
        bal.setCurrencyCode("USD");
        bal.setOutstandingBalance(new BigDecimal("450.00"));

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.just(bal));
        when(memberPayableBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.empty());
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
    }

    @Test
    void populate_memberPayablesOnly_createsMemberItems() {
        var run = draftRun("USD");
        UUID memberId = UUID.randomUUID();

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.empty());
        when(memberPayableBalanceRepository.findOutstandingByCurrency("USD"))
            .thenReturn(Flux.just(new OutstandingMemberPayableByMember(memberId, "USD", new BigDecimal("120.00"))));
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
    }

    @Test
    void populate_mixedPayees_createsOneItemPerPayee() {
        var run = draftRun("USD");
        UUID providerId = UUID.randomUUID();
        UUID memberId   = UUID.randomUUID();

        var providerBal = new ProviderBalance();
        providerBal.setProviderId(providerId);
        providerBal.setCurrencyCode("USD");
        providerBal.setOutstandingBalance(new BigDecimal("100.00"));

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.just(providerBal));
        when(memberPayableBalanceRepository.findOutstandingByCurrency("USD"))
            .thenReturn(Flux.just(new OutstandingMemberPayableByMember(memberId, "USD", new BigDecimal("50.00"))));
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
            .expectNext(2)
            .verifyComplete();

        verify(paymentRepository, times(2)).save(any());
        verify(paymentRunItemRepository, times(2)).save(any());
    }

    @Test
    void populate_zeroBalanceRow_isSkipped() {
        var run = draftRun("USD");

        var zeroBal = new ProviderBalance();
        zeroBal.setProviderId(UUID.randomUUID());
        zeroBal.setCurrencyCode("USD");
        zeroBal.setOutstandingBalance(BigDecimal.ZERO);

        when(providerBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.just(zeroBal));
        when(memberPayableBalanceRepository.findOutstandingByCurrency("USD")).thenReturn(Flux.empty());

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(0)
            .verifyComplete();

        verify(paymentRepository, times(0)).save(any());
    }

    private PaymentRun draftRun(String currency) {
        var run = new PaymentRun();
        run.setId(UUID.randomUUID());
        run.setRunNumber("RUN-777777");
        run.setStatus("draft");
        run.setCurrencyCode(currency);
        return run;
    }
}
