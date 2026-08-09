package com.medfund.tenancy.repository;

import com.medfund.tenancy.entity.TenantCtcAutoConfig;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface TenantCtcAutoConfigRepository extends R2dbcRepository<TenantCtcAutoConfig, UUID> {
}
