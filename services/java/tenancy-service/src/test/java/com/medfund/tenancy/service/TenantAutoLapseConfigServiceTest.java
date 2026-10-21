package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.UpdateTenantAutoLapseConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantAutoLapseConfig;
import com.medfund.tenancy.repository.TenantAutoLapseConfigRepository;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantAutoLapseConfigService} (V133). Pins the
 * "unconfigured" read semantics (enabled=false + null threshold/grace,
 * never a fabricated default), the insert-vs-update upsert branch, the
 * validation guard (threshold + grace required when enabled=true), and
 * the audit event emission that names the tenant slug on both paths.
 */
@ExtendWith(MockitoExtension.class)
class TenantAutoLapseConfigServiceTest {

    @Mock private TenantAutoLapseConfigRepository repository;
    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private TenantAutoLapseConfigService service;

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void get_existingConfig_mapsEntity() {
        TenantAutoLapseConfig cfg = config(true, 3, 7);
        cfg.setTenantId(TENANT);
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.just(cfg));

        StepVerifier.create(service.get(TENANT))
                .assertNext(response -> {
                    assertThat(response.tenantId()).isEqualTo(TENANT);
                    assertThat(response.enabled()).isTrue();
                    assertThat(response.arrearsThresholdMonths()).isEqualTo(3);
                    assertThat(response.graceWindowDays()).isEqualTo(7);
                })
                .verifyComplete();
    }

    @Test
    void get_noRow_returnsUnconfiguredDefaults() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(TENANT))
                .assertNext(response -> {
                    assertThat(response.enabled()).isFalse();
                    assertThat(response.arrearsThresholdMonths()).isNull();
                    assertThat(response.graceWindowDays()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void upsert_noExistingRow_insertsAndAuditsCreate() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());
        when(r2dbcTemplate.insert(any(TenantAutoLapseConfig.class))).thenAnswer(inv -> {
            TenantAutoLapseConfig c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT, request(true, 3, 7), "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.enabled()).isTrue();
                    assertThat(response.arrearsThresholdMonths()).isEqualTo(3);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        AuditEvent event = eventCap.getValue();
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.entityType()).isEqualTo("TENANT_AUTO_LAPSE_CONFIG");
        assertThat(event.entityName()).isEqualTo("AutoLapseConfig for tenant acme");
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_existingRow_updatesAndAuditsUpdate() {
        TenantAutoLapseConfig existing = config(true, 3, 7);
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT);
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantAutoLapseConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT, request(true, 6, 14), "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.arrearsThresholdMonths()).isEqualTo(6);
                    assertThat(response.graceWindowDays()).isEqualTo(14);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        AuditEvent event = eventCap.getValue();
        assertThat(event.action()).isEqualTo("UPDATE");
        assertThat(event.oldValue()).containsEntry("arrearsThresholdMonths", 3);
        assertThat(event.newValue()).containsEntry("arrearsThresholdMonths", 6);
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void upsert_enabledWithoutThresholdOrGrace_rejects() {
        assertThatThrownBy(() -> service.upsert(TENANT,
                        new UpdateTenantAutoLapseConfigRequest(true, null, 7),
                        "actor", "actor@test")
                .block())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upsert(TENANT,
                        new UpdateTenantAutoLapseConfigRequest(true, 3, null),
                        "actor", "actor@test")
                .block())
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void upsert_disabledWithNullFields_allowedAndInserted() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());
        when(r2dbcTemplate.insert(any(TenantAutoLapseConfig.class))).thenAnswer(inv -> {
            TenantAutoLapseConfig c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT,
                        new UpdateTenantAutoLapseConfigRequest(false, null, null),
                        "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.enabled()).isFalse();
                    assertThat(response.arrearsThresholdMonths()).isNull();
                    assertThat(response.graceWindowDays()).isNull();
                })
                .verifyComplete();
    }

    private static UpdateTenantAutoLapseConfigRequest request(boolean enabled, Integer threshold, Integer grace) {
        return new UpdateTenantAutoLapseConfigRequest(enabled, threshold, grace);
    }

    private static TenantAutoLapseConfig config(boolean enabled, Integer threshold, Integer grace) {
        TenantAutoLapseConfig c = new TenantAutoLapseConfig();
        c.setEnabled(enabled);
        c.setArrearsThresholdMonths(threshold);
        c.setGraceWindowDays(grace);
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }

    private static Tenant tenant(String slug) {
        Tenant t = new Tenant();
        t.setSlug(slug);
        return t;
    }
}
