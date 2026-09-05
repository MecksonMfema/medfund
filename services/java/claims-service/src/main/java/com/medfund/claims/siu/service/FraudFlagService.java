package com.medfund.claims.siu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.repository.FraudFlagRepository;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persists AI-emitted + officer-created fraud flags. Every AI decision on
 * the {@code medfund.claims.fraud-flagged} topic lands here as one row
 * (Rule 3 audit-of-record). Unlinked rows (siu_case_id IS NULL) age out
 * after 1 year via {@link com.medfund.claims.siu.scheduler.FraudFlagRetentionJob}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudFlagService {

    private final FraudFlagRepository repository;

    /**
     * Persist an AI-emitted fraud flag from the Kafka event envelope. See
     * {@code services/python/ai-service/app/core/kafka_consumer.py} for the
     * shape (10 required fields; correlationId nullable).
     */
    public Mono<FraudFlag> persist(JsonNode event) {
        FraudFlag flag = new FraudFlag();
        flag.setClaimId(UUID.fromString(event.get("claimId").asText()));
        flag.setFlagSource("AI_MODEL");
        flag.setModelVersion(event.get("modelVersion").asText());
        flag.setRiskScore(new BigDecimal(event.get("riskScore").asText()));
        flag.setRiskLevel(event.get("riskLevel").asText());
        flag.setIndicators(Json.of(event.get("indicators").toString()));
        flag.setFlaggedAt(OffsetDateTime.parse(event.get("occurredAt").asText()));
        flag.setCorrelationId(event.hasNonNull("correlationId")
                ? event.get("correlationId").asText() : null);
        return repository.save(flag)
                .doOnSuccess(saved -> log.debug(
                        "Persisted fraud_flag id={} claimId={} riskLevel={}",
                        saved.getId(), saved.getClaimId(), saved.getRiskLevel()));
    }

    /**
     * Officer-created flag path — an SIU officer manually opens a case
     * without an AI prediction (e.g. a colleague-referral). Rule 3 still
     * applies; audit-of-record row lands here.
     */
    public Mono<FraudFlag> createManualFlag(UUID claimId, String actorEmail) {
        FraudFlag flag = new FraudFlag();
        flag.setClaimId(claimId);
        flag.setFlagSource("MANUAL_OFFICER");
        flag.setIndicators(Json.of("[]"));
        flag.setFlaggedAt(OffsetDateTime.now());
        return repository.save(flag);
    }

    /**
     * Called by {@link SiuCaseService} after auto-opening a case to link the
     * triggering flag to the new case row. Persists {@code siu_case_id}
     * on the existing flag row.
     */
    public Mono<FraudFlag> linkToCase(FraudFlag flag, UUID caseId) {
        flag.setSiuCaseId(caseId);
        return repository.save(flag);
    }
}
