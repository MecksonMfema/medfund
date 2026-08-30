package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.CreatePolicyUnitLedgerRequest;
import com.medfund.user.dto.CreateUnitLinkedFundRequest;
import com.medfund.user.entity.PolicyUnitLedger;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.service.KeycloakSyncService;
import com.medfund.user.service.PolicyUnitLedgerService;
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
 * Phase 15 §8 (I4) IT — append-only policy unit ledger + audit + per-policy
 * / per-fund lookups.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/vfa-fund-migration",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_policy_unit_ledger",
})
@Import(PolicyUnitLedgerIT.SecurityStub.class)
class PolicyUnitLedgerIT extends AbstractIntegrationTest {

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

    static final String TENANT_ID = "00000000-0000-4000-8000-0000000000c3";

    @Autowired private DatabaseClient db;
    @Autowired private PolicyUnitLedgerService service;
    @Autowired private UnitLinkedFundService fundService;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID fundId;

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM policy_unit_ledger").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM variable_fee_schedule").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM fund_nav_history").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM unit_linked_fund").then().block(Duration.ofSeconds(10));

        var fund = fundService.create(
                        new CreateUnitLinkedFundRequest("Ledger Test Fund", "USD", "EQUITY"),
                        UUID.randomUUID().toString(), "seed@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(fund).isNotNull();
        fundId = fund.getId();
    }

    @Test
    @WithTenant(TENANT_ID)
    void append_insertsRow_andEmitsAuditWithFriendlyName() {
        UUID policyId = UUID.randomUUID();
        var request = new CreatePolicyUnitLedgerRequest(
                policyId, LocalDate.of(2026, 8, 15), "PURCHASE",
                new BigDecimal("1000.000000"), new BigDecimal("1.2340"));

        var saved = service.append(fundId, request,
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPolicyId()).isEqualTo(policyId);
        assertThat(saved.getFundId()).isEqualTo(fundId);
        assertThat(saved.getTransactionType()).isEqualTo("PURCHASE");
        assertThat(saved.getUnits()).isEqualByComparingTo("1000.000000");

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-ledger",
                saved.getId().toString(), "CREATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected CREATE audit").isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("PolicyUnitLedger");
        assertThat(audit.path("entityName").asText())
                .isEqualTo("policy:" + policyId + " PURCHASE 1000.000000 units");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void append_unknownFund_returns404() {
        var request = new CreatePolicyUnitLedgerRequest(
                UUID.randomUUID(), LocalDate.of(2026, 8, 15), "PURCHASE",
                new BigDecimal("1000.000000"), new BigDecimal("1.2340"));
        StepVerifier.create(
                service.append(UUID.randomUUID(), request,
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(UnitLinkedFundNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void findByPolicyId_returnsRowsDesc() {
        UUID policyId = UUID.randomUUID();
        service.append(fundId, new CreatePolicyUnitLedgerRequest(
                                policyId, LocalDate.of(2026, 1, 10), "PURCHASE",
                                new BigDecimal("500"), new BigDecimal("1.0000")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.append(fundId, new CreatePolicyUnitLedgerRequest(
                                policyId, LocalDate.of(2026, 4, 12), "FEE_DEDUCTION",
                                new BigDecimal("5"), new BigDecimal("1.2500")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        List<PolicyUnitLedger> rows = service.findByPolicyId(policyId)
                .collectList().block(Duration.ofSeconds(15));
        assertThat(rows).isNotNull();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getTransactionDate()).isEqualTo(LocalDate.of(2026, 4, 12));
        assertThat(rows.get(1).getTransactionDate()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void findByFundId_returnsAllPoliciesRows() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        service.append(fundId, new CreatePolicyUnitLedgerRequest(
                                p1, LocalDate.of(2026, 3, 1), "PURCHASE",
                                new BigDecimal("100"), new BigDecimal("1.0000")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        service.append(fundId, new CreatePolicyUnitLedgerRequest(
                                p2, LocalDate.of(2026, 3, 2), "PURCHASE",
                                new BigDecimal("200"), new BigDecimal("1.0000")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        List<PolicyUnitLedger> rows = service.findByFundId(fundId)
                .collectList().block(Duration.ofSeconds(15));
        assertThat(rows).isNotNull();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(PolicyUnitLedger::getPolicyId)
                .containsExactlyInAnyOrder(p1, p2);
    }
}
