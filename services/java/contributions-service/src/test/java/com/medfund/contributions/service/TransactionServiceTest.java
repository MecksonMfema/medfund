package com.medfund.contributions.service;

import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.TransactionRepository;
import com.medfund.contributions.repository.TransactionTypeRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private ContributionRepository contributionRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private BalanceService balanceService;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private TransactionService transactionService;

    private final String actorId = UUID.randomUUID().toString();

    @BeforeEach
    void setupBalanceMocks() {
        // Transaction recording now looks up the catalogue entry to read the
        // sign and updates the balance. These tests focus on the persistence
        // path; default to "no catalogue entry" so the balance update short-
        // circuits, mirroring an unconfigured tenant.
        lenient().when(transactionTypeRepository.findByCode(any())).thenReturn(Mono.empty());
    }

    @Test
    void findAll_returnsTransactions() {
        var t1 = createTestTransaction();
        var t2 = createTestTransaction();

        when(transactionRepository.findAllOrderByTransactionDateDesc())
            .thenReturn(Flux.just(t1, t2));

        StepVerifier.create(transactionService.findAll())
            .expectNext(t1)
            .expectNext(t2)
            .verifyComplete();

        verify(transactionRepository).findAllOrderByTransactionDateDesc();
    }

    @Test
    void findByContributionId_returnsTransactions() {
        var contributionId = UUID.randomUUID();
        var t1 = createTestTransaction();
        t1.setContributionId(contributionId);

        when(transactionRepository.findByContributionId(contributionId))
            .thenReturn(Flux.just(t1));

        StepVerifier.create(transactionService.findByContributionId(contributionId))
            .assertNext(result -> {
                assertThat(result.getContributionId()).isEqualTo(contributionId);
            })
            .verifyComplete();

        verify(transactionRepository).findByContributionId(contributionId);
    }

    @Test
    void record_validRequest_createsTransaction() {
        var request = new RecordTransactionRequest(
            UUID.randomUUID(), null,
            new BigDecimal("150.00"), "USD",
            "payment", "bank_transfer", "REF-001"
        );

        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(transactionService.record(request, actorId)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getTransactionNumber()).startsWith("TXN-");
                assertThat(saved.getStatus()).isEqualTo("completed");
                assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
                assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                assertThat(saved.getTransactionType()).isEqualTo("payment");
                assertThat(saved.getPaymentMethod()).isEqualTo("bank_transfer");
                assertThat(saved.getReference()).isEqualTo("REF-001");
                assertThat(saved.getContributionId()).isEqualTo(request.contributionId());
                assertThat(saved.getTransactionDate()).isNotNull();
                assertThat(saved.getId()).isNotNull();
                assertThat(saved.getCreatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(transactionRepository).save(any(Transaction.class));
        verify(auditPublisher).publish(any());
    }

    // ---- Helpers ----

    private Transaction createTestTransaction() {
        var t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setTransactionNumber("TXN-00012345");
        t.setContributionId(UUID.randomUUID());
        t.setAmount(new BigDecimal("150.00"));
        t.setCurrencyCode("USD");
        t.setTransactionType("payment");
        t.setPaymentMethod("bank_transfer");
        t.setReference("REF-001");
        t.setStatus("completed");
        t.setTransactionDate(Instant.now());
        t.setCreatedAt(Instant.now());
        t.setCreatedBy(UUID.randomUUID());
        return t;
    }
}
