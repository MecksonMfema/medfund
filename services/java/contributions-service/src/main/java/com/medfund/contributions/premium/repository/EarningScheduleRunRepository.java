package com.medfund.contributions.premium.repository;

import com.medfund.contributions.premium.entity.EarningScheduleRun;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface EarningScheduleRunRepository extends ReactiveCrudRepository<EarningScheduleRun, UUID> {

    Mono<EarningScheduleRun> findFirstByStatusOrderByStartedAtDesc(String status);
}
