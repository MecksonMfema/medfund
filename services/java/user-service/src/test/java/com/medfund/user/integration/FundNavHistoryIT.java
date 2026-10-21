package com.medfund.user.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import com.medfund.user.dto.CreateFundNavHistoryRequest;
import com.medfund.user.dto.CreateUnitLinkedFundRequest;
import com.medfund.user.entity.FundNavHistory;
import com.medfund.user.exception.UnitLinkedFundNotFoundException;
import com.medfund.user.service.FundNavHistoryService;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 §8 (I4) IT — Fund NAV history append + duplicate reject +
 * future-date reject + latest-for-asOf lookup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/vfa-fund-migration",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.baseline-version=0",
    "spring.flyway.table=flyway_history_fund_nav",
})
@Import(FundNavHistoryIT.SecurityStub.class)
class FundNavHistoryIT extends AbstractIntegrationTest {

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

    static final String TENANT_ID = "00000000-0000-4000-8000-0000000000c2";

    @Autowired private DatabaseClient db;
    @Autowired private FundNavHistoryService navService;
    @Autowired private UnitLinkedFundService fundService;

    @MockBean private UserEventPublisher userEventPublisher;
    @MockBean private KeycloakSyncService keycloakSyncService;

    private UUID fundId;

    @BeforeEach
    void seed() {
        db.sql("DELETE FROM fund_nav_history").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM variable_fee_schedule").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM policy_unit_ledger").then().block(Duration.ofSeconds(10));
        db.sql("DELETE FROM unit_linked_fund").then().block(Duration.ofSeconds(10));

        var fund = fundService.create(
                        new CreateUnitLinkedFundRequest("NAV Test Fund", "USD", "MULTI_ASSET"),
                        UUID.randomUUID().toString(), "seed@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        assertThat(fund).isNotNull();
        fundId = fund.getId();
    }

    @Test
    @WithTenant(TENANT_ID)
    void append_insertsRow_andEmitsAuditWithFriendlyName() {
        var request = new CreateFundNavHistoryRequest(
                LocalDate.now().minusDays(1), new BigDecimal("1.2340"));

        var saved = navService.create(fundId, request,
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getFundId()).isEqualTo(fundId);
        assertThat(saved.getNavPerUnit()).isEqualByComparingTo("1.2340");
        assertThat(saved.getSource()).isEqualTo("ADMIN");

        JsonNode audit = AuditEventProbe.pollForEntity(
                KAFKA.getBootstrapServers(), "audit-nav",
                saved.getId().toString(), "CREATE", Duration.ofSeconds(15));
        assertThat(audit).as("expected CREATE audit").isNotNull();
        assertThat(audit.path("entityType").asText()).isEqualTo("FundNavHistory");
        assertThat(audit.path("entityName").asText()).startsWith("NAV 1.2340 @ ");
        assertThat(audit.path("actorEmail").asText()).isEqualTo("alice@example.com");
    }

    @Test
    @WithTenant(TENANT_ID)
    void append_futureDate_returns400() {
        var request = new CreateFundNavHistoryRequest(
                LocalDate.now().plusDays(1), new BigDecimal("1.2340"));
        StepVerifier.create(
                navService.create(fundId, request,
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(400);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void append_duplicate_returns409() {
        var request = new CreateFundNavHistoryRequest(
                LocalDate.now().minusDays(1), new BigDecimal("1.2340"));
        navService.create(fundId, request,
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        StepVerifier.create(
                navService.create(fundId, request,
                                UUID.randomUUID().toString(), "bob@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(409);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void append_unknownFund_returns404() {
        var request = new CreateFundNavHistoryRequest(
                LocalDate.now().minusDays(1), new BigDecimal("1.2340"));
        StepVerifier.create(
                navService.create(UUID.randomUUID(), request,
                                UUID.randomUUID().toString(), "alice@example.com")
                        .contextWrite(TenantTestContext.put()))
                .expectError(UnitLinkedFundNotFoundException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @WithTenant(TENANT_ID)
    void findLatestFor_returnsMostRecentOnOrBefore() {
        navService.create(fundId, new CreateFundNavHistoryRequest(
                                LocalDate.of(2026, 1, 15), new BigDecimal("1.0000")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        navService.create(fundId, new CreateFundNavHistoryRequest(
                                LocalDate.of(2026, 3, 20), new BigDecimal("1.2500")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        FundNavHistory asOfFeb = navService.findLatestFor(fundId, LocalDate.of(2026, 2, 28))
                .block(Duration.ofSeconds(10));
        assertThat(asOfFeb).isNotNull();
        assertThat(asOfFeb.getNavPerUnit()).isEqualByComparingTo("1.0000");

        FundNavHistory asOfApril = navService.findLatestFor(fundId, LocalDate.of(2026, 4, 1))
                .block(Duration.ofSeconds(10));
        assertThat(asOfApril).isNotNull();
        assertThat(asOfApril.getNavPerUnit()).isEqualByComparingTo("1.2500");

        FundNavHistory beforeAny = navService.findLatestFor(fundId, LocalDate.of(2025, 12, 1))
                .block(Duration.ofSeconds(10));
        assertThat(beforeAny).isNull();
    }

    @Test
    @WithTenant(TENANT_ID)
    void findByFundId_orderedDesc() {
        navService.create(fundId, new CreateFundNavHistoryRequest(
                                LocalDate.of(2026, 1, 15), new BigDecimal("1.0000")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));
        navService.create(fundId, new CreateFundNavHistoryRequest(
                                LocalDate.of(2026, 3, 20), new BigDecimal("1.2500")),
                        UUID.randomUUID().toString(), "alice@example.com")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(15));

        List<FundNavHistory> rows = navService.findByFundId(fundId)
                .collectList().block(Duration.ofSeconds(15));
        assertThat(rows).isNotNull();
        assertThat(rows).extracting(FundNavHistory::getValuationDate)
                .containsExactly(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 1, 15));
    }
}
