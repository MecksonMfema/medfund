package com.medfund.contributions.repository;

import com.medfund.contributions.entity.Transaction;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TransactionRepository extends R2dbcRepository<Transaction, UUID> {

    @Query("SELECT * FROM transactions WHERE contribution_id = :contributionId")
    Flux<Transaction> findByContributionId(UUID contributionId);

    @Query("SELECT * FROM transactions WHERE invoice_id = :invoiceId")
    Flux<Transaction> findByInvoiceId(UUID invoiceId);

    @Query("SELECT * FROM transactions WHERE transaction_number = :transactionNumber")
    Mono<Transaction> findByTransactionNumber(String transactionNumber);

    @Query("SELECT * FROM transactions ORDER BY transaction_date DESC")
    Flux<Transaction> findAllOrderByTransactionDateDesc();

    /**
     * Idempotency probe for fixed-cause auto-adjustments: consumers pass
     * the source event's key (member id, scheme-change id) as the
     * {@code reference} when posting; before firing a new event, they
     * call this to see if the ledger already carries a matching row.
     * Reference is unique-enough on its own for this class of events —
     * the transaction type is included for defence-in-depth against a
     * future collision with a manually-recorded PAYMENT that happens
     * to share a reference string.
     */
    @Query("SELECT * FROM transactions " +
           "WHERE reference = :reference AND transaction_type = :transactionType " +
           "LIMIT 1")
    Mono<Transaction> findFirstByReferenceAndTransactionType(String reference, String transactionType);
}
