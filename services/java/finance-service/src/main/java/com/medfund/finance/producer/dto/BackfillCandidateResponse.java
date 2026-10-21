package com.medfund.finance.producer.dto;

import com.medfund.finance.producer.entity.ProducerBackfillCandidate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Candidate row enriched with treaty + producer context so the review UI can
 * render a full "source → suggestion" row without a client-side join.
 * {@code treatyRef} and the producer fields may be null if the underlying
 * row was deleted between candidate insertion and the list query.
 */
public record BackfillCandidateResponse(
        UUID id,
        UUID treatyId,
        String treatyRef,
        String treatyProducerRef,
        UUID candidateProducerId,
        String candidateProducerCode,
        String candidateProducerName,
        BigDecimal confidenceScore,
        String matchStrategy,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt,
        String resolvedActorEmail
) {

    public static BackfillCandidateResponse from(ProducerBackfillCandidate c,
                                                  String treatyRef,
                                                  String producerCode,
                                                  String producerName) {
        return new BackfillCandidateResponse(
                c.getId(),
                c.getTreatyId(),
                treatyRef,
                c.getTreatyProducerRef(),
                c.getCandidateProducerId(),
                producerCode,
                producerName,
                c.getConfidenceScore(),
                c.getMatchStrategy(),
                c.getStatus(),
                c.getCreatedAt(),
                c.getResolvedAt(),
                c.getResolvedActorEmail());
    }
}
