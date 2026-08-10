package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.dto.CtcPaymentDtos.CreateCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentDtos.ReverseCtcPaymentRequest;
import com.medfund.finance.dto.CtcPaymentFilterParams;
import com.medfund.finance.dto.CtcPaymentRow;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.entity.MemberBalance;
import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.entity.MemberPayableApplication;
import com.medfund.finance.repository.CtcPaymentQueryRepository;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.finance.repository.MemberPayableApplicationRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CtcPaymentServiceTest {

    @Mock private CtcPaymentRepository repository;
    @Mock private CtcPaymentQueryRepository queryRepository;
    @Mock private MemberPayableRepository memberPayableRepository;
    @Mock private MemberPayableApplicationRepository applicationRepository;
    @Mock private MemberPayableBalanceRepository balanceRepository;
    @Mock private MemberBalanceService memberBalanceService;
    @Mock private AuditPublisher auditPublisher;
    @Mock private FinanceEventPublisher eventPublisher;
    @Mock private FxConverter fxConverter;
    @Mock private DatabaseClient db;
    @Mock private DatabaseClient.GenericExecuteSpec sqlSpec;
    @Mock private org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetchSpec;

    @InjectMocks
    private CtcPaymentService service;

    @BeforeEach
    void stubCommonAuditAndEventPaths() {
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishCtcCommitted(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishCtcReversed(any(), any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishCtcApplied(any())).thenReturn(Mono.empty());
        lenient().when(memberBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
                 .thenReturn(Mono.just(new MemberBalance()));
    }

    // ── create ────────────────────────────────────────────────────────

    @Test
    void create_missingMemberId_422() {
        var request = new CreateCtcPaymentRequest(
            UUID.randomUUID(), null, new BigDecimal("10"), "USD", null, UUID.randomUUID());

        StepVerifier.create(service.create(request, "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void create_withGroupId_422() {
        var request = new CreateCtcPaymentRequest(
            UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10"), "USD", null, UUID.randomUUID());

        StepVerifier.create(service.create(request, "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void create_missingPayable_422() {
        UUID memberId  = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        var request = new CreateCtcPaymentRequest(
            null, memberId, new BigDecimal("10"), "USD", null, payableId);
        when(memberPayableRepository.findById(payableId)).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request, "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void create_payableOfDifferentMember_422() {
        UUID memberA   = UUID.randomUUID();
        UUID memberB   = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        var payable = openPayable(payableId, memberB, "USD", new BigDecimal("500"));
        var request = new CreateCtcPaymentRequest(
            null, memberA, new BigDecimal("10"), "USD", null, payableId);
        when(memberPayableRepository.findById(payableId)).thenReturn(Mono.just(payable));

        StepVerifier.create(service.create(request, "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void create_appliedPayable_422() {
        UUID memberId  = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        var payable = openPayable(payableId, memberId, "USD", new BigDecimal("500"));
        payable.setStatus("applied");
        var request = new CreateCtcPaymentRequest(
            null, memberId, new BigDecimal("10"), "USD", null, payableId);
        when(memberPayableRepository.findById(payableId)).thenReturn(Mono.just(payable));

        StepVerifier.create(service.create(request, "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void create_amountExceedsRemainingPayable_422() {
        UUID memberId  = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        var payable = openPayable(payableId, memberId, "USD", new BigDecimal("100"));
        var request = new CreateCtcPaymentRequest(
            null, memberId, new BigDecimal("150"), "USD", null, payableId);
        when(memberPayableRepository.findById(payableId)).thenReturn(Mono.just(payable));
        when(balanceRepository.remainingOn(payableId)).thenReturn(Mono.just(new BigDecimal("100")));

        StepVerifier.create(service.create(request, "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void create_matchingCurrencies_savesDraft() {
        UUID memberId  = UUID.randomUUID();
        UUID payableId = UUID.randomUUID();
        var payable = openPayable(payableId, memberId, "USD", new BigDecimal("500"));
        var request = new CreateCtcPaymentRequest(
            null, memberId, new BigDecimal("150"), "USD", null, payableId);

        when(memberPayableRepository.findById(payableId)).thenReturn(Mono.just(payable));
        when(balanceRepository.remainingOn(payableId)).thenReturn(Mono.just(new BigDecimal("500")));
        when(repository.save(any())).thenAnswer(inv -> {
            CtcPayment c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            c.setCreatedAt(Instant.now());
            return Mono.just(c);
        });

        StepVerifier.create(service.create(request, UUID.randomUUID().toString(), "actor@test")
                .contextWrite(tenantCtx()))
                .assertNext(saved -> {
                    assertThat(saved.getMemberId()).isEqualTo(memberId);
                    assertThat(saved.getMemberPayableId()).isEqualTo(payableId);
                    assertThat(saved.getStatus()).isEqualTo("draft");
                    assertThat(saved.getType()).isEqualTo("CTC");
                    assertThat(saved.getCommitted()).isFalse();
                })
                .verifyComplete();

        verify(repository).save(any());
        verify(auditPublisher).publish(any());
    }

    // ── commit ────────────────────────────────────────────────────────

    @Test
    void commit_draft_flipsStatusAndPublishesEvent_andWritesApplicationRow() {
        UUID payableId = UUID.randomUUID();
        var existing = draftCtc(payableId);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(applicationRepository.save(any())).thenAnswer(inv -> {
            MemberPayableApplication a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        // Not fully consumed after commit — remaining > 0 → no UPDATE.
        when(balanceRepository.remainingOn(payableId)).thenReturn(Mono.just(new BigDecimal("50")));

        StepVerifier.create(service.commit(existing.getId(), UUID.randomUUID().toString(), "actor@test")
                .contextWrite(tenantCtx()))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("committed");
                    assertThat(saved.getCommitted()).isTrue();
                    assertThat(saved.getCommittedAt()).isNotNull();
                })
                .verifyComplete();

        verify(applicationRepository).save(any());
        verify(eventPublisher).publishCtcApplied(any());
        verify(eventPublisher).publishCtcCommitted(any(), any());
    }

    @Test
    void commit_fullyConsumedPayable_flipsPayableToApplied() {
        UUID payableId = UUID.randomUUID();
        var existing = draftCtc(payableId);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(applicationRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // Payable fully consumed → remaining == 0 → status update fires.
        when(balanceRepository.remainingOn(payableId)).thenReturn(Mono.just(BigDecimal.ZERO));
        stubDbUpdate();

        StepVerifier.create(service.commit(existing.getId(), UUID.randomUUID().toString(), "actor@test")
                .contextWrite(tenantCtx()))
                .expectNextCount(1)
                .verifyComplete();

        verify(db).sql(anyString());
    }

    @Test
    void commit_alreadyCommitted_isIdempotentNoSave() {
        var existing = draftCtc(UUID.randomUUID());
        existing.setStatus("committed");
        existing.setCommitted(true);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.commit(existing.getId(), "system", "actor@test").contextWrite(tenantCtx()))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("committed"))
                .verifyComplete();

        verify(repository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
        verify(eventPublisher, never()).publishCtcCommitted(any(), any());
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void commit_reversedStatus_422() {
        var existing = draftCtc(UUID.randomUUID());
        existing.setStatus("reversed");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.commit(existing.getId(), "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    // ── reverse ───────────────────────────────────────────────────────

    @Test
    void reverse_committed_createsCompensatingRow_and_writesNegatingApplication_and_reopensPayable() {
        UUID payableId = UUID.randomUUID();
        var original = draftCtc(payableId);
        original.setStatus("committed");
        original.setCommitted(true);
        original.setCommittedAt(Instant.now());

        when(repository.findById(original.getId())).thenReturn(Mono.just(original));
        when(repository.save(any())).thenAnswer(inv -> {
            CtcPayment c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(applicationRepository.save(any())).thenAnswer(inv -> {
            MemberPayableApplication a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return Mono.just(a);
        });
        stubDbUpdate();

        StepVerifier.create(service.reverse(original.getId(),
                new ReverseCtcPaymentRequest("operator error"),
                UUID.randomUUID().toString(), "hod@test").contextWrite(tenantCtx()))
                .assertNext(compensating -> {
                    assertThat(compensating.getType()).isEqualTo("REVERSAL");
                    assertThat(compensating.getStatus()).isEqualTo("committed");
                    assertThat(compensating.getReversesCtcId()).isEqualTo(original.getId());
                })
                .verifyComplete();

        verify(applicationRepository).save(any());
        verify(eventPublisher).publishCtcReversed(any(), any(), any(), any());
        verify(db).sql(anyString());
    }

    @Test
    void reverse_notCommitted_422() {
        var draft = draftCtc(UUID.randomUUID());
        when(repository.findById(draft.getId())).thenReturn(Mono.just(draft));

        StepVerifier.create(service.reverse(draft.getId(),
                new ReverseCtcPaymentRequest("nope"),
                "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void reverse_alreadyReversalRow_422() {
        var reversalRow = draftCtc(UUID.randomUUID());
        reversalRow.setStatus("committed");
        reversalRow.setType("REVERSAL");
        reversalRow.setReversesCtcId(UUID.randomUUID());
        when(repository.findById(reversalRow.getId())).thenReturn(Mono.just(reversalRow));

        StepVerifier.create(service.reverse(reversalRow.getId(),
                new ReverseCtcPaymentRequest("nope"),
                "system", "actor@test").contextWrite(tenantCtx()))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    // ── searchPaged — envelope + clamp contract ─────────────────────

    @Test
    void searchPaged_wrapsQueryRepoRowsInPageResponse() {
        var row = new CtcPaymentRow(
                UUID.randomUUID(), UUID.randomUUID(), "Acme Ltd",
                null, "", null, new BigDecimal("500.00"), "USD",
                null, false, Instant.now(), null,
                "CTC", "draft", null);
        var params = new CtcPaymentFilterParams(
                false, null, null, null, "createdAt", "desc", 0, 50);

        when(queryRepository.search(any(), org.mockito.ArgumentMatchers.eq(50), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(reactor.core.publisher.Flux.just(row));
        when(queryRepository.count(any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.searchPaged(params))
                .assertNext(resp -> {
                    assertThat(resp.content()).containsExactly(row);
                    assertThat(resp.total()).isEqualTo(1L);
                    assertThat(resp.page()).isZero();
                    assertThat(resp.size()).isEqualTo(50);
                    assertThat(resp.totalPages()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void searchPaged_clampsSizeAndPage() {
        var params = new CtcPaymentFilterParams(
                null, null, null, null, "createdAt", "desc", -3, 99999);

        when(queryRepository.search(any(), org.mockito.ArgumentMatchers.eq(200), org.mockito.ArgumentMatchers.eq(0)))
                .thenReturn(reactor.core.publisher.Flux.empty());
        when(queryRepository.count(any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.searchPaged(params))
                .assertNext(resp -> {
                    assertThat(resp.page()).isZero();
                    assertThat(resp.size()).isEqualTo(200);
                })
                .verifyComplete();
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static java.util.function.Function<reactor.util.context.Context, reactor.util.context.Context> tenantCtx() {
        return ctx -> ctx.put("TENANT_ID", "00000000-0000-4000-8000-000000000001");
    }

    private static MemberPayable openPayable(UUID id, UUID memberId, String currency, BigDecimal amount) {
        var p = new MemberPayable();
        p.setId(id);
        p.setMemberId(memberId);
        p.setClaimId(UUID.randomUUID());
        p.setAmount(amount);
        p.setCurrencyCode(currency);
        p.setStatus("open");
        p.setRecordedAt(Instant.now());
        return p;
    }

    private static CtcPayment draftCtc(UUID payableId) {
        var c = new CtcPayment();
        c.setId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setMemberPayableId(payableId);
        c.setAmount(new BigDecimal("150.00"));
        c.setCurrencyCode("USD");
        c.setType("CTC");
        c.setStatus("draft");
        c.setCommitted(false);
        c.setCreatedAt(Instant.now());
        return c;
    }

    /** Stub the DatabaseClient fluent chain that maybeMarkPayableApplied / reopenPayable use. */
    private void stubDbUpdate() {
        when(db.sql(anyString())).thenReturn(sqlSpec);
        when(sqlSpec.bind(anyString(), any())).thenReturn(sqlSpec);
        when(sqlSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));
    }
}
