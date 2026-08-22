package com.medfund.contributions.service;

import com.medfund.contributions.dto.GenerateBillingRequest;
import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.exception.ContributionNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.BillingCycleConfigRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
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
    private com.medfund.contributions.repository.ContributionQueryRepository contributionQueryRepository;

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

    @Mock
    private BillingCycleConfigRepository billingCycleConfigRepository;

    @Mock
    private BalanceService balanceService;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private AiPricingClient aiPricingClient;

    @Mock
    private InvoiceSnapshotService invoiceSnapshotService;

    @InjectMocks
    private BillingService billingService;

    @BeforeEach
    void setupBalanceMocks() {
        // BalanceService is a hook on every contribution write; the tests in
        // this class care about the BillingService logic, so always succeed.
        lenient().when(balanceService.applyContributionDebit(any())).thenReturn(Mono.empty());
        lenient().when(balanceService.applyContributionPaid(any())).thenReturn(Mono.empty());
    }

    private final String actorId = UUID.randomUUID().toString();
    private final String actorEmail = "actor@test.example";

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
        var schemeId = UUID.randomUUID();
        var request = new GenerateBillingRequest(
            schemeId,
            UUID.randomUUID(),
            LocalDate.now().withDayOfMonth(1),
            LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1),
            null
        );

        var parentScheme = new com.medfund.contributions.entity.Scheme();
        parentScheme.setId(schemeId);
        parentScheme.setCurrencyCode("USD");

        when(schemeRepository.findById(schemeId)).thenReturn(Mono.just(parentScheme));
        // Stamp a DB-generated id on save so downstream getId().toString()
        // calls in publishAudit don't NPE — mirrors what the real R2DBC
        // pipeline does via the table's gen_random_uuid() default.
        when(contributionRepository.save(any(Contribution.class)))
            .thenAnswer(inv -> {
                Contribution c = inv.getArgument(0);
                if (c.getId() == null) c.setId(UUID.randomUUID());
                return Mono.just(c);
            });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishBillingGenerated(any(), any(), any(), anyInt()))
            .thenReturn(Mono.empty());
        // Pricing rules are out of scope for this test — return an empty fact
        // so generateBilling proceeds straight to save.
        when(pricingService.price(any(Contribution.class)))
            .thenReturn(Mono.just(new com.medfund.rules.fact.ContributionFact()));

        StepVerifier.create(billingService.generateBilling(request, actorId, actorEmail)
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
        // Every contribution write must pair with a running-balance
        // debit; without this the customer's outstanding drifts down
        // from what the contributions table says they owe. Locked in
        // after a 2026-07 audit found the pairing missing.
        verify(balanceService).applyContributionDebit(any(Contribution.class));
    }

    @Test
    void recordPayment_existingContribution_setsStatusPaid() {
        var contribution = createTestContribution();
        var scheme = new com.medfund.contributions.entity.Scheme();
        scheme.setId(contribution.getSchemeId());
        scheme.setInsuranceLine("HEALTH");
        scheme.setCurrencyCode("USD");

        when(contributionRepository.findById(contribution.getId()))
            .thenReturn(Mono.just(contribution));
        when(contributionRepository.save(any(Contribution.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(schemeRepository.findById(contribution.getSchemeId()))
            .thenReturn(Mono.just(scheme));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishContributionPaid(any(), any(), any(),
                any(), any(), any(), any()))
            .thenReturn(Mono.empty());

        StepVerifier.create(billingService.recordPayment(
                    contribution.getId(), "bank_transfer", "PAY-REF-001", actorId, actorEmail)
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
        // Phase 6: publisher now carries currency/insuranceLine/paidAt/tenantId
        // so the reinsurance premium-cession consumer can route.
        verify(eventPublisher).publishContributionPaid(any(), any(), any(),
                any(), any(), any(), any());
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
            .thenAnswer(inv -> {
                Invoice i = inv.getArgument(0);
                if (i.getId() == null) i.setId(UUID.randomUUID());
                return Mono.just(i);
            });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishInvoiceIssued(any(ContributionEventPublisher.InvoiceIssuedPayload.class)))
            .thenReturn(Mono.empty());

        StepVerifier.create(billingService.generateInvoice(
                    groupId, schemeId, periodStart, periodEnd, totalAmount, "USD", actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                // Prefix pinned so the CS- rename (2026-07) doesn't silently
                // regress. Historical INV- rows remain valid — only new ones
                // are guaranteed CS-.
                assertThat(saved.getInvoiceNumber()).startsWith("CS-");
                assertThat(saved.getStatus()).isEqualTo("issued");
                assertThat(saved.getGroupId()).isEqualTo(groupId);
                assertThat(saved.getSchemeId()).isEqualTo(schemeId);
                assertThat(saved.getTotalAmount()).isEqualByComparingTo(totalAmount);
                assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                // Due date is end-of-billing-month (was previously periodEnd
                // + 30 days). Guarding this so a copy-paste from the finance
                // module doesn't silently re-introduce the 30-day drift.
                assertThat(saved.getDueDate()).isEqualTo(periodEnd);
                assertThat(saved.getIssuedAt()).isNotNull();
                assertThat(saved.getId()).isNotNull();
                assertThat(saved.getCreatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(invoiceRepository).existsByInvoiceNumber(any());
        verify(invoiceRepository).save(any(Invoice.class));
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishInvoiceIssued(any(ContributionEventPublisher.InvoiceIssuedPayload.class));
    }

    // ---- Helpers ----

    // ------------------------------------------------------------------
    // commitBilling gates: cooldown + one-commit-per-period + preview no-persist
    // ------------------------------------------------------------------

    @Test
    void commitBilling_insideCooldownWindow_errorsWithCooldownException() {
        // Cooldown 24h + last_committed_at = 1 hour ago → 23-ish hours
        // remaining. commitBilling must reject before ever touching the
        // ledger — no contribution row, no invoice, no publisher.
        var cfg = cycleConfigWithCooldown((short) 24, Instant.now().minusSeconds(3600));
        when(billingCycleConfigRepository.findById(com.medfund.contributions.entity.BillingCycleConfig.SINGLETON_ID))
                .thenReturn(Mono.just(cfg));

        var req = new com.medfund.contributions.dto.CommitBillingRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                java.util.List.of(), java.util.List.of(), "HEALTH");

        StepVerifier.create(billingService.commitBilling(req, actorId, actorEmail))
                .expectError(com.medfund.contributions.exception.BillingCooldownException.class)
                .verify();

        verify(contributionRepository, never()).save(any());
        verify(contributionRepository, never()).countByPeriodAndLine(any(), any(), any());
    }

    @Test
    void commitBilling_periodAlreadyCommitted_errorsWithoutPersist() {
        // No cooldown active (last_committed_at NULL) but a prior commit
        // for this exact (period, line) already landed rows. The count-
        // > 0 branch must abort with BillingPeriodAlreadyCommittedException.
        // Guards the "operator clicks re-commit a week later" flow.
        var cfg = cycleConfigWithCooldown((short) 0, null);
        when(billingCycleConfigRepository.findById(com.medfund.contributions.entity.BillingCycleConfig.SINGLETON_ID))
                .thenReturn(Mono.just(cfg));
        when(contributionRepository.countByPeriodAndLine(
                any(LocalDate.class), any(LocalDate.class), anyString()))
                .thenReturn(Mono.just(3L));

        var req = new com.medfund.contributions.dto.CommitBillingRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                java.util.List.of(), java.util.List.of(), "HEALTH");

        StepVerifier.create(billingService.commitBilling(req, actorId, actorEmail))
                .expectError(com.medfund.contributions.exception.BillingPeriodAlreadyCommittedException.class)
                .verify();

        verify(contributionRepository, never()).save(any());
    }

    @Test
    void previewBilling_neverCallsContributionRepositorySave() {
        // The single most-load-bearing invariant of the wizard: preview
        // is read-only. If a refactor accidentally lets preview reach
        // the persist branch, an operator opening the wizard doubles up
        // an entire tenant's month. This test locks it in.
        var cfg = cycleConfigWithCooldown((short) 0, null);
        when(billingCycleConfigRepository.findById(com.medfund.contributions.entity.BillingCycleConfig.SINGLETON_ID))
                .thenReturn(Mono.just(cfg));
        stubMembershipModelDbCall();

        var req = new com.medfund.contributions.dto.PreviewBillingRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                java.util.List.of(), java.util.List.of(), "HEALTH");

        StepVerifier.create(billingService.previewBilling(req)
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "not-a-uuid")))
                .assertNext(resp -> assertThat(resp.totalRows()).isZero())
                .verifyComplete();

        verify(contributionRepository, never()).save(any());
        verify(eventPublisher, never()).publishBillingGenerated(
                any(), any(), any(), anyInt());
    }

    private static com.medfund.contributions.entity.BillingCycleConfig cycleConfigWithCooldown(
            short hours, Instant lastCommit) {
        var cfg = new com.medfund.contributions.entity.BillingCycleConfig();
        cfg.setId(com.medfund.contributions.entity.BillingCycleConfig.SINGLETON_ID);
        cfg.setFrequency("MONTHLY");
        cfg.setDayOfMonth((short) 1);
        cfg.setCommitCooldownHours(hours);
        cfg.setLastCommittedAt(lastCommit);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private void stubMembershipModelDbCall() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<String> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(databaseClient.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just("BOTH"));
    }

    // ------------------------------------------------------------------
    // runAutoBilling: scheduler contract with commitBilling
    // ------------------------------------------------------------------

    @Test
    void runAutoBilling_delegatesToCommitBilling_withCurrentMonthPeriod() {
        // Locks in the auto-billing contract: current calendar month,
        // no group/member/line filter (multi-line tenants override via
        // per-line cron entries), SYSTEM actor on the audit trail.
        // A regression that shifts the period (e.g. previous month due
        // to a timezone bug) would silently miss the current month for
        // every tenant.
        var spy = org.mockito.Mockito.spy(billingService);
        var response = new com.medfund.contributions.dto.BillingCommitResponse(
                5, java.util.Map.of("USD", new BigDecimal("500.00")),
                Instant.now(), 1, 2, "BOTH");
        org.mockito.Mockito.doReturn(Mono.just(response))
                .when(spy).commitBilling(any(), anyString(), anyString());

        StepVerifier.create(spy.runAutoBilling()).verifyComplete();

        var reqCap = org.mockito.ArgumentCaptor.forClass(
                com.medfund.contributions.dto.CommitBillingRequest.class);
        verify(spy).commitBilling(reqCap.capture(),
                eq(com.medfund.shared.audit.AuditActor.SYSTEM_ID),
                eq(com.medfund.shared.audit.AuditActor.SYSTEM_EMAIL));
        var req = reqCap.getValue();
        LocalDate today = LocalDate.now();
        assertThat(req.periodStart()).isEqualTo(today.withDayOfMonth(1));
        assertThat(req.periodEnd()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()));
        // No filter — auto-billing bills the whole tenant.
        assertThat(req.groupIds()).isNull();
        assertThat(req.memberIds()).isNull();
        assertThat(req.insuranceLine()).isNull();
    }

    @Test
    void runAutoBilling_cooldownException_propagatesUpwards() {
        // The scheduler's error-handling in BillingCycleExecutor marks
        // the run FAILED and moves on — precisely because the exception
        // propagates from here. If runAutoBilling ever wraps this in
        // onErrorResume it would silently swallow a real cooldown mismatch.
        var spy = org.mockito.Mockito.spy(billingService);
        org.mockito.Mockito.doReturn(Mono.error(
                        new com.medfund.contributions.exception.BillingCooldownException(120)))
                .when(spy).commitBilling(any(), anyString(), anyString());

        StepVerifier.create(spy.runAutoBilling())
                .expectError(com.medfund.contributions.exception.BillingCooldownException.class)
                .verify();
    }

    @Test
    void runAutoBilling_periodAlreadyCommitted_propagatesUpwards() {
        // Same failure contract for the period-guard exception.
        var spy = org.mockito.Mockito.spy(billingService);
        org.mockito.Mockito.doReturn(Mono.error(
                        new com.medfund.contributions.exception.BillingPeriodAlreadyCommittedException(
                                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "HEALTH", 42L)))
                .when(spy).commitBilling(any(), anyString(), anyString());

        StepVerifier.create(spy.runAutoBilling())
                .expectError(com.medfund.contributions.exception.BillingPeriodAlreadyCommittedException.class)
                .verify();
    }

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
