package com.medfund.contributions.repository;

import com.medfund.contributions.entity.DunningConfig;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface DunningConfigRepository extends R2dbcRepository<DunningConfig, UUID> {
}
