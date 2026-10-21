package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.CreateUnitLinkedFundRequest;
import com.medfund.user.dto.UpdateUnitLinkedFundRequest;
import com.medfund.user.entity.UnitLinkedFund;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.user.service.UnitLinkedFundService;
import com.medfund.user.service.UserEventPublisher;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §8 (I4) IT — end-to-end through {@link UnitLinkedFundService}:
 * create + update + delete + audit event emission with friendly
 * {@code entityName} ("<name> (<currency>)", per {@code feedback_audit_entity_name})
 * and the operator's actor identity flowing through from the controller boundary.
 *
 * <p>Distinct {@code flyway.table} + shared {@code db/vfa-fund-migration}
 * folder lets §8's four ITs coexist with peer user-service ITs on the shared
 * JVM-scoped Postgres testcontainer (Phase 4/5/6/7 precedent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/vfa-fund-migration",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_vfa_fund",
})
@Import(UnitLinkedFundIT.SecurityStub.class)
class UnitLinkedFundIT extends AbstractIntegrationTest {

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

    static final String TENANT_ID = "00000000-0000-4000-8000-0000000000c1";

    @Autowired private DatabaseClient db;
    @Autowired private UnitLinkedFundService service;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    @BeforeEach
    void wipe() {
        // Order matters — fee schedules + nav history + ledger cascade off the fund.
        db.sql("DELETE FROM variable_fee_schedule").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM policy_unit_ledger").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM fund_nav_history").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM unit_linked_fund").then().block(Duration.ofSeconds(10));
    }

    private CreateUnitLinkedFundRequest addRequest(String name, String currency, String assetClass) {
        return new CreateUnitLinkedFundRequest(name, currency, assetClass);
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_insertsRow_andEmitsAuditWithFriendlyName() {
        UUID actorId = UUID.randomUUID();

        var saved = service.create(addRequest("Balanced Growth USD", "USD", "MULTI_ASSET"),
                        actorId.toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Balanced Growth USD");
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getBaseAssetClass()).isEqualTo("MULTI_ASSET");
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getActorEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getActorId()).isEqualTo(actorId);

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM unit_linked_fund")
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(1L);

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-fund",
                saved.getId().toString(), "CREATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected CREATE audit event").isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("UnitLinkedFund");
        assertThat(audit.path("entityName").asText()).isEqualTo("Balanced Growth USD (USD)");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");
        assertThat(audit.path("actorId").asText()).isEqualTo(actorId.toString());
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_lowercaseCurrency_normalisedUppercase() {
        var saved = service.create(addRequest("Cash USD Fund", "usd", "MONEY_MARKET"),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();
        assertThat(saved.getCurrency()).isEqualTo("USD");
    }

    @Test
    @WithTenant(TENANT_ID)
    void create_duplicateName_returns409() {
        var first = addRequest("Balanced Growth USD", "USD", "MULTI_ASSET");
        service.create(first, UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        StepVerifier.create(
                service.create(first, UUID.randomUUID().toString(), "bob@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(409);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void update_mutatesNameAndActiveFlag_andEmitsAudit() {
        var saved = service.create(addRequest("Balanced Growth USD", "USD", "MULTI_ASSET"),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();

        UUID updaterId = UUID.randomUUID();
        var updated = service.update(saved.getId(),
                        new UpdateUnitLinkedFundRequest("Balanced Growth USD (v2)", "EQUITY", Boolean.FALSE),
                        updaterId.toString(), "bob@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("Balanced Growth USD (v2)");
        assertThat(updated.getBaseAssetClass()).isEqualTo("EQUITY");
        assertThat(updated.getIsActive()).isFalse();
        assertThat(updated.getCurrency()).isEqualTo("USD"); // immutable
        assertThat(updated.getActorId()).isEqualTo(updaterId);

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-fund",
                saved.getId().toString(), "UPDATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected UPDATE audit").isNotNull();
        assertThat(audit.path("entityName").asText()).isEqualTo("Balanced Growth USD (v2) (USD)");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("bob@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void update_unknown_returns404() {
        StepVerifier.create(
                service.update(UUID.randomUUID(),
                                new UpdateUnitLinkedFundRequest("does-not-matter", "EQUITY", Boolean.TRUE),
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(UnitLinkedFundNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void delete_dropsRow_andEmitsAudit() {
        var saved = service.create(addRequest("Cash USD", "USD", "MONEY_MARKET"),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(saved).isNotNull();

        service.delete(saved.getId(), UUID.randomUUID().toString(), "charlie@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        Long count = db.sql("SELECT COUNT(*)::bigint AS n FROM unit_linked_fund WHERE id = :id")
                .bind("id", saved.getId())
                .map((r, meta) -> r.get("n", Long.class))
                .one().block(Duration.ofSeconds(10));
        assertThat(count).isEqualTo(0L);

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-fund",
                saved.getId().toString(), "DELETE", Duration.ofSeconds(15));
        assertThat(audit).as("expected DELETE audit").isNotNull();
        assertThat(audit.path("actorEmail").asText()).isEqualTo("charlie@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void findAll_activeFirstThenByName() {
        service.create(addRequest("Zulu Equity", "USD", "EQUITY"),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        var alpha = service.create(addRequest("Alpha Cash", "USD", "MONEY_MARKET"),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(alpha).isNotNull();
        service.update(alpha.getId(),
                        new UpdateUnitLinkedFundRequest("Alpha Cash", "MONEY_MARKET", Boolean.FALSE),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        List<UnitLinkedFund> rows = service.findAll().collectList().block(Duration.ofSeconds(15));
        assertThat(rows).isNotNull();
        assertThat(rows).extracting(UnitLinkedFund::getName)
                .containsExactly("Zulu Equity", "Alpha Cash");

        List<UnitLinkedFund> active = service.findActive().collectList().block(Duration.ofSeconds(15));
        assertThat(active).extracting(UnitLinkedFund::getName)
                .containsExactly("Zulu Equity");
    }
}
