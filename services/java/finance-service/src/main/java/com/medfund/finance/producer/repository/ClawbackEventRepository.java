package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.ClawbackEvent;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface ClawbackEventRepository extends R2dbcRepository<ClawbackEvent, UUID> {
}
