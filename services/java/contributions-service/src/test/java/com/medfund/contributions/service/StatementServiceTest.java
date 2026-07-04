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
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.all()).thenReturn(Flux.empty());
        lenient().when(fetch.one()).thenReturn(Mono.empty());
    }
}
