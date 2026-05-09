package com.medfund.user.repository;

import com.medfund.user.entity.EmailCampaign;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface EmailCampaignRepository extends R2dbcRepository<EmailCampaign, UUID> {

    @Query("SELECT * FROM email_campaigns ORDER BY created_at DESC")
    Flux<EmailCampaign> findAllOrderByCreatedAtDesc();
}
