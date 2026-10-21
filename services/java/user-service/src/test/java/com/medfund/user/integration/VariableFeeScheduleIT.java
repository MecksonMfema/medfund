package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.CreateUnitLinkedFundRequest;
import com.medfund.user.dto.CreateVariableFeeScheduleRequest;
import com.medfund.user.dto.UpdateVariableFeeScheduleRequest;
import com.medfund.user.entity.VariableFeeSchedule;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.exception.VariableFeeScheduleNotFoundException;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.user.service.UnitLinkedFundService;
import com.medfund.user.service.UserEventPublisher;
import com.medfund.user.service.VariableFeeScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §8 (I4) IT — VFA variable fee schedule CRUD + effective-window
 * lookup consulted by §16.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/vfa-fund-migration",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_variable_fee",
})
@Import(VariableFeeScheduleIT.SecurityStub.class)
class VariableFeeScheduleIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    static final String TENANT_ID = "00000000-0000-4000-8000-0000000000c4";

    @Autowired private DatabaseClient db;
    @Autowired private VariableFeeScheduleService service;
    @Autowired private UnitLinkedFundService fundService;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID fundId;

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM variable_fee_schedule").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM policy_unit_ledger").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM fund_nav_history").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM unit_linked_fund").then().block(Duration.ofSeconds(10));

        var fund = fundService.create(
                        new CreateUnitLinkedFundRequest("Fee Test Fund", "USD", "MULTI_ASSET"),
                        UUID.randomUUID().toString(), "seed@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(fund).isNotNull();
        fundId = fund.getId();
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_insertsRow_andEmitsAuditWithFriendlyName() {
        var request = new CreateVariableFeeScheduleRequest(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                new BigDecimal("0.0150"));

        var saved = service.create(fundId, request,
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getFundId()).isEqualTo(fundId);
        assertThat(saved.getFeePercentage()).isEqualByComparingTo("0.0150");

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-fee",
                saved.getId().toString(), "CREATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected CREATE audit").isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("VariableFeeSchedule");
        assertThat(audit.path("entityName").asText())
                .isEqualTo("0.0150 from 2026-01-01 to 2027-01-01");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_effectiveToBeforeFrom_returns400() {
        var bad = new CreateVariableFeeScheduleRequest(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 3, 1),
                new BigDecimal("0.0150"));
        StepVerifier.create(
                service.create(fundId, bad, UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(400);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_duplicateEffectiveFrom_returns409() {
        var request = new CreateVariableFeeScheduleRequest(
                LocalDate.of(2026, 1, 1), null, new BigDecimal("0.0150"));
        service.create(fundId, request, UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        StepVerifier.create(
                service.create(fundId, request, UUID.randomUUID().toString(), "bob@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(409);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_unknownFund_returns404() {
        var request = new CreateVariableFeeScheduleRequest(
                LocalDate.of(2026, 1, 1), null, new BigDecimal("0.0150"));
        StepVerifier.create(
                service.create(UUID.randomUUID(), request,
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(UnitLinkedFundNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void update_mutatesFeeAndEffectiveTo_andEmitsAudit() {
        var saved = service.create(fundId, new CreateVariableFeeScheduleRequest(
                                LocalDate.of(2026, 1, 1), null, new BigDecimal("0.0150")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();

        var updated = service.update(saved.getId(),
                        new UpdateVariableFeeScheduleRequest(LocalDate.of(2027, 1, 1), new BigDecimal("0.0175")),
                        UUID.randomUUID().toString(), "bob@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(updated).isNotNull();
        assertThat(updated.getFeePercentage()).isEqualByComparingTo("0.0175");
        assertThat(updated.getEffectiveTo()).isEqualTo(LocalDate.of(2027, 1, 1));

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-fee",
                saved.getId().toString(), "UPDATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected UPDATE audit").isNotNull();
        assertThat(audit.path("actorEmail").asText()).isEqualTo("bob@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void update_unknown_returns404() {
        StepVerifier.create(
                service.update(UUID.randomUUID(),
                                new UpdateVariableFeeScheduleRequest(null, new BigDecimal("0.0175")),
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(VariableFeeScheduleNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void delete_dropsRow_andEmitsAudit() {
        var saved = service.create(fundId, new CreateVariableFeeScheduleRequest(
                                LocalDate.of(2026, 1, 1), null, new BigDecimal("0.0150")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();

        service.delete(saved.getId(), UUID.randomUUID().toString(), "charlie@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM variable_fee_schedule WHERE id = :id")
                .bind("id", saved.getId())
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(0L);

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-fee",
                saved.getId().toString(), "DELETE", Duration.ofSeconds(15));
        assertThat(audit).as("expected DELETE audit").isNotNull();
    }

    @Test
    @WithTenant(TENANT_ID)
    void findEffectiveOn_picksRowWhoseWindowCovers_asOf() {
        service.create(fundId, new CreateVariableFeeScheduleRequest(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1),
                                new BigDecimal("0.0150")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.create(fundId, new CreateVariableFeeScheduleRequest(
                                LocalDate.of(2026, 7, 1), null,
                                new BigDecimal("0.0175")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        VariableFeeSchedule march = service.findEffectiveOn(fundId, LocalDate.of(2026, 3, 15))
                .block(Duration.ofSeconds(10));
        assertThat(march).isNotNull();
        assertThat(march.getFeePercentage()).isEqualByComparingTo("0.0150");

        VariableFeeSchedule september = service.findEffectiveOn(fundId, LocalDate.of(2026, 9, 1))
                .block(Duration.ofSeconds(10));
        assertThat(september).isNotNull();
        assertThat(september.getFeePercentage()).isEqualByComparingTo("0.0175");

        VariableFeeSchedule before = service.findEffectiveOn(fundId, LocalDate.of(2025, 12, 1))
                .block(Duration.ofSeconds(10));
        assertThat(before).isNull();
    }

    @Test
    @WithTenant(TENANT_ID)
    void findByFundId_orderedDesc() {
        service.create(fundId, new CreateVariableFeeScheduleRequest(
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1),
                                new BigDecimal("0.0150")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.create(fundId, new CreateVariableFeeScheduleRequest(
                                LocalDate.of(2026, 7, 1), null,
                                new BigDecimal("0.0175")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        List<VariableFeeSchedule> rows = service.findByFundId(fundId)
                .collectList().block(Duration.ofSeconds(15));
        assertThat(rows).isNotNull();
        assertThat(rows).extracting(VariableFeeSchedule::getEffectiveFrom)
                .containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 1, 1));
    }
}
