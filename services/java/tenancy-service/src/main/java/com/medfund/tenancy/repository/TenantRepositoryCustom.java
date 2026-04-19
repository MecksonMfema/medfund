package com.medfund.tenancy.repository;

import com.medfund.tenancy.dto.TenantQueryParams;
import com.medfund.tenancy.entity.Tenant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TenantRepositoryCustom {
    Flux<Tenant> search(TenantQueryParams params);
    Mono<Long> countSearch(TenantQueryParams params);
}
