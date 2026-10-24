package com.medfund.kyc.repository;

import com.medfund.kyc.entity.KycVerification;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface KycVerificationRepository extends R2dbcRepository<KycVerification, UUID> {

    @Query("SELECT * FROM kyc_verifications ORDER BY created_at DESC")
    Flux<KycVerification> findAllOrdered();

    @Query("SELECT * FROM kyc_verifications WHERE subject_type = :subjectType AND subject_id = :subjectId")
    Mono<KycVerification> findBySubject(String subjectType, UUID subjectId);
}
