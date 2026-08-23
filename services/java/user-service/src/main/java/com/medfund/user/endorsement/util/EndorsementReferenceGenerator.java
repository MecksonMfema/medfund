package com.medfund.user.endorsement.util;

import com.medfund.user.endorsement.repository.EndorsementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Monotonic-per-year {@code END-YYYY-NNNNNN} reference minter for
 * endorsements. Mirrors {@code ReferenceGenerator.nextAdjustmentReference}
 * in finance-service — MVP counter via {@code SELECT COUNT + zero-padded
 * increment}, with the {@code endorsement.reference} UNIQUE constraint
 * catching concurrent-mint races so the caller surfaces a 409.
 */
@Component
@RequiredArgsConstructor
public class EndorsementReferenceGenerator {

    private final EndorsementRepository endorsementRepository;

    /** Format: {@code END-YYYY-NNNNNN} where NNNNNN is the count-so-far + 1. */
    public Mono<String> nextEndorsementReference() {
        int year = LocalDate.now().getYear();
        String prefix = "END-" + year + "-";
        return endorsementRepository.countByReferenceStartingWith(prefix)
                .map(count -> prefix + String.format("%06d", count + 1L));
    }
}
