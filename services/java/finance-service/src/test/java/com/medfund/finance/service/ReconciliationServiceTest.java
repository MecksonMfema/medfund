package com.medfund.finance.service;

import com.medfund.finance.dto.CreateReconciliationRequest;
import com.medfund.finance.entity.BankReconciliation;
import com.medfund.finance.repository.BankReconciliationRepository;
import com.medfund.finance.repository.PaymentRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private BankReconciliationRepository bankReconciliationRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private ReconciliationService reconciliationService;

    @Test
    void findAll_returnsReconciliations() {
        var r1 = createTestReconciliation();
        var r2 = createTestReconciliation();
        when(bankReconciliationRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(r1, r2));

        StepVerifier.create(reconciliationService.findAll())
                .expectNext(r1)
                .expectNext(r2)
                .verifyComplete();

        verify(bankReconciliationRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void create_matchingAmounts_setsStatusMatched() {
        var request = new CreateReconciliationRequest(
                "REF-001",
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                "USD",
                LocalDate.of(2026, 3, 28),
                "Monthly reconciliation"
        );
        String actorId = UUID.randomUUID().toString();

        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.create(request, actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("matched");
                    assertThat(saved.getDifference()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(saved.getReferenceNumber()).isEqualTo("REF-001");
                    assertThat(saved.getStatementAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
                    assertThat(saved.getSystemAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
                    assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                    assertThat(saved.getStatementDate()).isEqualTo(LocalDate.of(2026, 3, 28));
                    assertThat(saved.getNotes()).isEqualTo("Monthly reconciliation");
                    assertThat(saved.getCreatedBy()).isNotNull();
                })
                .verifyComplete();

        verify(bankReconciliationRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_mismatchedAmounts_setsStatusUnmatched() {
        var request = new CreateReconciliationRequest(
                "REF-002",
                new BigDecimal("10000.00"),
                new BigDecimal("9500.00"),
                "USD",
                LocalDate.of(2026, 3, 28),
                null
        );
        String actorId = UUID.randomUUID().toString();

        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.create(request, actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("unmatched");
                    assertThat(saved.getDifference()).isEqualByComparingTo(new BigDecimal("500.00"));
                    assertThat(saved.getStatementAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
                    assertThat(saved.getSystemAmount()).isEqualByComparingTo(new BigDecimal("9500.00"));
                })
                .verifyComplete();

        verify(bankReconciliationRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void markMatched_existingRecord_setsStatusMatched() {
        var recon = createTestReconciliation();
        recon.setStatus("unmatched");
        String actorId = UUID.randomUUID().toString();

        when(bankReconciliationRepository.findById(recon.getId())).thenReturn(Mono.just(recon));
        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.markMatched(recon.getId(), actorId, "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("matched");
                    assertThat(saved.getReconciledAt()).isNotNull();
                    assertThat(saved.getReconciledBy()).isNotNull();
                })
                .verifyComplete();

        verify(bankReconciliationRepository).findById(recon.getId());
        verify(bankReconciliationRepository).save(any());
        verify(auditPublisher).publish(any());
    }

    @Test
    void create_omittedSystemAmount_resolvesFromPayments() {
        var request = new CreateReconciliationRequest(
                "REF-AUTO-001",
                new BigDecimal("500.00"),
                null,
                "USD",
                LocalDate.of(2026, 5, 10),
                null);

        when(paymentRepository.sumPaidUpTo(eq("USD"), any(Instant.class)))
                .thenReturn(Mono.just(new BigDecimal("500.00")));
        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getSystemAmount()).isEqualByComparingTo("500.00");
                    assertThat(saved.getStatus()).isEqualTo("matched");
                })
                .verifyComplete();

        verify(paymentRepository).sumPaidUpTo(eq("USD"), any(Instant.class));
    }

    @Test
    void create_omittedSystemAmount_emptyResult_defaultsToZero() {
        var request = new CreateReconciliationRequest(
                "REF-AUTO-002",
                new BigDecimal("75.00"),
                null,
                "USD",
                LocalDate.of(2026, 5, 10),
                null);

        when(paymentRepository.sumPaidUpTo(eq("USD"), any(Instant.class))).thenReturn(Mono.empty());
        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getSystemAmount()).isEqualByComparingTo("0");
                    assertThat(saved.getStatus()).isEqualTo("unmatched");
                    assertThat(saved.getDifference()).isEqualByComparingTo("75.00");
                })
                .verifyComplete();
    }

    @Test
    void create_explicitSystemAmount_skipsAutoResolve() {
        var request = new CreateReconciliationRequest(
                "REF-EXPLICIT",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                "USD",
                LocalDate.now(),
                null);

        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.create(request, UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("matched"))
                .verifyComplete();

        verify(paymentRepository, never()).sumPaidUpTo(any(), any());
    }

    @Test
    void markInvestigating_unmatched_flipsStatus() {
        var recon = createTestReconciliation();
        recon.setStatus("unmatched");

        when(bankReconciliationRepository.findById(recon.getId())).thenReturn(Mono.just(recon));
        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.markInvestigating(recon.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("investigating"))
                .verifyComplete();
    }

    @Test
    void markResolved_investigating_stampsReconciled() {
        var recon = createTestReconciliation();
        recon.setStatus("investigating");

        when(bankReconciliationRepository.findById(recon.getId())).thenReturn(Mono.just(recon));
        when(bankReconciliationRepository.save(any())).thenAnswer(inv -> {
            BankReconciliation e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(UUID.randomUUID()); }
            return Mono.just(e);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                reconciliationService.markResolved(recon.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("resolved");
                    assertThat(saved.getReconciledAt()).isNotNull();
                    assertThat(saved.getReconciledBy()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void markResolved_alreadyResolved_isIdempotentNoSave() {
        var recon = createTestReconciliation();
        recon.setStatus("resolved");

        when(bankReconciliationRepository.findById(recon.getId())).thenReturn(Mono.just(recon));

        StepVerifier.create(
                reconciliationService.markResolved(recon.getId(), UUID.randomUUID().toString(), "actor@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("resolved"))
                .verifyComplete();

        verify(bankReconciliationRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    // ---- Helper ----

    private BankReconciliation createTestReconciliation() {
        var r = new BankReconciliation();
        r.setId(UUID.randomUUID());
        r.setReferenceNumber("REF-001");
        r.setStatementAmount(new BigDecimal("10000.00"));
        r.setSystemAmount(new BigDecimal("10000.00"));
        r.setDifference(BigDecimal.ZERO);
        r.setCurrencyCode("USD");
        r.setStatus("matched");
        r.setStatementDate(LocalDate.of(2026, 3, 28));
        r.setCreatedAt(Instant.now());
        r.setCreatedBy(UUID.randomUUID());
        return r;
    }
}
