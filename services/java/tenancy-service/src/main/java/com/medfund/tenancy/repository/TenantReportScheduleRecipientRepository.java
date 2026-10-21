package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantReportScheduleRecipient;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantReportScheduleRecipientRepository
        extends ReactiveCrudRepository<TenantReportScheduleRecipient, UUID> {

    Flux<TenantReportScheduleRecipient> findByScheduleIdOrderByEmailAsc(UUID scheduleId);

    /** Active recipients read path used by the notification-service Go
     *  dispatcher via the {@code /active} endpoint. */
    Flux<TenantReportScheduleRecipient> findByScheduleIdAndIsActiveIsTrue(UUID scheduleId);

    Mono<TenantReportScheduleRecipient> findByUnsubscribeToken(UUID unsubscribeToken);
}
