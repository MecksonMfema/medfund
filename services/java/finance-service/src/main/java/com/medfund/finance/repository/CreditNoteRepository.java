package com.medfund.finance.repository;

import com.medfund.finance.entity.CreditNote;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface CreditNoteRepository extends R2dbcRepository<CreditNote, UUID> {

    @Query("SELECT * FROM credit_notes ORDER BY created_at DESC")
    Flux<CreditNote> findAllOrdered();
}
