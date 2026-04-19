package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.CreateProviderRequest;
import com.medfund.user.dto.ProviderPage;
import com.medfund.user.dto.ProviderResponse;
import com.medfund.user.dto.UpdateProviderRequest;
import com.medfund.user.entity.Provider;
import com.medfund.user.exception.ProviderNotFoundException;
import com.medfund.user.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {

    private final ProviderRepository providerRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;
    private final KeycloakSyncService keycloakSyncService;

    public Flux<Provider> findAll() {
        return providerRepository.findAllOrderByCreatedAtDesc();
    }

    /**
     * Paginated, filtered provider search.
     * All filter params are optional — pass {@code null} to skip each one.
     */
    public Mono<ProviderPage> searchPage(String q, String status, String providerType, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = (size > 0 && size <= 100) ? size : 20;
        long offset  = (long) (safePage - 1) * safeSize;

        Flux<ProviderResponse> rows = providerRepository
                .searchPage(q, status, providerType, safeSize, offset)
                .map(ProviderResponse::from);

        Mono<Long> total = providerRepository.countSearch(q, status, providerType);

        return Mono.zip(rows.collectList(), total)
                   .map(t -> ProviderPage.of(t.getT1(), t.getT2(), safePage, safeSize));
    }

    public Mono<Provider> findById(UUID id) {
        return providerRepository.findById(id)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(id)));
    }

    public Flux<Provider> findByStatus(String status) {
        return providerRepository.findByStatus(status);
    }

    public Flux<Provider> findBySpecialty(String specialty) {
        return providerRepository.findBySpecialty(specialty);
    }

    public Flux<Provider> search(String query) {
        return providerRepository.search(query);
    }

    public Mono<Provider> findByRegistrationNumber(String registrationNumber) {
        return providerRepository.findByRegistrationNumber(registrationNumber);
    }

    @Transactional
    public Mono<Provider> onboard(CreateProviderRequest request, String actorId, String actorEmail) {
        var provider = new Provider();
        // id NOT set — PostgreSQL generates it via DEFAULT gen_random_uuid().
        // Setting @Id before save() causes R2DBC to issue UPDATE instead of INSERT.
        provider.setName(request.name());
        provider.setProviderType(request.providerType());
        provider.setRegistrationNumber(request.registrationNumber());
        provider.setSpecialty(request.specialty());
        provider.setEmail(request.email());
        provider.setPhone(request.phone());
        provider.setCity(request.city());
        provider.setAddress(request.address());
        provider.setBankingDetails(request.bankingDetails());
        provider.setStatus("pending_verification");
        // createdAt / updatedAt handled by @CreatedDate / @LastModifiedDate auditing
        if (actorId != null && !actorId.equals("system")) {
            provider.setCreatedBy(UUID.fromString(actorId));
            provider.setUpdatedBy(UUID.fromString(actorId));
        }

        return r2dbcTemplate.insert(provider)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                String realm = "tenant-" + tenantId;

                Mono<Void> keycloakSync = Mono.empty();
                if (saved.getEmail() != null && !saved.getEmail().isBlank()) {
                    keycloakSync = keycloakSyncService.createUser(
                        realm, saved.getEmail(), saved.getName(), "",
                        List.of("provider")
                    ).flatMap(keycloakUserId -> {
                        saved.setKeycloakUserId(keycloakUserId);
                        return providerRepository.save(saved).then();
                    }).onErrorResume(e -> {
                        log.warn("Keycloak sync failed for provider {}: {}", saved.getName(), e.getMessage());
                        return Mono.empty();
                    });
                }

                return keycloakSync
                    .then(publishAudit(tenantId, saved, null, actorId, actorEmail, "CREATE"))
                    .then(eventPublisher.publishProviderOnboarded(saved.getId().toString(), saved.getName()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Provider> verifyAhfoz(UUID id, String actorId, String actorEmail) {
        return providerRepository.findById(id)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyProvider(existing);
                existing.setStatus("active");
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));

                return providerRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Provider> update(UUID id, UpdateProviderRequest request, String actorId, String actorEmail) {
        return providerRepository.findById(id)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyProvider(existing);

                if (request.name() != null)               existing.setName(request.name());
                if (request.providerType() != null)       existing.setProviderType(request.providerType());
                if (request.registrationNumber() != null) existing.setRegistrationNumber(request.registrationNumber());
                if (request.specialty() != null)      existing.setSpecialty(request.specialty());
                if (request.email() != null)          existing.setEmail(request.email());
                if (request.phone() != null)          existing.setPhone(request.phone());
                if (request.city() != null)           existing.setCity(request.city());
                if (request.address() != null)        existing.setAddress(request.address());
                if (request.bankingDetails() != null) existing.setBankingDetails(request.bankingDetails());
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));

                return providerRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Provider> activate(UUID id, String actorId, String actorEmail) {
        return providerRepository.findById(id)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyProvider(existing);
                existing.setStatus("active");
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));
                return providerRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE")
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Provider> suspend(UUID id, String actorId, String actorEmail) {
        return providerRepository.findById(id)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(id)))
            .flatMap(existing -> {
                var previous = copyProvider(existing);
                existing.setStatus("suspended");
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(UUID.fromString(actorId));

                return providerRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        Mono<Void> keycloakDisable = Mono.empty();
                        if (saved.getKeycloakUserId() != null) {
                            keycloakDisable = keycloakSyncService.disableUser(
                                "tenant-" + tenantId, saved.getKeycloakUserId());
                        }
                        return keycloakDisable
                            .then(publishAudit(tenantId, saved, previous, actorId, actorEmail, "UPDATE"))
                            .thenReturn(saved);
                    }));
            });
    }

    private Mono<Void> publishAudit(String tenantId, Provider current, Provider previous,
                                     String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "platform",
            "PROVIDER",
            current.getId().toString(),
            current.getName(),                       // entityName — human-readable, never a UUID
            action,
            actorId,
            actorEmail,                              // actorEmail from JWT — enables readable actor display
            previous != null ? Map.of("name", previous.getName(), "status", previous.getStatus()) : null,
            Map.of("name", current.getName(), "status", current.getStatus(),
                "registrationNumber", current.getRegistrationNumber() != null ? current.getRegistrationNumber() : ""),
            new String[]{"name", "status", "registrationNumber", "bankingDetails"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private Provider copyProvider(Provider source) {
        var copy = new Provider();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setProviderType(source.getProviderType());
        copy.setRegistrationNumber(source.getRegistrationNumber());
        copy.setSpecialty(source.getSpecialty());
        copy.setEmail(source.getEmail());
        copy.setPhone(source.getPhone());
        copy.setCity(source.getCity());
        copy.setAddress(source.getAddress());
        copy.setBankingDetails(source.getBankingDetails());
        copy.setKeycloakUserId(source.getKeycloakUserId());
        copy.setStatus(source.getStatus());
        return copy;
    }
}
