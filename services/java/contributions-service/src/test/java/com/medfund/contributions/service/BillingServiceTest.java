package com.medfund.contributions.service;

import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.exception.ContributionNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.SchemeRepository;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock
    private ContributionRepository contributionRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private SchemeRepository schemeRepository;

    @Mock
    private AgeGroupRepository ageGroupRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private ContributionEventPublisher eventPublisher;

    @Mock
    private ContributionPricingService pricingService;

    @InjectMocks
    private BillingService billingService;

    private final String actorId = UUID.randomUUID().toString();

    @Test
    void findContributionsByMemberId_returnsContributions() {
        var memberId = UUID.randomUUID();
        var c1 = createTestContribution();
        c1.setMemberId(memberId);
        var c2 = createTestContribution();
        c2.setMemberId(memberId);

        when(contributionRepository.findByMemberId(memberId)).thenReturn(Flux.just(c1, c2));

        StepVerifier.create(billingService.findContributionsByMemberId(memberId))
            .expectNext(c1)
            .expectNext(c2)
            .verifyComplete();

        verify(contributionRepository).findByMemberId(memberId);
    }

    @Test
    void findContributionById_existing_returnsContribution() {
        var contribution = createTestContribution();

        when(contributionRepository.findById(contribution.getId())).thenReturn(Mono.just(contribution));

        StepVerifier.create(billingService.findContributionById(contribution.getId()))
            .assertNext(result -> {
                assertThat(result.getId()).isEqualTo(contribution.getId());
                assertThat(result.getStatus()).isEqualTo("pending");
            })
            .verifyComplete();

        verify(contributionRepository).findById(contribution.getId());
    }

    @Test
    void findContributionById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();

        when(contributionRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(billingService.findContributionById(id))
            .expectError(ContributionNotFoundException.class)
            .verify();

        verify(contributionRepository).findById(id);
    }

    @Test
    void generateBilling_validRequest_createsContribution() {
        var request = new GenerateBillingRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.now().withDayOfMonth(1),
            LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1),
            null
        );

        when(contributionRepository.save(any(Contribution.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishBillingGenerated(any(), any(), any(), anyInt()))
            .thenReturn(Mono.empty());
        // Pricing rules are out of scope for this test — return an empty fact
        // so generateBilling proceeds straight to save.
        when(pricingService.price(any(Contribution.class)))
            .thenReturn(Mono.just(new com.medfund.rules.fact.ContributionFact()));

        StepVerifier.create(billingService.generateBilling(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(count -> assertThat(count).isEqualTo(1L))
            .verifyComplete();

        verify(contributionRepository).save(argThat(contribution -> {
            assertThat(contribution.getStatus()).isEqualTo("pending");
            assertThat(contribution.getSchemeId()).isEqualTo(request.schemeId());
            assertThat(contribution.getPeriodStart()).isEqualTo(request.periodStart());
            assertThat(contribution.getPeriodEnd()).isEqualTo(request.periodEnd());
            assertThat(contribution.getCreatedBy()).isEqualTo(UUID.fromString(actorId));
            return true;
        }));
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishBillingGenerated(any(), any(), any(), anyInt());
    }

    @Test
    void recordPayment_existingContribution_setsStatusPaid() {
        var contribution = createTestContribution();

        when(contributionRepository.findById(contribution.getId()))
            .thenReturn(Mono.just(contribution));
        when(contributionRepository.save(any(Contribution.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishContributionPaid(any(), any(), any()))
            .thenReturn(Mono.empty());

        StepVerifier.create(billingService.recordPayment(
                    contribution.getId(), "bank_transfer", "PAY-REF-001", actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getStatus()).isEqualTo("paid");
                assertThat(saved.getPaymentMethod()).isEqualTo("bank_transfer");
                assertThat(saved.getPaymentReference()).isEqualTo("PAY-REF-001");
                assertThat(saved.getPaidAt()).isNotNull();
                assertThat(saved.getUpdatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(contributionRepository).findById(contribution.getId());
        verify(contributionRepository).save(any(Contribution.class));
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishContributionPaid(any(), any(), any());
    }

    @Test
    void generateInvoice_validRequest_createsInvoice() {
        var groupId = UUID.randomUUID();
        var schemeId = UUID.randomUUID();
        var periodStart = LocalDate.now().withDayOfMonth(1);
        var periodEnd = periodStart.plusMonths(1).minusDays(1);
        var totalAmount = new BigDecimal("1500.00");

        when(invoiceRepository.existsByInvoiceNumber(any())).thenReturn(Mono.just(false));
        when(invoiceRepository.save(any(Invoice.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishInvoiceIssued(any(), any(), any()))
            .thenReturn(Mono.empty());

        StepVerifier.create(billingService.generateInvoice(
                    groupId, schemeId, periodStart, periodEnd, totalAmount, "USD", actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getInvoiceNumber()).startsWith("INV-");
                assertThat(saved.getStatus()).isEqualTo("issued");
                assertThat(saved.getGroupId()).isEqualTo(groupId);
                assertThat(saved.getSchemeId()).isEqualTo(schemeId);
                assertThat(saved.getTotalAmount()).isEqualByComparingTo(totalAmount);
                assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                assertThat(saved.getDueDate()).isEqualTo(periodEnd.plusDays(30));
                assertThat(saved.getIssuedAt()).isNotNull();
                assertThat(saved.getId()).isNotNull();
                assertThat(saved.getCreatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(invoiceRepository).existsByInvoiceNumber(any());
        verify(invoiceRepository).save(any(Invoice.class));
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishInvoiceIssued(any(), any(), any());
    }

    // ---- Helpers ----

    private Contribution createTestContribution() {
        var c = new Contribution();
        c.setId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setSchemeId(UUID.randomUUID());
        c.setAmount(new BigDecimal("150.00"));
        c.setCurrencyCode("USD");
        c.setPeriodStart(LocalDate.now().withDayOfMonth(1));
        c.setPeriodEnd(LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1));
        c.setStatus("pending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        c.setCreatedBy(UUID.randomUUID());
        c.setUpdatedBy(UUID.randomUUID());
        return c;
    }
}
