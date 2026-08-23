package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.dto.UpdateProducerRequest;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.producer.service.CommissionRateCardService;
import com.medfund.finance.producer.service.ProducerService;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the producer / rate-card CRUD surface. Real
 * Postgres (Testcontainers) + real R2DBC + real services; only
 * {@link AuditPublisher} is mocked because there's no broker in the harness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ProducerCrudIT.SecurityStub.class)
class ProducerCrudIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000011";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ProducerService producerService;
    @Autowired private CommissionRateCardService rateCardService;
    @Autowired private ProducerRepository producerRepository;
    @Autowired private CommissionRateCardRepository rateCardRepository;
    @MockBean private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void producer_fullLifecycle_createUpdateHierarchy_persistsAndEmitsFriendlyAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        // 1. Create parent
        var parent = producerService.create(
                        new CreateProducerRequest("BRK-PARENT-IT",
                                "Parent Broker IT",
                                "parent@broker.example", "+263711000000", "ZW",
                                "USD", null, new BigDecimal("15.00"), null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(parent).isNotNull();
        assertThat(parent.producerCode()).isEqualTo("BRK-PARENT-IT");
        assertThat(parent.active()).isTrue();

        // 2. Create child with parent
        var child = producerService.create(
                        new CreateProducerRequest("BRK-CHILD-IT",
                                "Child Broker IT",
                                null, null, "ZW",
                                "USD", parent.id(), null, null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(child).isNotNull();
        assertThat(child.parentProducerId()).isEqualTo(parent.id());

        // 3. Duplicate producer_code → 409 (IllegalStateException)
        assertThatThrownBy(() -> producerService.create(
                        new CreateProducerRequest("BRK-CHILD-IT", "Dup", null, null, null,
                                "USD", null, null, null),
                        "sys", "a@b")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class);

        // 4. Reparent parent under child → cycle → 400
        UpdateProducerRequest cycleReq = new UpdateProducerRequest(
                "Parent Broker IT", null, null, "ZW",
                "USD", child.id(), null, null, true);
        assertThatThrownBy(() -> producerService.update(parent.id(), cycleReq, "sys", "a@b")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);

        // 5. Legitimate rename + deactivate the child
        var deactivated = producerService.update(child.id(),
                        new UpdateProducerRequest("Child Broker Renamed IT",
                                null, null, "ZW", "USD", parent.id(),
                                null, null, false),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(deactivated).isNotNull();
        assertThat(deactivated.active()).isFalse();
        assertThat(deactivated.name()).isEqualTo("Child Broker Renamed IT");

        // 6. Ancestry walks upward
        List<UUID> ancestryIds = producerService.ancestry(child.id())
                .map(r -> r.id())
                .collectList()
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(ancestryIds).contains(child.id(), parent.id());

        // 7. Every audit event uses producer_code as entityName (feedback_audit_entity_name)
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        List<AuditEvent> emitted = cap.getAllValues();
        assertThat(emitted).allSatisfy(ev -> {
            assertThat(ev.entityType()).isEqualTo("Producer");
            assertThat(ev.entityName()).isIn("BRK-PARENT-IT", "BRK-CHILD-IT");
            assertThat(ev.actorEmail()).isEqualTo("admin@test.example");
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void rateCard_softDeactivate_snapsEffectiveTo_toLastDayOfMonth() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var card = rateCardService.create(
                        new CreateRateCardRequest("Std HEALTH IT", "HEALTH", "DIRECT",
                                new BigDecimal("8.00"), 90,
                                LocalDate.of(2026, 3, 15), null),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(card).isNotNull();
        assertThat(card.effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));

        var deactivated = rateCardService.deactivate(card.id(), "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(deactivated).isNotNull();
        assertThat(deactivated.active()).isFalse();
        assertThat(deactivated.effectiveTo())
                .isEqualTo(LocalDate.now().with(TemporalAdjusters.lastDayOfMonth()));

        var reloaded = rateCardRepository.findById(card.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getActive()).isFalse();
    }

    @Test
    @WithTenant(TENANT_ID)
    void rateCard_findApplicable_picksTierScopedOverTierAgnostic() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        // Tier-agnostic fallback
        rateCardService.create(new CreateRateCardRequest("Fallback HEALTH", "HEALTH", null,
                        new BigDecimal("5.00"), null,
                        LocalDate.of(2026, 1, 1), null),
                "sys", "a@b").contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // Tier-scoped DIRECT card should win
        rateCardService.create(new CreateRateCardRequest("Direct HEALTH", "HEALTH", "DIRECT",
                        new BigDecimal("10.00"), null,
                        LocalDate.of(2026, 1, 1), null),
                "sys", "a@b").contextWrite(TenantTestContext.put()).block(TIMEOUT);

        var picked = rateCardService.findApplicable("HEALTH", "DIRECT", LocalDate.of(2026, 6, 1))
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(picked).isNotNull();
        assertThat(picked.name()).isEqualTo("Direct HEALTH");
    }
}
