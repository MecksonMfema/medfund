package com.medfund.finance.producer.util;

import com.medfund.finance.producer.repository.CommissionAdjustmentRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Human-friendly reference minter for commission-side ledger rows. Format:
 * {@code COMM-YYYY-NNNNNN} for commission transactions,
 * {@code COMM-ADJ-YYYY-NNNNNN} for adjustments (Phase 8).
 *
 * <p>MVP counter — SELECT COUNT + zero-padded increment. The reference column
 * has a UNIQUE index so a concurrent collision surfaces as
 * {@code DuplicateKeyException} in the caller; a follow-up ticket swaps in a
 * per-tenant PostgreSQL sequence.
 */
@Component
@RequiredArgsConstructor
public class ReferenceGenerator {

    private final CommissionTransactionRepository commissionTxnRepository;
    private final CommissionAdjustmentRepository commissionAdjustmentRepository;

    /** Format: {@code COMM-YYYY-NNNNNN} where NNNNNN is the count-so-far + 1. */
    public Mono<String> nextCommissionReference() {
        int year = LocalDate.now().getYear();
        String prefix = "COMM-" + year + "-";
        return commissionTxnRepository.countByReferenceStartingWith(prefix)
                .map(count -> prefix + String.format("%06d", count + 1L));
    }

    /** Format: {@code COMM-ADJ-YYYY-NNNNNN} where NNNNNN is the count-so-far + 1. */
    public Mono<String> nextAdjustmentReference() {
        int year = LocalDate.now().getYear();
        String prefix = "COMM-ADJ-" + year + "-";
        return commissionAdjustmentRepository.countByReferenceStartingWith(prefix)
                .map(count -> prefix + String.format("%06d", count + 1L));
    }
}
