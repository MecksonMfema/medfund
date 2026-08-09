package com.medfund.finance.service;

import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableBalanceRepository.OutstandingMemberPayable;
import com.medfund.finance.repository.MemberPayableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only over the member-payable ledger. Writes come from the Kafka
 * consumer path ({@code ClaimAdjudicatedConsumer}) and the CTC service —
 * this service does not expose write methods.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberPayableService {

    private final MemberPayableRepository repository;
    private final MemberPayableBalanceRepository balanceRepository;

    /** Open payables (still consumable by a CTC) ordered oldest-first. */
    public Flux<MemberPayable> findOpenByMember(UUID memberId) {
        return repository.findByMemberIdAndStatus(memberId, "open");
    }

    /** All payables regardless of status, newest-first. */
    public Flux<MemberPayable> findAllByMember(UUID memberId) {
        return repository.findByMemberId(memberId);
    }

    public Flux<OutstandingMemberPayable> outstandingByMember(UUID memberId) {
        return balanceRepository.findOutstandingByMember(memberId);
    }

    /** Remaining unconsumed balance on a single payable — used by CTC create. */
    public reactor.core.publisher.Mono<BigDecimal> remainingOn(UUID payableId) {
        return balanceRepository.remainingOn(payableId);
    }
}
