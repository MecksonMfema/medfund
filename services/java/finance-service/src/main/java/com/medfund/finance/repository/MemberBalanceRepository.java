package com.medfund.finance.repository;

import com.medfund.finance.entity.MemberBalance;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MemberBalanceRepository extends R2dbcRepository<MemberBalance, UUID> {

    @Query("SELECT * FROM member_balances WHERE member_id = :memberId")
    Flux<MemberBalance> findByMemberId(UUID memberId);

    @Query("SELECT * FROM member_balances WHERE member_id = :memberId AND currency_code = :currencyCode")
    Mono<MemberBalance> findByMemberIdAndCurrencyCode(UUID memberId, String currencyCode);

    @Query("SELECT * FROM member_balances WHERE outstanding_balance > 0 ORDER BY outstanding_balance DESC")
    Flux<MemberBalance> findAllByOutstandingBalanceGreaterThanZero();

    @Query("SELECT * FROM member_balances WHERE currency_code = :currencyCode AND outstanding_balance > 0 ORDER BY member_id")
    Flux<MemberBalance> findOutstandingByCurrency(String currencyCode);
}
