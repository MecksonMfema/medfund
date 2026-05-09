package com.medfund.user.repository;

import com.medfund.user.entity.EmailSender;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface EmailSenderRepository extends R2dbcRepository<EmailSender, UUID> {

    @Query("SELECT * FROM email_senders ORDER BY status, address")
    Flux<EmailSender> findAllOrdered();

    Mono<EmailSender> findByAddress(String address);
}
