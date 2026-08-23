package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.UpdateTenantEndorsementConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantEndorsementConfig;
import com.medfund.tenancy.repository.TenantEndorsementConfigRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantEndorsementConfigService} (V134). Mirrors
 * {@code TenantAutoLapseConfigServiceTest}: unconfigured read semantics,
 * insert-vs-update upsert branch, enabled-requires-threshold guard, and
 * the audit event that names the tenant slug on both paths.
 */
@ExtendWith(MockitoExtension.class)
class TenantEndorsementConfigServiceTest {

    @Mock private TenantEndorsementConfigRepository repository;
    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private TenantEndorsementConfigService service;

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void get_noRow_returnsUnconfiguredDefaults() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(TENANT))
                .assertNext(response -> {
                    assertThat(response.enabled()).isFalse();
                    assertThat(response.fourEyesThresholdAmount()).isNull();
                    assertThat(response.thresholdCurrency()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void upsert_noExistingRow_insertsAndAuditsCreate() {
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.empty());
        when(r2dbcTemplate.insert(any(TenantEndorsementConfig.class))).thenAnswer(inv -> {
            TenantEndorsementConfig c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT,
                        request(true, new BigDecimal("500.00"), "USD"),
                        "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.enabled()).isTrue();
                    assertThat(response.fourEyesThresholdAmount()).isEqualByComparingTo("500.00");
                    assertThat(response.thresholdCurrency()).isEqualTo("USD");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        AuditEvent event = eventCap.getValue();
        assertThat(event.action()).isEqualTo("CREATE");
        assertThat(event.entityType()).isEqualTo("TENANT_ENDORSEMENT_CONFIG");
        assertThat(event.entityName()).isEqualTo("EndorsementConfig for tenant acme");
        verify(repository, never()).save(any());
    }

    @Test
    void upsert_existingRow_updatesAndAuditsUpdate() {
        TenantEndorsementConfig existing = config(true, new BigDecimal("100.00"), "USD");
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT);
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantEndorsementConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT,
                        request(true, new BigDecimal("500.00"), "USD"),
                        "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.fourEyesThresholdAmount()).isEqualByComparingTo("500.00");
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> eventCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCap.capture());
        AuditEvent event = eventCap.getValue();
        assertThat(event.action()).isEqualTo("UPDATE");
        assertThat(event.oldValue()).containsEntry("fourEyesThresholdAmount", "100.00");
        assertThat(event.newValue()).containsEntry("fourEyesThresholdAmount", "500.00");
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void upsert_enabledWithoutThresholdOrCurrency_rejects() {
        assertThatThrownBy(() -> service.upsert(TENANT,
                        new UpdateTenantEndorsementConfigRequest(true, null, "USD"),
                        "actor", "actor@test")
                .block())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upsert(TENANT,
                        new UpdateTenantEndorsementConfigRequest(true, new BigDecimal("100.00"), null),
                        "actor", "actor@test")
                .block())
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void upsert_disabledClearsThreshold() {
        // An enable→disable transition must null out the threshold so a
        // subsequent enable starts from a clean state.
        TenantEndorsementConfig existing = config(true, new BigDecimal("500.00"), "USD");
        existing.setId(UUID.randomUUID());
        existing.setTenantId(TENANT);
        when(repository.findByTenantId(TENANT)).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantEndorsementConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tenantRepository.findById(TENANT)).thenReturn(Mono.just(tenant("acme")));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.upsert(TENANT,
                        new UpdateTenantEndorsementConfigRequest(false, null, null),
                        "actor", "actor@test"))
                .assertNext(response -> {
                    assertThat(response.enabled()).isFalse();
                    assertThat(response.fourEyesThresholdAmount()).isNull();
                    assertThat(response.thresholdCurrency()).isNull();
                })
                .verifyComplete();
    }

    private static UpdateTenantEndorsementConfigRequest request(boolean enabled,
                                                                BigDecimal amount,
                                                                String currency) {
        return new UpdateTenantEndorsementConfigRequest(enabled, amount, currency);
    }

    private static TenantEndorsementConfig config(boolean enabled, BigDecimal amount, String currency) {
        TenantEndorsementConfig c = new TenantEndorsementConfig();
        c.setEnabled(enabled);
        c.setFourEyesThresholdAmount(amount);
        c.setThresholdCurrency(currency);
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
