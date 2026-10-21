package com.medfund.finance.regulatory.aml.repository;

import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface SuspiciousTransactionAlertRepository
        extends ReactiveCrudRepository<SuspiciousTransactionAlert, UUID> {

    Flux<SuspiciousTransactionAlert> findByStatusInOrderByRaisedAtDesc(
            List<String> statuses, Pageable pageable);

    Mono<Long> countByStatusIn(List<String> statuses);

    Flux<SuspiciousTransactionAlert> findByStatusAndFiledAtBetweenOrderByFiledAtAsc(
            String status, OffsetDateTime from, OffsetDateTime to);
}
