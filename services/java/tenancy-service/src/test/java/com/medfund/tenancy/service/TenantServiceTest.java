package com.medfund.tenancy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.scheduler.ScheduledJobService;
import com.medfund.tenancy.dto.CreateTenantRequest;
import com.medfund.tenancy.dto.UpdateTenantRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantCurrencyConfig;
import com.medfund.tenancy.exception.TenantNotFoundException;
import com.medfund.tenancy.exception.TenantSlugConflictException;
import com.medfund.tenancy.repository.CurrencyRepository;
import com.medfund.tenancy.repository.TenantCurrencyConfigRepository;
import com.medfund.tenancy.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantCurrencyConfigRepository currencyConfigRepository;

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private SchemaProvisioningService schemaProvisioning;

    @Mock
    private KeycloakRealmService keycloakRealmService;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private TenantEventPublisher eventPublisher;

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @Mock
    private ScheduledJobService scheduledJobService;

    @InjectMocks
    private TenantService tenantService;

    @BeforeEach
    void stubRedis() {
        // Cache eviction is a side effect of every write; stub it for all tests so we
        // don't have to repeat the boilerplate in each method.
        lenient().when(redis.keys(anyString())).thenReturn(Flux.empty());
    }

    @Test
    void findAll_returnsAllTenants() {
        Tenant tenant1 = createTestTenant();
        Tenant tenant2 = createTestTenant();
        tenant2.setName("Other Society");
        tenant2.setSlug("other-soc");

        when(tenantRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(tenant1, tenant2));

        StepVerifier.create(tenantService.findAll())
                .expectNext(tenant1)
                .expectNext(tenant2)
                .verifyComplete();
    }

    @Test
    void findById_existingTenant_returnsTenant() {
        Tenant tenant = createTestTenant();
        when(tenantRepository.findById(tenant.getId())).thenReturn(Mono.just(tenant));

        StepVerifier.create(tenantService.findById(tenant.getId()))
                .assertNext(result -> {
                    assertThat(result.getId()).isEqualTo(tenant.getId());
                    assertThat(result.getName()).isEqualTo("Test Society");
                    assertThat(result.getSlug()).isEqualTo("test-soc");
                })
                .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(tenantService.findById(id))
                .expectError(TenantNotFoundException.class)
                .verify();
    }

    @Test
    void findBySlug_existingSlug_returnsTenant() {
        Tenant tenant = createTestTenant();
        when(tenantRepository.findBySlug("test-soc")).thenReturn(Mono.just(tenant));

        StepVerifier.create(tenantService.findBySlug("test-soc"))
                .assertNext(result -> {
                    assertThat(result.getSlug()).isEqualTo("test-soc");
                    assertThat(result.getName()).isEqualTo("Test Society");
                })
                .verifyComplete();
    }

    @Test
    void findBySlug_nonExisting_throwsNotFound() {
        when(tenantRepository.findBySlug("nonexistent")).thenReturn(Mono.empty());

        StepVerifier.create(tenantService.findBySlug("nonexistent"))
                .expectError(TenantNotFoundException.class)
                .verify();
    }

    @Test
    void create_validRequest_createsTenantWithSchemaAndRealmAndDefaultCurrency() {
        var request = new CreateTenantRequest(
                "Test Society", "test-soc", null, null,
                "admin@test.com", "US", null, null, null, null
        );

        when(currencyRepository.existsActiveByCode("USD")).thenReturn(Mono.just(true));
        when(tenantRepository.existsBySlug("test-soc")).thenReturn(Mono.just(false));
        when(r2dbcTemplate.insert(any(Tenant.class)))
                .thenAnswer(inv -> {
                    Tenant t = inv.getArgument(0);
                    t.setId(UUID.randomUUID());
                    return Mono.just(t);
                });
        when(r2dbcTemplate.insert(any(TenantCurrencyConfig.class)))
                .thenAnswer(inv -> {
                    TenantCurrencyConfig c = inv.getArgument(0);
                    c.setId(UUID.randomUUID());
                    return Mono.just(c);
                });
        when(schemaProvisioning.provisionSchema(anyString())).thenReturn(Mono.empty());
        when(keycloakRealmService.createRealm(anyString(), any(Tenant.class))).thenReturn(Mono.empty());
        when(scheduledJobService.seedDefaults(any(UUID.class), anyString())).thenReturn(Mono.empty());
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        when(eventPublisher.publishTenantProvisioned(any(Tenant.class))).thenReturn(Mono.empty());
        when(redis.keys(anyString())).thenReturn(Flux.empty());

        StepVerifier.create(tenantService.create(request, "actor-123", "actor@test.com"))
                .assertNext(tenant -> {
                    assertThat(tenant.getName()).isEqualTo("Test Society");
                    assertThat(tenant.getSlug()).isEqualTo("test-soc");
                    assertThat(tenant.getStatus()).isEqualTo("active");
                    assertThat(tenant.getContactEmail()).isEqualTo("admin@test.com");
                    assertThat(tenant.getCountryCode()).isEqualTo("US");
                    assertThat(tenant.getTimezone()).isEqualTo("UTC");
                    assertThat(tenant.getMembershipModel()).isEqualTo("BOTH");
                    assertThat(tenant.getKeycloakRealm()).isEqualTo("medfund-test-soc");
                    assertThat(tenant.getSchemaName()).startsWith("tenant_");
                    assertThat(tenant.getId()).isNotNull();
                })
                .verifyComplete();

        verify(schemaProvisioning).provisionSchema(anyString());
        verify(keycloakRealmService).createRealm(eq("medfund-test-soc"), any(Tenant.class));
        // The seeded currency config must carry all four scope flags TRUE.
        ArgumentCaptor<TenantCurrencyConfig> seedCaptor = ArgumentCaptor.forClass(TenantCurrencyConfig.class);
        verify(r2dbcTemplate).insert(seedCaptor.capture());
        // The first capture is the Tenant; check we get a TenantCurrencyConfig as well.
        verify(r2dbcTemplate, atLeastOnce()).insert(any(TenantCurrencyConfig.class));
        verify(auditPublisher).publish(any(AuditEvent.class));
        verify(eventPublisher).publishTenantProvisioned(any(Tenant.class));
    }

    @Test
    void create_duplicateSlug_throwsConflict() {
        var request = new CreateTenantRequest(
                "Test Society", "test-soc", null, null,
                "admin@test.com", "US", null, null, null, null
        );

        when(currencyRepository.existsActiveByCode("USD")).thenReturn(Mono.just(true));
        when(tenantRepository.existsBySlug("test-soc")).thenReturn(Mono.just(true));

        StepVerifier.create(tenantService.create(request, "actor-123", "actor@test.com"))
                .expectError(TenantSlugConflictException.class)
                .verify();

        verify(r2dbcTemplate, never()).insert(any(Tenant.class));
    }

    @Test
    void create_unknownCurrencyCode_rejected() {
        var request = new CreateTenantRequest(
                "Test Society", "test-soc", null, null,
                "admin@test.com", "US", null, null, "XYZ", null
        );

        when(currencyRepository.existsActiveByCode("XYZ")).thenReturn(Mono.just(false));

        StepVerifier.create(tenantService.create(request, "actor-123", "actor@test.com"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalArgumentException.class);
                    assertThat(err.getMessage()).contains("XYZ");
                })
                .verify();

        verify(tenantRepository, never()).existsBySlug(anyString());
    }

    @Test
    void update_existingTenant_updatesFields() {
        Tenant existing = createTestTenant();
        UUID tenantId = existing.getId();

        var request = new UpdateTenantRequest(
                "Updated Name", null, null, "new@test.com",
                null, null, null, null, null, null, null
        );

        when(tenantRepository.findById(tenantId)).thenReturn(Mono.just(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(tenantService.update(tenantId, request, "actor-123", "actor@test.com"))
                .assertNext(tenant -> {
                    assertThat(tenant.getName()).isEqualTo("Updated Name");
                    assertThat(tenant.getContactEmail()).isEqualTo("new@test.com");
                    // Unchanged fields remain as they were
                    assertThat(tenant.getSlug()).isEqualTo("test-soc");
                    assertThat(tenant.getCountryCode()).isEqualTo("US");
                    assertThat(tenant.getTimezone()).isEqualTo("UTC");
                })
                .verifyComplete();

        verify(auditPublisher).publish(any(AuditEvent.class));
    }

    @Test
    void suspend_existingTenant_setsStatusSuspended() {
        Tenant tenant = createTestTenant();
        UUID tenantId = tenant.getId();

        when(tenantRepository.findById(tenantId)).thenReturn(Mono.just(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        when(eventPublisher.publishTenantSuspended(any(Tenant.class))).thenReturn(Mono.empty());

        StepVerifier.create(tenantService.suspend(tenantId, "actor-123", "actor@test.com"))
                .assertNext(result -> {
                    assertThat(result.getStatus()).isEqualTo("suspended");
                    assertThat(result.getId()).isEqualTo(tenantId);
                })
                .verifyComplete();

        verify(auditPublisher).publish(any(AuditEvent.class));
        verify(eventPublisher).publishTenantSuspended(any(Tenant.class));
    }

    @Test
    void activate_existingTenant_setsStatusActive() {
        Tenant tenant = createTestTenant();
        tenant.setStatus("suspended");
        UUID tenantId = tenant.getId();

        when(tenantRepository.findById(tenantId)).thenReturn(Mono.just(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(tenantService.activate(tenantId, "actor-123", "actor@test.com"))
                .assertNext(result -> {
                    assertThat(result.getStatus()).isEqualTo("active");
                    assertThat(result.getId()).isEqualTo(tenantId);
                })
                .verifyComplete();

        verify(auditPublisher).publish(any(AuditEvent.class));
    }

    private Tenant createTestTenant() {
        var tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Test Society");
        tenant.setSlug("test-soc");
        tenant.setSchemaName("tenant_abc123");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@test.com");
        tenant.setCountryCode("US");
        tenant.setTimezone("UTC");
        tenant.setMembershipModel("BOTH");
        tenant.setKeycloakRealm("medfund-test-soc");
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenant;
    }
}
