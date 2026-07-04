package com.medfund.contributions.dto;

import com.medfund.contributions.entity.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String transactionNumber,
        UUID groupId,
        UUID memberId,
        UUID contributionId,
        UUID invoiceId,
        BigDecimal amount,
        String currencyCode,
        String transactionType,
        String paymentMethod,
        String reference,
        String reason,
        String status,
        Instant transactionDate,
        Instant createdAt,
        UUID createdBy
) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionNumber(),
                transaction.getGroupId(),
                transaction.getMemberId(),
                transaction.getContributionId(),
                transaction.getInvoiceId(),
                transaction.getAmount(),
                transaction.getCurrencyCode(),
                transaction.getTransactionType(),
                transaction.getPaymentMethod(),
                transaction.getReference(),
                transaction.getReason(),
                transaction.getStatus(),
                transaction.getTransactionDate(),
                transaction.getCreatedAt(),
                transaction.getCreatedBy()
        );
    }
}
