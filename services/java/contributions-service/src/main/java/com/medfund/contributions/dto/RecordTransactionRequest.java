package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wire payload for {@code POST /api/v1/transactions}. Payments anchor
 * on an owner (a {@code groupId} or a {@code memberId}) — never on a
 * specific contribution. That lets operators record money paid ahead
 * of any billing cycle or a lump sum that later settles multiple
 * contributions; the balance service does the drawdown.
 */
public record RecordTransactionRequest(
        UUID groupId,
        UUID memberId,
        @NotNull BigDecimal amount,
        @NotBlank String currencyCode,
        @NotBlank String transactionType,
        @NotBlank String paymentMethod,
        String reference,
        /** Operator-supplied justification. Required (enforced in
         *  TransactionService.record) when the type is an adjustment
         *  (CREDIT / DEBIT) — those move the ledger without a paying
         *  counterparty and need an audit trail. */
        String reason
) {}
