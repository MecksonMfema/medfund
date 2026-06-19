package com.medfund.contributions.service;

import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.dto.TransactionFilterParams;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.TransactionQueryRepository;
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
    private TransactionQueryRepository queryRepository;

    @Mock
    private BalanceService balanceService;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private TransactionService transactionService;

    private final String actorId = UUID.randomUUID().toString();
    private final String actorEmail = "actor@test.example";

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

        StepVerifier.create(transactionService.record(request, actorId, actorEmail)
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

    @Test
    void search_emptyFilters_returnsAllPaged() {
        var t1 = createTestTransaction();
        var t2 = createTestTransaction();
        var params = new TransactionFilterParams(null, null, null, null, null,
                null, null, null, 0, 20);

        when(queryRepository.search(any(TransactionFilterParams.class), eq(20), eq(0)))
                .thenReturn(Flux.just(t1, t2));
        when(queryRepository.count(any(TransactionFilterParams.class)))
                .thenReturn(Mono.just(2L));

        StepVerifier.create(transactionService.search(params))
                .assertNext(page -> {
                    assertThat(page.content()).containsExactly(t1, t2);
                    assertThat(page.total()).isEqualTo(2L);
                    assertThat(page.page()).isEqualTo(0);
                    assertThat(page.size()).isEqualTo(20);
                    assertThat(page.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void search_clampsSize_andComputesOffset() {
        var params = new TransactionFilterParams("USD", "ORDINARY", null, null, null,
                null, null, "REF", 2, 500); // size 500 should be capped to 100

        when(queryRepository.search(any(TransactionFilterParams.class), eq(100), eq(200)))
                .thenReturn(Flux.empty());
        when(queryRepository.count(any(TransactionFilterParams.class)))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(transactionService.search(params))
                .assertNext(page -> {
                    assertThat(page.content()).isEmpty();
                    assertThat(page.total()).isEqualTo(0L);
                    assertThat(page.size()).isEqualTo(100);
                    assertThat(page.page()).isEqualTo(2);
                })
                .verifyComplete();

        verify(queryRepository).search(any(TransactionFilterParams.class), eq(100), eq(200));
        verify(queryRepository).count(any(TransactionFilterParams.class));
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
