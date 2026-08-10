package com.medfund.contributions.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.entity.Invoice;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.contributions.repository.InvoiceRepository;
import com.medfund.contributions.repository.TransactionTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the SQL predicates that decide which transactions land on a
 * group's / member's statement. The pre-fix query only joined via
 * {@code contribution_id}, so a top-of-group payment (posted directly
 * against the group with {@code contribution_id = NULL}) never surfaced
 * on the statement, the ledger view, or the invoice preview. Regression
 * tests below assert both the contribution-linked AND the direct-owner
 * predicates are present.
 */
@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

    @Mock ContributionRepository contributionRepository;
    @Mock TransactionTypeRepository transactionTypeRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock DatabaseClient db;

    private StatementService service;
    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    void setUp() {
        service = new StatementService(contributionRepository, transactionTypeRepository,
                invoiceRepository, db);
        sqlCaptor = ArgumentCaptor.forClass(String.class);
    }

    @Test
    void findTransactionsForHolder_group_predicateIncludesContributionIdOrDirectGroupId() {
        // Bug fix regression: a group-anchored payment has group_id NOT NULL
        // and contribution_id NULL. Statement must pull it in via the
        // OR-branch on group_id, not only via contribution_id.
        stubDbFluxChain();
        UUID targetId = UUID.randomUUID();

        service.findTransactionsForHolder("GROUP", targetId,
                Set.of(UUID.randomUUID()), "USD").blockLast();

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("Predicate must accept EITHER a contribution linkage OR direct group_id")
                .contains("contribution_id = ANY(:ids)")
                .contains("group_id = :targetId");
        // Member owner column must NOT be part of the group query — mistakenly
        // ORing member_id would leak an ungrouped member's ledger onto the
        // group's statement.
        assertThat(sql).doesNotContain("member_id = :targetId");
    }

    @Test
    void findTransactionsForHolder_member_predicateIncludesContributionIdOrDirectMemberId() {
        stubDbFluxChain();
        UUID targetId = UUID.randomUUID();

        service.findTransactionsForHolder("MEMBER", targetId,
                Set.of(UUID.randomUUID()), "USD").blockLast();

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("contribution_id = ANY(:ids)")
                .contains("member_id = :targetId");
        assertThat(sql).doesNotContain("group_id = :targetId");
    }

    @Test
    void findTransactionsForHolder_emptyContributionIds_stillQueriesForDirectOwner() {
        // Pre-billing view: no contributions yet, but a group might already
        // have a pre-paid credit sitting on the ledger. The predicate must
        // still fire on the direct owner column so those credits appear.
        stubDbFluxChain();
        UUID targetId = UUID.randomUUID();

        service.findTransactionsForHolder("GROUP", targetId, Set.of(), "USD").blockLast();

        // Guard: SQL is issued (executor doesn't short-circuit on empty ids
        // and skip the direct-owner branch).
        assertThat(sqlCaptor.getValue()).contains("group_id = :targetId");
    }

    @Test
    void windowedTransactions_useLeftJoin_soContributionIdNullSurvives() {
        // The invoice-snapshot statement (per-invoice preview) previously
        // used INNER JOIN transactions → contributions which silently
        // dropped every transaction with contribution_id NULL. LEFT JOIN
        // keeps them, and the WHERE clause reattaches them via the
        // direct owner column.
        stubDbFluxChain();
        Invoice inv = new Invoice();
        inv.setGroupId(UUID.randomUUID());
        inv.setCurrencyCode("USD");
        inv.setCommittedAt(Instant.now());

        service.windowedTransactions(inv, Instant.EPOCH).blockLast();

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("LEFT JOIN keeps top-of-group transactions (contribution_id NULL) alive")
                .contains("LEFT JOIN contributions");
        // Two-path predicate: contribution's holder OR transaction's own
        // holder. Both required — dropping either drops legitimate rows.
        assertThat(sql)
                .contains("c.group_id  = :groupId")
                .contains("t.group_id  = :groupId");
    }

    @SuppressWarnings("unchecked")
    private void stubDbFluxChain() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<Transaction> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(sqlCaptor.capture())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.bindNull(anyString(), any())).thenReturn(spec);
        // StatementService uses BOTH map(Function<Row,T>) and
        // map(BiFunction<Row,RowMetadata,T>) — the latter for the
        // contribution query at :318. Both routes return the same
        // fetch stub so all(.) and .one() short-circuit to empty.
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(spec.map(any(java.util.function.BiFunction.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.all()).thenReturn(Flux.empty());
        lenient().when(fetch.one()).thenReturn(Mono.empty());
    }

    // ------------------------------------------------------------------
    // Double-entry bookend rows — regression guard for the ledger
    // cleanup. If a future refactor drops the OPENING_BALANCE prefix
    // or CLOSING_BALANCE suffix, the ledger reverts to a flat list
    // with balances only in the header — the exact shape we just
    // spent this ticket removing. Both assertions below fail loudly.
    // ------------------------------------------------------------------

    @Test
    void generateForInvoice_emitsOpeningAndClosingBookendRows() {
        UUID invoiceId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setId(invoiceId);
        inv.setInvoiceNumber("CS-000001");
        inv.setGroupId(groupId);
        inv.setCurrencyCode("USD");
        inv.setOpeningBalance(new BigDecimal("100.00"));
        inv.setClosingBalance(new BigDecimal("250.00"));
        inv.setCommittedAt(Instant.parse("2026-07-31T12:00:00Z"));
        inv.setPeriodStart(java.time.LocalDate.of(2026, 7, 1));
        inv.setPeriodEnd(java.time.LocalDate.of(2026, 7, 31));

        when(invoiceRepository.findById(invoiceId)).thenReturn(Mono.just(inv));
        when(transactionTypeRepository.findAllOrdered()).thenReturn(Flux.empty());
        stubDbFluxChain();

        var resp = service.generateForInvoice(invoiceId).block();

        assertThat(resp).isNotNull();
        assertThat(resp.lines()).isNotEmpty();

        // First line is the opening bookend — displays snapshot opening.
        var first = resp.lines().get(0);
        assertThat(first.type()).isEqualTo(com.medfund.contributions.dto.StatementLine.TYPE_OPENING_BALANCE);
        assertThat(first.description()).isEqualTo("Balance brought forward");
        assertThat(first.runningBalance()).isEqualByComparingTo("100.00");
        assertThat(first.debit()).isNull();
        assertThat(first.credit()).isNull();

        // Last line is the closing bookend — displays snapshot closing
        // (NOT the recomputed running total, which could drift on very
        // late back-dated posts). Snapshot is authoritative.
        var last = resp.lines().get(resp.lines().size() - 1);
        assertThat(last.type()).isEqualTo(com.medfund.contributions.dto.StatementLine.TYPE_CLOSING_BALANCE);
        assertThat(last.description()).isEqualTo("Amount Due");
        assertThat(last.runningBalance()).isEqualByComparingTo("250.00");
        assertThat(last.debit()).isNull();
        assertThat(last.credit()).isNull();

        // Header still carries opening + closing for the summary card.
        assertThat(resp.header().openingBalance()).isEqualByComparingTo("100.00");
        assertThat(resp.header().closingBalance()).isEqualByComparingTo("250.00");
    }

    @Test
    void generateForInvoice_nullSnapshotFields_defaultToZeroBookends() {
        // Robust to legacy invoices that never had their snapshot
        // stamped (opening_balance / closing_balance NULL). Bookend
        // rows still emitted so the PDF / Excel don't break; runningBalance
        // falls through to zero.
        UUID invoiceId = UUID.randomUUID();
        Invoice inv = new Invoice();
        inv.setId(invoiceId);
        inv.setGroupId(UUID.randomUUID());
        inv.setCurrencyCode("USD");
        inv.setCommittedAt(Instant.now());
        inv.setPeriodStart(java.time.LocalDate.of(2026, 7, 1));
        inv.setPeriodEnd(java.time.LocalDate.of(2026, 7, 31));
        // opening + closing intentionally null

        when(invoiceRepository.findById(invoiceId)).thenReturn(Mono.just(inv));
        when(transactionTypeRepository.findAllOrdered()).thenReturn(Flux.empty());
        stubDbFluxChain();

        var resp = service.generateForInvoice(invoiceId).block();

        assertThat(resp.lines().get(0).type())
                .isEqualTo(com.medfund.contributions.dto.StatementLine.TYPE_OPENING_BALANCE);
        assertThat(resp.lines().get(0).runningBalance()).isEqualByComparingTo("0.00");
        var last = resp.lines().get(resp.lines().size() - 1);
        assertThat(last.type())
                .isEqualTo(com.medfund.contributions.dto.StatementLine.TYPE_CLOSING_BALANCE);
        assertThat(last.runningBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void generate_periodStatement_emitsBookendsWhenNoActivity() {
        // Empty group / empty period — the ledger should still open
        // with a b/f row (opening=0) and close with a c/f row (closing=0).
        // Guards against "no lines" being interpreted as "no ledger" —
        // an empty statement is still a valid statement with bookends.
        UUID groupId = UUID.randomUUID();
        when(contributionRepository.findByGroupId(groupId)).thenReturn(Flux.empty());
        when(transactionTypeRepository.findAllOrdered()).thenReturn(Flux.empty());
        stubDbFluxChain();

        var resp = service.generate("GROUP", groupId,
                java.time.LocalDate.of(2026, 7, 1),
                java.time.LocalDate.of(2026, 7, 31),
                "USD").block();

        assertThat(resp).isNotNull();
        // Exactly two lines — the opening bookend and the closing
        // bookend. Nothing in between because there's no activity.
        assertThat(resp.lines()).hasSize(2);
        assertThat(resp.lines().get(0).type())
                .isEqualTo(com.medfund.contributions.dto.StatementLine.TYPE_OPENING_BALANCE);
        assertThat(resp.lines().get(1).type())
                .isEqualTo(com.medfund.contributions.dto.StatementLine.TYPE_CLOSING_BALANCE);
    }
}
