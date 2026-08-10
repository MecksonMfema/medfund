package com.medfund.finance.repository;

import com.medfund.finance.entity.Note;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface NoteRepository extends R2dbcRepository<Note, UUID> {

    @Query("SELECT * FROM notes WHERE provider_id = :providerId ORDER BY created_at DESC")
    Flux<Note> findByProviderId(UUID providerId);

    @Query("SELECT * FROM notes WHERE member_id = :memberId ORDER BY created_at DESC")
    Flux<Note> findByMemberId(UUID memberId);

    @Query("SELECT * FROM notes WHERE status = :status ORDER BY created_at DESC")
    Flux<Note> findByStatus(String status);

    @Query("SELECT * FROM notes ORDER BY created_at DESC")
    Flux<Note> findAllOrderByCreatedAtDesc();

    @Query("SELECT EXISTS(SELECT 1 FROM notes WHERE note_number = :noteNumber)")
    Mono<Boolean> existsByNoteNumber(String noteNumber);
}
