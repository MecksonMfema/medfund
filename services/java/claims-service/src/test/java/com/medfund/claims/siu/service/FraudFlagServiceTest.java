package com.medfund.claims.siu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.repository.FraudFlagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudFlagServiceTest {

    private FraudFlagRepository repo;
    private FraudFlagService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = mock(FraudFlagRepository.class);
        service = new FraudFlagService(repo);
    }

    @Test
    void persist_mapsAllFieldsFromEnvelope() throws Exception {
        UUID claimId = UUID.randomUUID();
        String json = """
                {
                  "eventType":"FRAUD_FLAG_EMITTED",
                  "tenantId":"t-1",
                  "claimId":"%s",
                  "modelVersion":"fraud-isolation-forest-v1.2.0",
                  "riskScore":0.87,
                  "riskLevel":"HIGH",
                  "indicators":["frequent_visits","unusual_time_of_day"],
                  "occurredAt":"2026-09-05T14:23:45Z",
                  "correlationId":"req-1"
                }
                """.formatted(claimId);
        JsonNode event = mapper.readTree(json);
        when(repo.save(any(FraudFlag.class))).thenAnswer(inv -> {
            FraudFlag arg = inv.getArgument(0);
            arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        StepVerifier.create(service.persist(event))
                .assertNext(saved -> {
                    assertThat(saved.getClaimId()).isEqualTo(claimId);
                    assertThat(saved.getFlagSource()).isEqualTo("AI_MODEL");
                    assertThat(saved.getModelVersion()).isEqualTo("fraud-isolation-forest-v1.2.0");
                    assertThat(saved.getRiskScore()).isEqualByComparingTo("0.87");
                    assertThat(saved.getRiskLevel()).isEqualTo("HIGH");
                    assertThat(saved.getCorrelationId()).isEqualTo("req-1");
                    assertThat(saved.getIndicators()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void persist_treatsMissingCorrelationIdAsNull() throws Exception {
        UUID claimId = UUID.randomUUID();
        JsonNode event = mapper.readTree("""
                {
                  "claimId":"%s",
                  "modelVersion":"v1",
                  "riskScore":0.4,
                  "riskLevel":"MEDIUM",
                  "indicators":[],
                  "occurredAt":"2026-09-05T14:23:45Z"
                }
                """.formatted(claimId));
        when(repo.save(any(FraudFlag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.persist(event))
                .assertNext(saved -> assertThat(saved.getCorrelationId()).isNull())
                .verifyComplete();
    }

    @Test
    void createManualFlag_stampsSourceAndEmptyIndicators() {
        UUID claimId = UUID.randomUUID();
        ArgumentCaptor<FraudFlag> captor = ArgumentCaptor.forClass(FraudFlag.class);
        when(repo.save(any(FraudFlag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.createManualFlag(claimId, "officer@medfund.local"))
                .assertNext(saved -> {
                    assertThat(saved.getClaimId()).isEqualTo(claimId);
                    assertThat(saved.getFlagSource()).isEqualTo("MANUAL_OFFICER");
                    assertThat(saved.getModelVersion()).isNull();
                    assertThat(saved.getRiskScore()).isNull();
                    assertThat(saved.getRiskLevel()).isNull();
                    assertThat(saved.getFlaggedAt()).isNotNull();
                    assertThat(saved.getIndicators()).isNotNull();
                })
                .verifyComplete();
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getFlagSource()).isEqualTo("MANUAL_OFFICER");
    }

    @Test
    void linkToCase_setsSiuCaseIdAndSaves() {
        FraudFlag flag = new FraudFlag();
        flag.setId(UUID.randomUUID());
        flag.setClaimId(UUID.randomUUID());
        UUID caseId = UUID.randomUUID();
        when(repo.save(any(FraudFlag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.linkToCase(flag, caseId))
                .assertNext(saved -> assertThat(saved.getSiuCaseId()).isEqualTo(caseId))
                .verifyComplete();
    }
}
