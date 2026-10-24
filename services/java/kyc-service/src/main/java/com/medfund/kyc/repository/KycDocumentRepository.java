package com.medfund.kyc.repository;

import com.medfund.kyc.entity.KycDocument;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface KycDocumentRepository extends R2dbcRepository<KycDocument, UUID> {

    @Query("SELECT * FROM kyc_documents WHERE subject_type = :subjectType AND subject_id = :subjectId ORDER BY uploaded_at DESC")
    Flux<KycDocument> findBySubject(String subjectType, UUID subjectId);
}
