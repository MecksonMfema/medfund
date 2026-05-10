package com.medfund.finance.repository;

import com.medfund.finance.entity.DebitNote;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface DebitNoteRepository extends R2dbcRepository<DebitNote, UUID> {

    @Query("SELECT * FROM debit_notes ORDER BY created_at DESC")
    Flux<DebitNote> findAllOrdered();
}
