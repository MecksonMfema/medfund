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

    @Mock
    private ContributionEventPublisher eventPublisher;

    @Mock
    private org.springframework.r2dbc.core.DatabaseClient db;

    @InjectMocks
    private TransactionService transactionService;

    private final String actorId = UUID.randomUUID().toString();
    private final String actorEmail = "actor@test.example";

    /**
     * Stub the DatabaseClient fluent chain used by resolveRecipientName
     * so it emits the given name. Structure mirrors the production
     * chain: db.sql(...).bind(...).map(...).one() → Mono&lt;String&gt;.
     */
    @SuppressWarnings("unchecked")
    private void stubRecipientNameLookup(String name) {
        var spec = org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec.class);
        var fetchSpec = org.mockito.Mockito.mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        org.mockito.Mockito.lenient().when(db.sql(org.mockito.ArgumentMatchers.anyString())).thenReturn(spec);
        org.mockito.Mockito.lenient().when(spec.bind(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(spec);
        org.mockito.Mockito.lenient().when(spec.map(org.mockito.ArgumentMatchers.any(java.util.function.Function.class)))
                .thenReturn(fetchSpec);
        org.mockito.Mockito.lenient().when(fetchSpec.one()).thenReturn(Mono.just(name));
    }

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
    void record_paymentAgainstGroup_publishesReceiptEvent() {
        UUID groupId = UUID.randomUUID();
        var request = new RecordTransactionRequest(
            groupId, null,
            new BigDecimal("150.00"), "USD",
            "PAYMENT", "bank_transfer", "REF-001", null
        );

        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(inv -> {
                Transaction t = inv.getArgument(0);
                if (t.getId() == null) t.setId(UUID.randomUUID());
                return Mono.just(t);
            });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishTransactionRecorded(any())).thenReturn(Mono.empty());
        stubRecipientNameLookup("Acme Corp");

        StepVerifier.create(transactionService.record(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> {
                assertThat(saved.getTransactionNumber()).startsWith("TXN-");
                assertThat(saved.getStatus()).isEqualTo("completed");
                assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
                assertThat(saved.getCurrencyCode()).isEqualTo("USD");
                assertThat(saved.getTransactionType()).isEqualTo("PAYMENT");
                assertThat(saved.getPaymentMethod()).isEqualTo("bank_transfer");
                assertThat(saved.getReference()).isEqualTo("REF-001");
                assertThat(saved.getGroupId()).isEqualTo(groupId);
                assertThat(saved.getMemberId()).isNull();
                assertThat(saved.getContributionId()).isNull();
                assertThat(saved.getTransactionDate()).isNotNull();
                assertThat(saved.getId()).isNotNull();
                assertThat(saved.getCreatedBy()).isEqualTo(UUID.fromString(actorId));
            })
            .verifyComplete();

        verify(transactionRepository).save(any(Transaction.class));
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishTransactionRecorded(any());
    }

    /**
     * Internal ledger operations (ADJUSTMENT, DEBIT, CREDIT, REVERSAL)
     * skip the receipt fan-out — the payer already knows about them via
     * out-of-band operator communication. Guard here so a well-meaning
     * refactor that "always publishes on save" doesn't spam every
     * refund/chargeback out to the group liaison.
     */
    @Test
    void record_adjustment_skipsReceiptEvent() {
        UUID groupId = UUID.randomUUID();
        var request = new RecordTransactionRequest(
            groupId, null,
            new BigDecimal("20.00"), "USD",
            "ADJUSTMENT", "manual", "note-write-off", null
        );

        when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(inv -> {
                Transaction t = inv.getArgument(0);
                if (t.getId() == null) t.setId(UUID.randomUUID());
                return Mono.just(t);
            });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(transactionService.record(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .assertNext(saved -> assertThat(saved.getTransactionType()).isEqualTo("ADJUSTMENT"))
            .verifyComplete();

        verify(eventPublisher, org.mockito.Mockito.never()).publishTransactionRecorded(any());
    }

    /** Missing both group and member is a 422 — nothing to anchor the ledger to. */
    @Test
    void record_noOwner_returns422() {
        var request = new RecordTransactionRequest(
            null, null,
            new BigDecimal("50"), "USD",
            "payment", "cash", null, null
        );

        StepVerifier.create(transactionService.record(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant")))
            .expectErrorSatisfies(err ->
                assertThat(err.getMessage()).contains("groupId or a memberId"))
            .verify();
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
        var params = new TransactionFilterParams("USD", "PAYMENT", null, null, null,
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
