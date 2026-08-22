package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpdateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.UpdateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyLayerRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.ReinsurerRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.finance.reinsurance.service.CessionRuleService;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
import com.medfund.finance.reinsurance.service.TreatyLayerService;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
import com.medfund.finance.reinsurance.service.TreatyService;
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
 * End-to-end integration for the reinsurance CRUD surface. Real Postgres
 * (Testcontainers) + real R2DBC + real service layer; only AuditPublisher
 * is mocked because there's no broker in the test harness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ReinsuranceCrudIT.SecurityStub.class)
class ReinsuranceCrudIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000010";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyLayerService layerService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;
    @Autowired private CessionRuleService cessionRuleService;
    @Autowired private ReinsurerRepository reinsurerRepository;
    @Autowired private TreatyRepository treatyRepository;
    @MockBean private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void reinsurer_cudCycle_persistsAndEmitsAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        var created = reinsurerService.create(
                        new CreateReinsurerRequest("Munich Re IT", "ops@munich.example",
                                "Munich", "DE", "EUR", "AA+"),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(created).isNotNull();
        assertThat(created.name()).isEqualTo("Munich Re IT");

        var updated = reinsurerService.update(created.id(),
                        new UpdateReinsurerRequest("Munich Re IT", "new@munich.example",
                                "Munich", "DE", "EUR", "AA", false),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(updated).isNotNull();
        assertThat(updated.active()).isFalse();

        var reloaded = reinsurerRepository.findById(created.id())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("Munich Re IT");
        assertThat(reloaded.getActive()).isFalse();
        assertThat(reloaded.getContactEmail()).isEqualTo("new@munich.example");

        // Two audit events (CREATE + UPDATE), both with entityName = reinsurer name.
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        List<AuditEvent> emitted = cap.getAllValues();
        assertThat(emitted).extracting(AuditEvent::action).contains("CREATE", "UPDATE");
        assertThat(emitted).allSatisfy(ev -> {
            assertThat(ev.entityType()).isEqualTo("Reinsurer");
            assertThat(ev.entityName()).isEqualTo("Munich Re IT");
            assertThat(ev.actorEmail()).isEqualTo("admin@test.example");
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void treatyLifecycle_draftToActive_runsValidationAndTransitions() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        // Two reinsurers → 60 / 40 split.
        var munich = reinsurerService.create(
                        new CreateReinsurerRequest("Munich Re IT2", null, null, null, null, null),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        var swiss = reinsurerService.create(
                        new CreateReinsurerRequest("Swiss Re IT2", null, null, null, null, null),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("HEALTH-XOL-IT-2026", "EXCESS_OF_LOSS", "USD",
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                                null, null, new BigDecimal("50000.00"), null),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo("DRAFT");

        // Activate before validation prereqs → 400.
        assertThatThrownBy(() -> treatyService.activate(draft.id(), "sys", "a@t")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");

        // Add participants (60 / 40 sums to 100).
        participantService.upsert(draft.id(),
                        new UpsertTreatyParticipantRequest(munich.id(),
                                new BigDecimal("60.0000"), "LEADER"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        participantService.upsert(draft.id(),
                        new UpsertTreatyParticipantRequest(swiss.id(),
                                new BigDecimal("40.0000"), "FOLLOWING"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // Still no applicable line + no layer → 400 with the applicable-line message.
        assertThatThrownBy(() -> treatyService.activate(draft.id(), "sys", "a@t")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insurance line");

        // Add applicable line.
        applicableLineService.add(draft.id(),
                        new CreateTreatyApplicableLineRequest("HEALTH"),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        // XoL still needs a layer → 400.
        assertThatThrownBy(() -> treatyService.activate(draft.id(), "sys", "a@t")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XoL/StopLoss");

        // Add a single layer, then activate — success.
        layerService.create(draft.id(),
                        new UpsertTreatyLayerRequest(1,
                                new BigDecimal("10000.0000"), new BigDecimal("40000.0000"),
                                "USD", new BigDecimal("0.02"), 1),
                        "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        var activated = treatyService.activate(draft.id(), "sys", "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(activated).isNotNull();
        assertThat(activated.status()).isEqualTo("ACTIVE");
        assertThat(activated.activatedAt()).isNotNull();

        // Edit on an ACTIVE treaty → 409.
        assertThatThrownBy(() -> treatyService.update(draft.id(),
                        new UpdateTreatyRequest("HEALTH-XOL-IT-2026", "EXCESS_OF_LOSS", "USD",
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                                null, null, null, null),
                        "sys", "a@t")
                        .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class);

        // Confirm reload sees ACTIVE.
        Treaty reloaded = treatyRepository.findById(draft.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
    }

}
