package com.medfund.contributions.repository;

import com.medfund.contributions.entity.MemberRunningBalance;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MemberRunningBalanceRepository extends R2dbcRepository<MemberRunningBalance, UUID> {

    @Query("SELECT * FROM member_running_balance WHERE member_id = :memberId AND currency_code = :currency")
    Mono<MemberRunningBalance> findByMemberAndCurrency(UUID memberId, String currency);
}
