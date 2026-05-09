package com.medfund.user.service;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.dto.CreateProviderRequest;
import com.medfund.user.dto.UpdateProviderRequest;
import com.medfund.user.entity.Provider;
import com.medfund.user.exception.ProviderNotFoundException;
import com.medfund.user.repository.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class ProviderServiceTest {

    @Mock
    private ProviderRepository providerRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private UserEventPublisher eventPublisher;

    @Mock
    private KeycloakSyncService keycloakSyncService;

    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;

    @InjectMocks
    private ProviderService providerService;

    @BeforeEach
    void stubInsert() {
        lenient().when(r2dbcTemplate.insert(any(Provider.class))).thenAnswer(inv -> {
            Provider p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return Mono.just(p);
        });
    }

    @Test
    void findAll_returnsProviders() {
        var provider1 = createTestProvider();
        var provider2 = createTestProvider();
        provider2.setName("Metro Clinic");

        when(providerRepository.findAllOrderByCreatedAtDesc()).thenReturn(Flux.just(provider1, provider2));

        StepVerifier.create(providerService.findAll())
            .expectNext(provider1)
            .expectNext(provider2)
            .verifyComplete();

        verify(providerRepository).findAllOrderByCreatedAtDesc();
    }

    @Test
    void findById_existing_returnsProvider() {
        var provider = createTestProvider();
        var id = provider.getId();

        when(providerRepository.findById(id)).thenReturn(Mono.just(provider));

        StepVerifier.create(providerService.findById(id))
            .assertNext(result -> {
                assertThat(result.getId()).isEqualTo(id);
                assertThat(result.getName()).isEqualTo("City Hospital");
            })
            .verifyComplete();
    }

    @Test
    void findById_nonExisting_throwsNotFound() {
        var id = UUID.randomUUID();

        when(providerRepository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(providerService.findById(id))
            .expectError(ProviderNotFoundException.class)
            .verify();
    }

    @Test
    void onboard_validRequest_createsProviderPendingVerification() {
        var actorId = UUID.randomUUID().toString();
        var actorEmail = "ops@medfund.test";
        var request = new CreateProviderRequest(
            "City Hospital", null, "AH-001", "General Practice",
            "info@cityhospital.com", null, null, null, null
        );

        when(providerRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(eventPublisher.publishProviderOnboarded(any(), any())).thenReturn(Mono.empty());
        when(keycloakSyncService.createUser(any(), any(), any(), any(), any())).thenReturn(Mono.just("kc-user-id"));

        StepVerifier.create(
            providerService.onboard(request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(provider -> {
                assertThat(provider.getStatus()).isEqualTo("pending_verification");
                assertThat(provider.getName()).isEqualTo("City Hospital");
                assertThat(provider.getRegistrationNumber()).isEqualTo("AH-001");
            })
            .verifyComplete();

        verify(providerRepository, atLeast(1)).save(any());
        verify(auditPublisher).publish(any());
        verify(eventPublisher).publishProviderOnboarded(any(), any());
    }

    @Test
    void verifyAhfoz_existingProvider_setsStatusActive() {
        var provider = createTestProvider();
        provider.setStatus("pending_verification");
        var id = provider.getId();
        var actorId = UUID.randomUUID().toString();
        var actorEmail = "ops@medfund.test";

        when(providerRepository.findById(id)).thenReturn(Mono.just(provider));
        when(providerRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            providerService.verifyAhfoz(id, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> assertThat(result.getStatus()).isEqualTo("active"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    @Test
    void update_existingProvider_updatesFields() {
        var provider = createTestProvider();
        var id = provider.getId();
        var actorId = UUID.randomUUID().toString();
        var actorEmail = "ops@medfund.test";
        var request = new UpdateProviderRequest(
            "Updated Hospital", null, null, "Cardiology",
            null, null, null, null, null
        );

        when(providerRepository.findById(id)).thenReturn(Mono.just(provider));
        when(providerRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            providerService.update(id, request, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> {
                assertThat(result.getName()).isEqualTo("Updated Hospital");
                assertThat(result.getSpecialty()).isEqualTo("Cardiology");
                assertThat(result.getRegistrationNumber()).isEqualTo("AH-001");
            })
            .verifyComplete();
    }

    @Test
    void suspend_existingProvider_setsStatusSuspended() {
        var provider = createTestProvider();
        var id = provider.getId();
        var actorId = UUID.randomUUID().toString();
        var actorEmail = "ops@medfund.test";

        when(providerRepository.findById(id)).thenReturn(Mono.just(provider));
        when(providerRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
            providerService.suspend(id, actorId, actorEmail)
                .contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))
        )
            .assertNext(result -> assertThat(result.getStatus()).isEqualTo("suspended"))
            .verifyComplete();

        verify(auditPublisher).publish(any());
    }

    private Provider createTestProvider() {
        var provider = new Provider();
        provider.setId(UUID.randomUUID());
        provider.setName("City Hospital");
        provider.setRegistrationNumber("AH-001");
        provider.setSpecialty("General Practice");
        provider.setEmail("info@cityhospital.com");
        provider.setStatus("active");
        provider.setCreatedAt(Instant.now());
        provider.setUpdatedAt(Instant.now());
        provider.setCreatedBy(UUID.randomUUID());
        provider.setUpdatedBy(UUID.randomUUID());
        return provider;
    }
}
