package com.medfund.contributions.repository;

import com.medfund.contributions.entity.GroupRunningBalance;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface GroupRunningBalanceRepository extends R2dbcRepository<GroupRunningBalance, UUID> {

    @Query("SELECT * FROM group_running_balance WHERE group_id = :groupId AND currency_code = :currency")
    Mono<GroupRunningBalance> findByGroupAndCurrency(UUID groupId, String currency);
}
