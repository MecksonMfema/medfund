package com.medfund.finance.regulatory.aml.service;

import com.medfund.finance.regulatory.aml.dto.AddTenantAmlThresholdConfigRequest;
import com.medfund.finance.regulatory.aml.dto.UpdateTenantAmlThresholdConfigRequest;
import com.medfund.finance.regulatory.aml.entity.TenantAmlThresholdConfig;
import com.medfund.finance.regulatory.aml.repository.TenantAmlThresholdConfigRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantAmlThresholdConfigServiceTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String ACTOR_ID = "cccccccc-0000-4000-8000-000000000001";
    private static final String ACTOR_EMAIL = "admin@medfund";

    @Mock TenantAmlThresholdConfigRepository repository;
    @Mock R2dbcEntityTemplate r2dbcTemplate;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks TenantAmlThresholdConfigService service;

    // ── add ───────────────────────────────────────────────────────────

    @Test
    void add_happyPath_insertsRowAndAudits_withFriendlyEntityName() {
        when(r2dbcTemplate.insert(any(TenantAmlThresholdConfig.class))).thenAnswer(inv -> {
            TenantAmlThresholdConfig r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        AddTenantAmlThresholdConfigRequest req = new AddTenantAmlThresholdConfigRequest(
                "PREMIUM", new BigDecimal("25000.00"), "ZAR",
                LocalDate.of(2026, 4, 1), null, "FIC CTR default");

        StepVerifier.create(service.add(TENANT, req, ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.transactionType()).isEqualTo("PREMIUM");
                    assertThat(resp.thresholdAmount()).isEqualByComparingTo("25000.00");
                    assertThat(resp.currency()).isEqualTo("ZAR");
                    assertThat(resp.actorEmail()).isEqualTo(ACTOR_EMAIL);
                    assertThat(resp.sourceNote()).isEqualTo("FIC CTR default");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        AuditEvent event = cap.getValue();
        assertThat(event.entityType()).isEqualTo("TENANT_AML_THRESHOLD_CONFIG");
        assertThat(event.action()).isEqualTo("CREATE");
        // Friendly entityName includes tenant + type + currency + amount — never the UUID
        assertThat(event.entityName())
                .contains(TENANT.toString())
                .contains("PREMIUM")
                .contains("ZAR")
                .contains("25000.00");
        assertThat(event.entityName()).doesNotStartWith(event.entityId());
    }

    @Test
    void add_lowercaseCurrency_isUppercased_beforeInsert() {
        when(r2dbcTemplate.insert(any(TenantAmlThresholdConfig.class))).thenAnswer(inv -> {
            TenantAmlThresholdConfig r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        AddTenantAmlThresholdConfigRequest req = new AddTenantAmlThresholdConfigRequest(
                "CLAIM_PAYOUT", new BigDecimal("10000"), "zar", null, null, null);

        service.add(TENANT, req, ACTOR_ID, ACTOR_EMAIL).block();

        ArgumentCaptor<TenantAmlThresholdConfig> cap =
                ArgumentCaptor.forClass(TenantAmlThresholdConfig.class);
        verify(r2dbcTemplate).insert(cap.capture());
        assertThat(cap.getValue().getCurrency()).isEqualTo("ZAR");
    }

    @Test
    void add_defaultsEffectiveFrom_toToday_whenOmitted() {
        when(r2dbcTemplate.insert(any(TenantAmlThresholdConfig.class))).thenAnswer(inv -> {
            TenantAmlThresholdConfig r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        AddTenantAmlThresholdConfigRequest req = new AddTenantAmlThresholdConfigRequest(
                "OTHER", new BigDecimal("25000.00"), "USD", null, null, null);

        service.add(TENANT, req, ACTOR_ID, ACTOR_EMAIL).block();

        ArgumentCaptor<TenantAmlThresholdConfig> cap =
                ArgumentCaptor.forClass(TenantAmlThresholdConfig.class);
        verify(r2dbcTemplate).insert(cap.capture());
        assertThat(cap.getValue().getEffectiveFrom()).isEqualTo(LocalDate.now());
    }

    @Test
    void add_missingActor_isRejected() {
        AddTenantAmlThresholdConfigRequest req = new AddTenantAmlThresholdConfigRequest(
                "PREMIUM", new BigDecimal("1000"), "ZAR", null, null, null);

        assertThatThrownBy(() -> service.add(TENANT, req, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actorId and actorEmail");

        verify(r2dbcTemplate, never()).insert(any(TenantAmlThresholdConfig.class));
    }

    // ── update ────────────────────────────────────────────────────────

    @Test
    void update_happyPath_updatesMutableFields_andAudits() {
        UUID id = UUID.randomUUID();
        TenantAmlThresholdConfig existing = row(id, TENANT, "PREMIUM", "25000.00", "ZAR");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        UpdateTenantAmlThresholdConfigRequest req = new UpdateTenantAmlThresholdConfigRequest(
                new BigDecimal("15000.00"), LocalDate.of(2026, 12, 31), "revised risk-based");

        StepVerifier.create(service.update(TENANT, id, req, ACTOR_ID, ACTOR_EMAIL))
                .assertNext(resp -> {
                    assertThat(resp.thresholdAmount()).isEqualByComparingTo("15000.00");
                    assertThat(resp.effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
                    assertThat(resp.sourceNote()).isEqualTo("revised risk-based");
                    // transactionType + currency stay pinned
                    assertThat(resp.transactionType()).isEqualTo("PREMIUM");
                    assertThat(resp.currency()).isEqualTo("ZAR");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("UPDATE");
        assertThat(cap.getValue().changedFields()).containsExactlyInAnyOrder(
                "thresholdAmount", "effectiveTo", "sourceNote");
    }

    @Test
    void update_crossTenant_isRejected() {
        UUID id = UUID.randomUUID();
        TenantAmlThresholdConfig existing = row(id, OTHER_TENANT, "PREMIUM", "25000", "ZAR");
        when(repository.findById(id)).thenReturn(Mono.just(existing));

        UpdateTenantAmlThresholdConfigRequest req = new UpdateTenantAmlThresholdConfigRequest(
                new BigDecimal("10000"), null, null);

        StepVerifier.create(service.update(TENANT, id, req, ACTOR_ID, ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("does not belong to tenant");
                })
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void update_notFound_is404_shape() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        UpdateTenantAmlThresholdConfigRequest req = new UpdateTenantAmlThresholdConfigRequest(
                new BigDecimal("10000"), null, null);

        StepVerifier.create(service.update(TENANT, id, req, ACTOR_ID, ACTOR_EMAIL))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(NoSuchElementException.class);
                    assertThat(err.getMessage()).contains("Tenant AML threshold config not found");
                })
                .verify();
    }

    // ── delete ────────────────────────────────────────────────────────

    @Test
    void delete_happyPath_removesRowAndAuditsWithDeleteAction() {
        UUID id = UUID.randomUUID();
        TenantAmlThresholdConfig existing = row(id, TENANT, "PREMIUM", "25000", "ZAR");
        when(repository.findById(id)).thenReturn(Mono.just(existing));
        when(repository.delete(existing)).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(TENANT, id, ACTOR_ID, ACTOR_EMAIL))
                .verifyComplete();

        verify(repository).delete(existing);
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().action()).isEqualTo("DELETE");
    }

    @Test
    void delete_crossTenant_isRejected() {
        UUID id = UUID.randomUUID();
        TenantAmlThresholdConfig existing = row(id, OTHER_TENANT, "PREMIUM", "25000", "ZAR");
        when(repository.findById(id)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.delete(TENANT, id, ACTOR_ID, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).delete(any(TenantAmlThresholdConfig.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static TenantAmlThresholdConfig row(UUID id, UUID tenantId, String type, String amount, String currency) {
        TenantAmlThresholdConfig r = new TenantAmlThresholdConfig();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setTransactionType(type);
        r.setThresholdAmount(new BigDecimal(amount));
        r.setCurrency(currency);
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }
}
