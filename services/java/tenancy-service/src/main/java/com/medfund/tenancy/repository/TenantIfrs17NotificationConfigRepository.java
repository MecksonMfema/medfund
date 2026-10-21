package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantIfrs17NotificationConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface TenantIfrs17NotificationConfigRepository
        extends ReactiveCrudRepository<TenantIfrs17NotificationConfig, UUID> {

    Flux<TenantIfrs17NotificationConfig>
            findByTenantIdOrderByEventTypeAscRecipientAsc(UUID tenantId);

    Flux<TenantIfrs17NotificationConfig>
            findByTenantIdAndEventTypeAndIsActiveTrue(UUID tenantId, String eventType);
}
