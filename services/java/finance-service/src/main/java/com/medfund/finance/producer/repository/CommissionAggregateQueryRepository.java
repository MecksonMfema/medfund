package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.dto.CommissionAggregateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 18 K7 aggregate query — PAID commission per (currency [,
 * insurance line [, producer]]) for a period. Reads
 * {@code commission_transaction} rows with {@code status = 'PAID'} and
 * {@code paid_at} in the half-open window.
 *
 * <p>Sibling {@code CommissionTransactionRepository.aggregateForPayout}
 * (see line 62-79) serves a different consumer — the PaymentRun-generator
 * groups by {@code (producer, native_currency)} on ACCRUED rows keyed off
 * {@code occurred_at}. Kept separate so the two consumers can't accidentally
 * collide on filter semantics or grouping shape.
 *
 * <p>Native-currency rows only (parent-plan G25). The KPI composer performs
 * any reporting-currency conversion downstream via FxRateReader.convert.
 */
@Repository
@RequiredArgsConstructor
public class CommissionAggregateQueryRepository {

    private final DatabaseClient databaseClient;

    public enum AggregateDimension { TENANT, LINE, PRODUCER, LINE_AND_PRODUCER }

    public Flux<CommissionAggregateRow> aggregatePaid(
            LocalDate periodStart,
            LocalDate periodEnd,
            AggregateDimension dimension,
            String insuranceLineFilter,
            UUID producerIdFilter) {

        boolean includeProducer = dimension == AggregateDimension.PRODUCER
                || dimension == AggregateDimension.LINE_AND_PRODUCER;
        boolean includeLine = dimension == AggregateDimension.LINE
                || dimension == AggregateDimension.LINE_AND_PRODUCER;

        StringBuilder sql = new StringBuilder("SELECT ct.native_currency AS currency_code\n")
                .append("     , SUM(ct.native_amount) AS total_paid\n")
                .append("     , COUNT(*)              AS row_count\n");
        if (includeProducer) {
            sql.append("     , ct.producer_id AS producer_id\n")
                    .append("     , p.name          AS producer_name\n");
        } else {
            sql.append("     , CAST(NULL AS UUID)    AS producer_id\n")
                    .append("     , CAST(NULL AS VARCHAR) AS producer_name\n");
        }
        if (includeLine) {
            sql.append("     , ct.insurance_line AS insurance_line\n");
        } else {
            sql.append("     , CAST(NULL AS VARCHAR) AS insurance_line\n");
        }
        sql.append("  FROM commission_transaction ct\n");
        if (includeProducer) {
            sql.append("  LEFT JOIN producer p ON p.id = ct.producer_id\n");
        }
        sql.append(" WHERE ct.status = 'PAID'\n")
                .append("   AND ct.paid_at >= :periodStart\n")
                .append("   AND ct.paid_at <  :periodEnd\n");
        if (insuranceLineFilter != null) {
            sql.append("   AND ct.insurance_line = :insuranceLine\n");
        }
        if (producerIdFilter != null && includeProducer) {
            sql.append("   AND ct.producer_id = :producerId\n");
        }
        sql.append(switch (dimension) {
            case TENANT             -> " GROUP BY ct.native_currency\n";
            case LINE               -> " GROUP BY ct.native_currency, ct.insurance_line\n";
            case PRODUCER           -> " GROUP BY ct.native_currency, ct.producer_id, p.name\n";
            case LINE_AND_PRODUCER  -> " GROUP BY ct.native_currency, ct.insurance_line, ct.producer_id, p.name\n";
        });

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("periodStart", periodStart.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("periodEnd", periodEnd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
        if (insuranceLineFilter != null) {
            spec = spec.bind("insuranceLine", insuranceLineFilter);
        }
        if (producerIdFilter != null && includeProducer) {
            spec = spec.bind("producerId", producerIdFilter);
        }

        return spec.map((row, meta) -> new CommissionAggregateRow(
                        row.get("producer_id", UUID.class),
                        row.get("producer_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("currency_code", String.class),
                        nz(row.get("total_paid", BigDecimal.class)),
                        row.get("row_count", Long.class) != null
                                ? row.get("row_count", Long.class)
                                : 0L))
                .all();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
