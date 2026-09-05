package com.medfund.finance.producer.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Phase 18 K7 aggregate row. K7 explicitly deferred the acquisition-vs-
 * servicing classifier — this row sums all PAID commission_transaction
 * rows regardless of type. UI note on the KPI page: "Includes all paid
 * commission; new-business/trail split in a future release."
 *
 * <p>{@code producerId}/{@code producerName} nullable when the dimension
 * excludes producer; {@code insuranceLine} nullable when it excludes
 * insurance line.
 */
public record CommissionAggregateRow(
        UUID producerId,
        String producerName,
        String insuranceLine,
        String currencyCode,
        BigDecimal totalPaid,
        long rowCount) {
}
