package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.entity.MemberBalance;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.repository.ProducerCommissionSummary;
import com.medfund.finance.producer.repository.ProducerRepository;
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
import java.time.LocalDate;
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
    @Mock private CommissionTransactionRepository commissionTransactionRepository;
    @Mock private ProducerRepository producerRepository;
    @Mock private FxConverter fxConverter;
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

    // ---- Producer branch (Phase 11 §A Phase 6) ----

    @Test
    void populate_producer_missingPeriod_errors() {
        var run = draftRun("USD", "PRODUCER");

        StepVerifier.create(generator.populate(run)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorMatches(err ->
                err instanceof IllegalArgumentException
                && err.getMessage().contains("periodStart"))
            .verify();

        verifyNoInteractions(commissionTransactionRepository);
    }

    @Test
    void populate_producer_singleProducerSameCurrency_createsItem() {
        var run = draftRun("USD", "PRODUCER");
        UUID producerId = UUID.randomUUID();
        Producer producer = producerWithHomeCurrency(producerId, "USD", null);

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.just(new ProducerCommissionSummary(
                        producerId, "USD", new BigDecimal("450.00"), "USD", 3L)));
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(producer));
        stubPaymentAndItemSaves();

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(1)
            .verifyComplete();

        ArgumentCaptor<Payment> paymentCap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCap.capture());
        assertThat(paymentCap.getValue().getProducerId()).isEqualTo(producerId);
        assertThat(paymentCap.getValue().getPayeeType()).isEqualTo("PRODUCER");
        assertThat(paymentCap.getValue().getAmount()).isEqualByComparingTo("450.00");

        ArgumentCaptor<PaymentRunItem> itemCap = ArgumentCaptor.forClass(PaymentRunItem.class);
        verify(paymentRunItemRepository).save(itemCap.capture());
        assertThat(itemCap.getValue().getProducerId()).isEqualTo(producerId);
        assertThat(itemCap.getValue().getPayeeType()).isEqualTo("PRODUCER");
        assertThat(itemCap.getValue().getWithholdingTaxPct()).isNull();
        verifyNoInteractions(fxConverter);
    }

    @Test
    void populate_producer_multiNativeCurrency_convertsAndSums() {
        var run = draftRun("USD", "PRODUCER");
        UUID producerId = UUID.randomUUID();
        Producer producer = producerWithHomeCurrency(producerId, "USD", null);

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.just(
                        new ProducerCommissionSummary(producerId, "USD",
                                new BigDecimal("100.00"), "USD", 1L),
                        new ProducerCommissionSummary(producerId, "USD",
                                new BigDecimal("200.00"), "EUR", 1L)));
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(producer));
        // 200 EUR → USD @ 1.1 = 220.00; grand total should be 320.00.
        when(fxConverter.convert(eqAmt("200.00"), eqStr("EUR"), eqStr("USD"), any(), any()))
                .thenReturn(Mono.just(new BigDecimal("220.00")));
        stubPaymentAndItemSaves();

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(1)
            .verifyComplete();

        ArgumentCaptor<Payment> cap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualByComparingTo("320.00");
    }

    @Test
    void populate_producer_missingFxRate_errors() {
        var run = draftRun("USD", "PRODUCER");
        UUID producerId = UUID.randomUUID();
        Producer producer = producerWithHomeCurrency(producerId, "USD", null);

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.just(new ProducerCommissionSummary(
                        producerId, "USD", new BigDecimal("100.00"), "ZWL", 1L)));
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(producer));
        when(fxConverter.convert(any(), eqStr("ZWL"), eqStr("USD"), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("No exchange rate for ZWL->USD")));

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorMessage("No exchange rate for ZWL->USD")
            .verify();

        verify(paymentRepository, times(0)).save(any());
    }

    @Test
    void populate_producer_whtConfigured_deductsFromGross() {
        var run = draftRun("USD", "PRODUCER");
        UUID producerId = UUID.randomUUID();
        // 10% WHT → 1000 * 0.10 = 100 withheld; net = 900.00
        Producer producer = producerWithHomeCurrency(producerId, "USD", new BigDecimal("10.00"));

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.just(new ProducerCommissionSummary(
                        producerId, "USD", new BigDecimal("1000.00"), "USD", 5L)));
        when(producerRepository.findById(producerId)).thenReturn(Mono.just(producer));
        stubPaymentAndItemSaves();

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(1)
            .verifyComplete();

        ArgumentCaptor<Payment> paymentCap = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCap.capture());
        assertThat(paymentCap.getValue().getAmount()).isEqualByComparingTo("900.00");

        ArgumentCaptor<PaymentRunItem> itemCap = ArgumentCaptor.forClass(PaymentRunItem.class);
        verify(paymentRunItemRepository).save(itemCap.capture());
        assertThat(itemCap.getValue().getWithholdingTaxPct()).isEqualByComparingTo("10.00");
    }

    @Test
    void populate_producer_noAccruals_zeroCount() {
        var run = draftRun("USD", "PRODUCER");

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(0)
            .verifyComplete();

        verify(paymentRepository, times(0)).save(any());
        verifyNoInteractions(producerRepository);
    }

    @Test
    void populate_producer_multipleProducers_yieldsOneItemPerProducer() {
        var run = draftRun("USD", "PRODUCER");
        UUID producerA = UUID.randomUUID();
        UUID producerB = UUID.randomUUID();

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.just(
                        new ProducerCommissionSummary(producerA, "USD",
                                new BigDecimal("100.00"), "USD", 1L),
                        new ProducerCommissionSummary(producerB, "USD",
                                new BigDecimal("200.00"), "USD", 2L)));
        when(producerRepository.findById(producerA))
                .thenReturn(Mono.just(producerWithHomeCurrency(producerA, "USD", null)));
        when(producerRepository.findById(producerB))
                .thenReturn(Mono.just(producerWithHomeCurrency(producerB, "USD", null)));
        stubPaymentAndItemSaves();

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectNext(2)
            .verifyComplete();

        verify(paymentRepository, times(2)).save(any());
        verify(paymentRunItemRepository, times(2)).save(any());
    }

    @Test
    void populate_producer_missingProducer_errors() {
        var run = draftRun("USD", "PRODUCER");
        UUID producerId = UUID.randomUUID();

        when(commissionTransactionRepository.aggregateForPayout(eqStr("USD"), any(), any()))
                .thenReturn(Flux.just(new ProducerCommissionSummary(
                        producerId, "USD", new BigDecimal("100.00"), "USD", 1L)));
        when(producerRepository.findById(producerId)).thenReturn(Mono.empty());

        StepVerifier.create(generator.populate(run,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30))
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorMatches(err ->
                err instanceof IllegalStateException
                && err.getMessage().contains("Producer not found"))
            .verify();

        verify(paymentRepository, times(0)).save(any());
    }

    // ---- Test helpers ----

    private Producer producerWithHomeCurrency(UUID id, String home, BigDecimal wht) {
        var p = new Producer();
        p.setId(id);
        p.setProducerCode("P-" + id.toString().substring(0, 8));
        p.setName("Test Producer");
        p.setHomeCurrency(home);
        p.setWhtPctOverride(wht);
        return p;
    }

    private void stubPaymentAndItemSaves() {
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
    }

    private static String eqStr(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }

    private static BigDecimal eqAmt(String s) {
        return org.mockito.ArgumentMatchers.argThat(
                arg -> arg != null && arg.compareTo(new BigDecimal(s)) == 0);
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
