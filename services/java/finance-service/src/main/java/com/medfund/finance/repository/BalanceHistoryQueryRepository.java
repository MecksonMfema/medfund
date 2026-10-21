package com.medfund.finance.repository;

import com.medfund.finance.dto.BalanceHistoryResponse;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dynamic-SQL balance-history read (Phase 6). Joins the freeze-frame
 * snapshot tables to {@code payment_runs} for the run number and executed
 * timestamp so the history page renders run context. Optional filters:
 * {@code asAtRun} pins the row set to exactly one run (D6-4) and
 * {@code currency} narrows to a single native currency.
 */
@Repository
public class BalanceHistoryQueryRepository {

    private final DatabaseClient db;

    public BalanceHistoryQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<BalanceHistoryResponse.BalanceHistoryRow> providerHistory(UUID providerId,
                                                                           UUID asAtRun,
                                                                           String currency) {
        return history("provider", "provider_balance_snapshot", providerId, asAtRun, currency);
    }

    public Flux<BalanceHistoryResponse.BalanceHistoryRow> memberHistory(UUID memberId,
                                                                        UUID asAtRun,
                                                                        String currency) {
        return history("member", "member_balance_snapshot", memberId, asAtRun, currency);
    }

    private Flux<BalanceHistoryResponse.BalanceHistoryRow> history(String payeeColumn,
                                                                   String snapshotTable,
                                                                   UUID payeeId,
                                                                   UUID asAtRun,
                                                                   String currency) {
        boolean hasRun = asAtRun != null;
        boolean hasCurrency = currency != null && !currency.isBlank();

        String sql = "SELECT s.payment_run_id, pr.run_number, pr.executed_at, "
                + "       s.currency_code, s.opening_balance, s.closing_balance, "
                + "       s.total_claimed, s.total_approved, s.total_paid, s.net_due "
                + "  FROM " + snapshotTable + " s "
                + "  JOIN payment_runs pr ON pr.id = s.payment_run_id "
                + " WHERE s." + payeeColumn + "_id = :payeeId "
                + (hasRun ? " AND s.payment_run_id = :runId " : "")
                + (hasCurrency ? " AND UPPER(s.currency_code) = UPPER(:currency) " : "")
                + " ORDER BY s.taken_at DESC, s.payment_run_id, s.currency_code";

        var spec = db.sql(sql).bind("payeeId", payeeId);
        if (hasRun)      spec = spec.bind("runId", asAtRun);
        if (hasCurrency) spec = spec.bind("currency", currency);
        return spec.map((row, meta) -> new BalanceHistoryResponse.BalanceHistoryRow(
                        row.get("payment_run_id", UUID.class),
                        row.get("run_number", String.class),
                        row.get("executed_at", Instant.class),
                        row.get("currency_code", String.class),
                        row.get("opening_balance", BigDecimal.class),
                        row.get("closing_balance", BigDecimal.class),
                        row.get("total_claimed", BigDecimal.class),
                        row.get("total_approved", BigDecimal.class),
                        row.get("total_paid", BigDecimal.class),
                        row.get("net_due", BigDecimal.class)))
                .all();
    }
}
