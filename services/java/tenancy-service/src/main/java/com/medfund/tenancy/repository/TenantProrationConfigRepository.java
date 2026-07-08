package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantProrationConfig;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface TenantProrationConfigRepository extends R2dbcRepository<TenantProrationConfig, UUID> {
}
