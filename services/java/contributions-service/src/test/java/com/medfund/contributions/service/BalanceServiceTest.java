package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.entity.GroupRunningBalance;
import com.medfund.contributions.entity.MemberRunningBalance;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.BalanceQueryRepository;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.repository.GroupRunningBalanceRepository;
import com.medfund.contributions.repository.MemberRunningBalanceRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock private MemberRunningBalanceRepository memberRepo;
    @Mock private GroupRunningBalanceRepository groupRepo;
    @Mock private BalanceQueryRepository queryRepo;
    @Mock private DunningConfigRepository dunningRepo;
    @Mock private DatabaseClient db;
    @Mock private DatabaseClient.GenericExecuteSpec spec;
    @Mock private AuditPublisher auditPublisher;
    @Mock private BalanceEventPublisher eventPublisher;

    private BalanceService service;

    @BeforeEach
    void setup() {
        service = new BalanceService(memberRepo, groupRepo, queryRepo, dunningRepo, db,
                auditPublisher, eventPublisher);
    }

    @Test
    void applyContributionDebit_skipsWhenAmountIsNull() {
        Contribution c = new Contribution();
        c.setMemberId(UUID.randomUUID());
        // amount and currency intentionally null
        StepVerifier.create(service.applyContributionDebit(c)).verifyComplete();
        verifyNoInteractions(db, auditPublisher, eventPublisher);
    }

    @Test
    void applyContributionDebit_skipsWhenMemberIdIsNull() {
        Contribution c = new Contribution();
        c.setAmount(BigDecimal.TEN);
        c.setCurrencyCode("USD");
        // memberId null
        StepVerifier.create(service.applyContributionDebit(c)).verifyComplete();
        verifyNoInteractions(db);
    }

    @Test
    void applyTransaction_noOwners_isNoOp() {
        Transaction t = new Transaction();
        t.setAmount(BigDecimal.TEN);
        t.setCurrencyCode("USD");
        StepVerifier.create(service.applyTransaction(t, "+", null, null)).verifyComplete();
        verifyNoInteractions(db);
    }

    @Test
    void getMemberBalance_returnsZeroWhenRowMissing() {
        UUID memberId = UUID.randomUUID();
        when(memberRepo.findByMemberAndCurrency(memberId, "USD")).thenReturn(Mono.empty());

        StepVerifier.create(service.getMemberBalance(memberId, "USD"))
                .assertNext(resp -> {
                    assertThat(resp.balance()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(resp.memberId()).isEqualTo(memberId);
                    assertThat(resp.currencyCode()).isEqualTo("USD");
                })
                .verifyComplete();
    }

    @Test
    void getGroupBalance_returnsZeroWhenRowMissing() {
        UUID groupId = UUID.randomUUID();
        when(groupRepo.findByGroupAndCurrency(groupId, "ZAR")).thenReturn(Mono.empty());

        StepVerifier.create(service.getGroupBalance(groupId, "ZAR"))
                .assertNext(resp -> {
                    assertThat(resp.balance()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(resp.groupId()).isEqualTo(groupId);
                })
                .verifyComplete();
    }

    @Test
    void getMemberBalance_returnsRowWhenPresent() {
        UUID memberId = UUID.randomUUID();
        MemberRunningBalance row = new MemberRunningBalance();
        row.setMemberId(memberId);
        row.setCurrencyCode("USD");
        row.setBalance(new BigDecimal("250.00"));
        row.setLastChargeAt(Instant.now());
        when(memberRepo.findByMemberAndCurrency(memberId, "USD")).thenReturn(Mono.just(row));

        StepVerifier.create(service.getMemberBalance(memberId, "USD"))
                .assertNext(resp -> assertThat(resp.balance()).isEqualByComparingTo("250.00"))
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // V043 arrears-reactivation surface
    // ------------------------------------------------------------------

    @Test
    void currentlyAgedSubjectIds_readsSuspensionThresholdFromDunning_andReturnsSet() {
        // Uses dunning_config.suspension_days as the age cutoff. That's
        // the same threshold the arrears escalator uses to compute the
        // aging bucket, so a subject that drops out of this set has
        // demonstrably cleared the SUSPENDED-bucket age.
        DunningConfig cfg = new DunningConfig();
        cfg.setId(DunningConfig.SINGLETON_ID);
        cfg.setSuspensionDays(30);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(cfg));

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(queryRepo.findAged(any(), org.mockito.ArgumentMatchers.eq(30), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        agedBalanceRow(id1), agedBalanceRow(id2)));

        StepVerifier.create(service.currentlyAgedSubjectIds())
                .assertNext(set -> {
                    assertThat(set).containsExactlyInAnyOrder(id1, id2);
                })
                .verifyComplete();
    }

    @Test
    void currentlyAgedSubjectIds_defaultThresholdWhenNoDunningConfig() {
        // No dunning_config row → default to 30 days. Guards against a
        // fresh tenant that hasn't opened the arrears settings panel yet.
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.empty());
        when(queryRepo.findAged(any(), org.mockito.ArgumentMatchers.eq(30), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.currentlyAgedSubjectIds())
                .assertNext(set -> assertThat(set).isEmpty())
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // writeOffBalance — zeros the current-collectable side of the ledger
    // when a bad_debts row is written off. See BalanceService.writeOffBalance
    // for why we zero the ledger and let the bad_debts row carry the history.
    // ------------------------------------------------------------------

    @Test
    void writeOffBalance_nullCurrency_isNoOp() {
        StepVerifier.create(service.writeOffBalance(UUID.randomUUID(), null, null, BigDecimal.TEN))
                .verifyComplete();
        verifyNoInteractions(db);
    }

    @Test
    void writeOffBalance_bothSubjectsNull_isNoOp() {
        StepVerifier.create(service.writeOffBalance(null, null, "USD", BigDecimal.TEN))
                .verifyComplete();
        verifyNoInteractions(db);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeOffBalance_memberAndGroup_hitsBothLegs() {
        // A group-billed member's write-off must zero both the member's
        // running balance AND the group's — the two rows track separately
        // and both must reflect the loss.
        UUID memberId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        stubUpsertDbChain();

        StepVerifier.create(service.writeOffBalance(memberId, groupId, "USD", new BigDecimal("125.50")))
                .verifyComplete();

        // Two SQL calls — one per leg. upsertMember + upsertGroup both
        // hit db.sql().bind(...).map(...).one().
        verify(db, org.mockito.Mockito.times(2)).sql(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeOffBalance_memberOnly_hitsMemberLegOnly() {
        // Individual (ungrouped) member — only the member leg fires.
        UUID memberId = UUID.randomUUID();
        stubUpsertDbChain();

        StepVerifier.create(service.writeOffBalance(memberId, null, "USD", new BigDecimal("50.00")))
                .verifyComplete();

        verify(db, org.mockito.Mockito.times(1)).sql(anyString());
    }

    /**
     * Stubs the DatabaseClient.sql().bind()...map().one() chain plus the
     * downstream audit + event publishers so upsertMember / upsertGroup
     * can complete. Kept private + reusable so the write-off tests
     * focus on which legs fire, not on ledger arithmetic.
     */
    @SuppressWarnings("unchecked")
    private void stubUpsertDbChain() {
        DatabaseClient.GenericExecuteSpec spec = org.mockito.Mockito.mock(
                DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<BigDecimal> fetch =
                org.mockito.Mockito.mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(BigDecimal.ZERO));
        // upsertMember/upsertGroup fan out to auditPublisher.publish and
        // eventPublisher.publishMember/GroupBalance after the .one() —
        // both must complete or the outer Mono errors "last".
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishMemberBalance(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishGroupBalance(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    private static com.medfund.contributions.dto.BalanceRow agedBalanceRow(UUID id) {
        return new com.medfund.contributions.dto.BalanceRow(
                "MEMBER", id, "CODE", "Name", "e@x.com",
                "USD", new BigDecimal("100.00"),
                Instant.now().minusSeconds(60L * 60L * 24L * 40L),
                null);
    }

    // ------------------------------------------------------------------
    // listBadDebts — bad-debts view (deactivated / terminated subjects
    // that still owe money). Symmetrical to listDebtors but flipped on
    // the status filter; the service layer's only real job is delegating
    // to the repo pair and doing the page → offset arithmetic. These
    // tests lock both in so a future refactor can't silently drop either.
    // ------------------------------------------------------------------

    @Test
    void listBadDebts_delegatesToRepoWithFilters_andPagesFromZero() {
        // First page (page=0, size=20 → offset=0). Verifies the service
        // passes the currency, subjectType, and q through unmodified.
        UUID id = UUID.randomUUID();
        when(queryRepo.findBadDebts(eq("USD"), eq("GROUP"), eq("Acme"), eq(20), eq(0)))
                .thenReturn(reactor.core.publisher.Flux.just(badDebtsRow(id)));
        when(queryRepo.countBadDebts(eq("USD"), eq("GROUP"), eq("Acme")))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.listBadDebts("USD", "GROUP", "Acme", 0, 20))
                .assertNext(page -> {
                    assertThat(page.total()).isEqualTo(1L);
                    assertThat(page.content()).hasSize(1);
                    assertThat(page.content().get(0).subjectId()).isEqualTo(id);
                    assertThat(page.content().get(0).subjectType()).isEqualTo("GROUP");
                })
                .verifyComplete();

        verify(queryRepo).findBadDebts("USD", "GROUP", "Acme", 20, 0);
        verify(queryRepo).countBadDebts("USD", "GROUP", "Acme");
    }

    @Test
    void listBadDebts_page2Size10_computesOffset20() {
        // page * size = offset — a regression here would break every
        // paginated bad-debts view silently. Guard the math directly.
        when(queryRepo.findBadDebts(eq("ZAR"), isNull(), isNull(), eq(10), eq(20)))
                .thenReturn(reactor.core.publisher.Flux.empty());
        when(queryRepo.countBadDebts(eq("ZAR"), isNull(), isNull()))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(service.listBadDebts("ZAR", null, null, 2, 10))
                .assertNext(page -> {
                    assertThat(page.content()).isEmpty();
                    assertThat(page.total()).isZero();
                    assertThat(page.page()).isEqualTo(2);
                    assertThat(page.size()).isEqualTo(10);
                })
                .verifyComplete();

        verify(queryRepo).findBadDebts("ZAR", null, null, 10, 20);
    }

    @Test
    void listBadDebts_nullSubjectType_passesThroughAsNull() {
        // Null subjectType is the "both individuals + groups" flavour of
        // the query — the service must not coerce it to a default. If we
        // accidentally defaulted to "MEMBER" here the Groups half of a
        // BOTH-model tenant's bad-debts list would silently vanish.
        when(queryRepo.findBadDebts(anyString(), isNull(), any(), anyInt(), anyInt()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        when(queryRepo.countBadDebts(anyString(), isNull(), any()))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(service.listBadDebts("USD", null, null, 0, 20))
                .expectNextCount(1)
                .verifyComplete();

        verify(queryRepo).findBadDebts("USD", null, null, 20, 0);
        verify(queryRepo).countBadDebts("USD", null, null);
    }

    private static com.medfund.contributions.dto.BalanceRow badDebtsRow(UUID id) {
        return new com.medfund.contributions.dto.BalanceRow(
                "GROUP", id, "GRP-01", "Acme Ltd", "billing@acme.example",
                "USD", new BigDecimal("875.25"),
                Instant.now().minusSeconds(60L * 60L * 24L * 90L),
                Instant.now().minusSeconds(60L * 60L * 24L * 60L));
    }
}
