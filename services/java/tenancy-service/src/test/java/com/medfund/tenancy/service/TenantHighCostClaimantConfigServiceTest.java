package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.TenantHighCostClaimantConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantHighCostClaimantConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantHighCostClaimantConfig;
import com.medfund.tenancy.repository.TenantHighCostClaimantConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantHighCostClaimantConfigService} (V132).
 * Pins the "unconfigured" read semantics (nulls, never a fabricated
 * default), the insert vs update upsert branch, and the audit event
 * emission that names the tenant slug on both paths.
 */
@ExtendWith(MockitoExtension.class)
class TenantHighCostClaimantConfigServiceTest {

    @Mock private TenantHighCostClaimantConfigRepository repository;
    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private TenantHighCostClaimantConfigService service;

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void get_existingConfig_mapsEntity() {
        TenantHighCostClaimantConfig cfg = config("500.00", "USD");
        cfg.setTenantId(TENANT);
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.just(cfg));

        StepVerifier.create(service.get(TENANT))
                .assertNext(response -> {
                    assertThat(response.tenantId()).isEqualTo(TENANT);
                    assertThat(response.thresholdAmount()).isEqualByComparingTo("500.00");
                    assertThat(response.currencyCode()).isEqualTo("USD");
                    assertThat(response.updatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void get_noRow_returnsUnconfiguredNulls() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(TENANT))
                .assertNext(response -> {
                    assertThat(response.tenantId()).isEqualTo(TENANT);
                    assertThat(response.thresholdAmount()).isNull();
                    assertThat(response.currencyCode()).isNull();
                    assertThat(response.updatedAt()).isNull();
                    assertThat(response.updatedBy()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void upsert_noExistingRow_insertsAndAuditsCreate() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());
        when(r2dbcTemplate.insert(any(TenantHighCostClaimantConfig.class))).thenAnswer(inv -> {
            TenantHighCostClaimantConfig c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT, request("750.00", "ZAR"), "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.thresholdAmount()).isEqualByComparingTo("750.00");
                    assertThat(response.currencyCode()).isEqualTo("ZAR");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        AuditEvent event = eventCap.getValue();
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.entityType()).isEqualTo("TENANT_HIGH_COST_CLAIMANT_CONFIG");
        assertThat(event.entityName()).isEqualTo("HighCostClaimantConfig for tenant acme");
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_existingRow_updatesAndAuditsUpdate() {
        TenantHighCostClaimantConfig existing = config("500.00", "USD");
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT);
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantHighCostClaimantConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT, request("1000.00", "USD"), "actor", "actor@test"))
                .assertNext(response -> assertThat(response.thresholdAmount()).isEqualByComparingTo("1000.00"))
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        AuditEvent event = eventCap.getValue();
        assertThat(event.action()).isEqualTo("UPDATE");
        assertThat(event.entityName()).isEqualTo("HighCostClaimantConfig for tenant acme");
        assertThat(event.oldValue()).containsKey("thresholdAmount");
        assertThat(event.newValue()).containsEntry("thresholdAmount", new BigDecimal("1000.00"));
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void upsert_missingTenantSlug_auditsWithUnknownFallback() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());
        when(r2dbcTemplate.insert(any(TenantHighCostClaimantConfig.class))).thenAnswer(inv -> {
            TenantHighCostClaimantConfig c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.empty());
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT, request("100.00", "USD"), "actor", "actor@test"))
                .assertNext(response -> assertThat(response.thresholdAmount()).isEqualByComparingTo("100.00"))
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        assertThat(eventCap.getValue().entityName()).isEqualTo("HighCostClaimantConfig for tenant unknown");
    }

    private static UpdateTenantHighCostClaimantConfigRequest request(String amount, String ccy) {
        return new UpdateTenantHighCostClaimantConfigRequest(new BigDecimal(amount), ccy);
    }

    private static TenantHighCostClaimantConfig config(String amount, String ccy) {
        TenantHighCostClaimantConfig c = new TenantHighCostClaimantConfig();
        c.setThresholdAmount(new BigDecimal(amount));
        c.setCurrencyCode(ccy);
        c.setUpdatedAt(OffsetDateTime.now());
        c.setUpdatedBy(UUID.randomUUID());
        return c;
    }

    private static Tenant tenant(String slug) {
        Tenant t = new Tenant();
        t.setSlug(slug);
        return t;
    }
}
