package com.medfund.contributions.service;

import com.medfund.contributions.entity.Invoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Pins the SQL predicates for the invoice-snapshot window sums —
 * {@code payments} and {@code adjustments} that feed opening/closing
 * balances at commit time. The pre-fix version joined via
 * {@code contribution_id} only, so a top-of-group payment or adjustment
 * (with {@code contribution_id = NULL}) was silently excluded from the
 * snapshot, corrupting every future statement rendered off it.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceSnapshotServiceTest {

    @Mock DatabaseClient db;

    private InvoiceSnapshotService service;
    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    void setUp() {
        service = new InvoiceSnapshotService(db);
        sqlCaptor = ArgumentCaptor.forClass(String.class);
        stubDbMonoChain();
    }

    @Test
    void sumWindowedTransactions_useLeftJoinAndOwnerColumns_forGroupInvoice() {
        // Regression guard: snapshot opening/closing math MUST include
        // group-anchored payments (contribution_id NULL). LEFT JOIN
        // survives the missing contribution, and the OR predicate
        // re-attributes the row via the transaction's own group_id.
        Invoice inv = new Invoice();
        inv.setGroupId(UUID.randomUUID());
        inv.setCurrencyCode("USD");
        inv.setCommittedAt(Instant.now());

        service.sumWindowedTransactions(inv, Instant.EPOCH, inv.getCommittedAt()).block();

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .as("LEFT JOIN keeps top-of-group payments alive so they hit the snapshot totals")
                .contains("LEFT JOIN contributions");
        // Both attribution paths present: via the contribution's holder
        // AND via the transaction's own owner column.
        assertThat(sql)
                .contains("c.group_id  = :groupId")
                .contains("t.group_id  = :groupId");
        // Payment / adjustment split still driven by the transaction-type
        // sign — a fix that broke the sum shape would silently corrupt
        // opening balances.
        assertThat(sql)
                .contains("tt.sign = '-'")
                .contains("tt.sign = '+'");
    }

    @Test
    void sumWindowedTransactions_useLeftJoinAndOwnerColumns_forMemberInvoice() {
        // Same guard on the member side — an individual member's ledger
        // must include payments posted directly against them (no
        // contribution linkage), otherwise their snapshot understates
        // what they've paid.
        Invoice inv = new Invoice();
        inv.setMemberId(UUID.randomUUID());
        inv.setCurrencyCode("USD");
        inv.setCommittedAt(Instant.now());

        service.sumWindowedTransactions(inv, Instant.EPOCH, inv.getCommittedAt()).block();

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("c.member_id = :memberId")
                .contains("t.member_id = :memberId");
    }

    @SuppressWarnings("unchecked")
    private void stubDbMonoChain() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.RowsFetchSpec<InvoiceSnapshotService.WindowSums> fetch =
                mock(org.springframework.r2dbc.core.RowsFetchSpec.class);
        lenient().when(db.sql(sqlCaptor.capture())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.bindNull(anyString(), any())).thenReturn(spec);
        lenient().when(spec.map(any(java.util.function.Function.class))).thenAnswer(inv -> fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(new InvoiceSnapshotService.WindowSums(
                BigDecimal.ZERO, BigDecimal.ZERO)));
    }
}
