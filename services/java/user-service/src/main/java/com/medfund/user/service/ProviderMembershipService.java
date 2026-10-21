package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.insurance.InsuranceLine;
import com.medfund.user.entity.Provider;
import com.medfund.user.entity.ProviderInsuranceLine;
import com.medfund.user.entity.ProviderTenant;
import com.medfund.user.exception.ProviderNotFoundException;
import com.medfund.user.exception.TenantNotFoundException;
import com.medfund.user.repository.PlatformTenantRepository;
import com.medfund.user.repository.ProviderInsuranceLineRepository;
import com.medfund.user.repository.ProviderRepository;
import com.medfund.user.repository.ProviderTenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Operator-driven management of the two platform junctions behind
 * {@code public.providers}: which tenants a provider is contracted with
 * ({@code public.provider_tenants}) and which insurance lines it is tagged to
 * serve ({@code public.provider_insurance_lines}).
 *
 * <p>Both junctions are read on the claim path: claims-service rejects a claim
 * whose provider has no active membership for the submitting tenant, or whose
 * scheme line is missing from the provider's tags. That makes these mutations
 * load-bearing rather than cosmetic, so every one of them emits an audit event
 * and the link/unlink pair also emits a business event for future
 * cache-invalidation subscribers.
 *
 * <p>Audit events are tagged with the {@link #PLATFORM_TENANT} sentinel for the
 * same reason {@code ProviderService} uses it: providers are a platform-wide
 * registry and these rows live in {@code public}, so there is no single owning
 * tenant schema.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderMembershipService {

    private static final String PLATFORM_TENANT = "platform";

    private final ProviderRepository providerRepository;
    private final ProviderTenantRepository membershipRepository;
    private final ProviderInsuranceLineRepository lineRepository;
    private final PlatformTenantRepository tenantRepository;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;

    // ── Tenant membership ────────────────────────────────────────────

    public Flux<ProviderTenant> listMemberships(UUID providerId) {
        return membershipRepository.findByProviderId(providerId);
    }

    /**
     * Contract a provider with a tenant. Defaults come from the V185 column
     * defaults (active / STANDARD / in-network, effective today); the contract
     * fields stay null until a {@code providers:manage_contracts} editor ships.
     *
     * @throws ProviderNotFoundException when the provider id is unknown (404)
     * @throws TenantNotFoundException   when the tenant id is unknown (404)
     * @throws IllegalStateException     when the membership already exists (409)
     */
    @Transactional
    public Mono<ProviderTenant> link(UUID providerId, UUID tenantId, String actorId, String actorEmail) {
        return resolve(providerId, tenantId)
            .flatMap(ctx -> {
                var pt = new ProviderTenant();
                pt.setProviderId(providerId);
                pt.setTenantId(tenantId);
                pt.setCreatedBy(actorUuid(actorId));
                return membershipRepository.insert(pt)
                    .onErrorMap(DuplicateKeyException.class, e -> new IllegalStateException(
                        "Provider " + ctx.providerName() + " is already linked to " + ctx.tenantName()))
                    .flatMap(saved -> publishLinkAudit(ctx, actorId, actorEmail, "LINK")
                        .then(eventPublisher.publishProviderTenantLinked(
                                providerId.toString(), tenantId.toString()))
                        .thenReturn(saved));
            });
    }

    /**
     * Drop a provider's contract with a tenant. Unlinking is idempotent: a
     * membership that is already gone completes empty without emitting audit
     * or business events, so a retried DELETE does not double-log.
     */
    @Transactional
    public Mono<Void> unlink(UUID providerId, UUID tenantId, String actorId, String actorEmail) {
        return resolve(providerId, tenantId)
            .flatMap(ctx -> membershipRepository.delete(providerId, tenantId)
                .flatMap(rows -> {
                    if (rows == 0) {
                        log.debug("Unlink no-op: provider {} was not linked to tenant {}", providerId, tenantId);
                        return Mono.empty();
                    }
                    return publishLinkAudit(ctx, actorId, actorEmail, "UNLINK")
                        .then(eventPublisher.publishProviderTenantUnlinked(
                                providerId.toString(), tenantId.toString()));
                }));
    }

    // ── Insurance-line tags ──────────────────────────────────────────

    public Flux<ProviderInsuranceLine> listLines(UUID providerId) {
        return lineRepository.findByProviderId(providerId);
    }

    /**
     * Tag a provider with an insurance line. The code is normalised through
     * {@link InsuranceLine} first, so the UI alias {@code MOTOR} stores as
     * {@code VEHICLE} and an unknown code is rejected here rather than by the
     * schema CHECK (which would surface as an opaque 500).
     */
    @Transactional
    public Mono<ProviderInsuranceLine> addLine(UUID providerId, String line,
                                               String actorId, String actorEmail) {
        InsuranceLine resolved = InsuranceLine.from(line);
        if (resolved == null) {
            return Mono.error(new IllegalArgumentException("Unknown insurance line: " + line));
        }
        String code = resolved.code();
        return requireProvider(providerId)
            .flatMap(provider -> lineRepository.insert(providerId, code)
                .onErrorMap(DuplicateKeyException.class, e -> new IllegalStateException(
                    "Provider " + provider.getName() + " is already tagged for " + code))
                .flatMap(saved -> publishLineAudit(provider.getName(), providerId, code,
                                                    actorId, actorEmail, "ADD")
                    .thenReturn(saved)));
    }

    /** Idempotent, for the same reason {@link #unlink} is. */
    @Transactional
    public Mono<Void> removeLine(UUID providerId, String line, String actorId, String actorEmail) {
        InsuranceLine resolved = InsuranceLine.from(line);
        if (resolved == null) {
            return Mono.error(new IllegalArgumentException("Unknown insurance line: " + line));
        }
        String code = resolved.code();
        return requireProvider(providerId)
            .flatMap(provider -> lineRepository.delete(providerId, code)
                .flatMap(rows -> rows == 0
                    ? Mono.<Void>empty()
                    : publishLineAudit(provider.getName(), providerId, code,
                                        actorId, actorEmail, "REMOVE")));
    }

    // ── Lookups ──────────────────────────────────────────────────────

    private Mono<Provider> requireProvider(UUID providerId) {
        return providerRepository.findById(providerId)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(providerId)));
    }

    /**
     * Both halves of the pair resolved up front. The tenant lookup is what
     * turns an unknown tenant UUID into a 404 instead of the FK violation the
     * INSERT would otherwise raise, and both names feed the audit envelope so
     * {@code entityName} reads as text rather than a pair of UUIDs.
     */
    private Mono<LinkContext> resolve(UUID providerId, UUID tenantId) {
        return requireProvider(providerId)
            .flatMap(provider -> tenantRepository.findNameById(tenantId)
                .switchIfEmpty(Mono.error(new TenantNotFoundException(tenantId)))
                .map(tenantName -> new LinkContext(providerId, provider.getName(), tenantId, tenantName)));
    }

    private static UUID actorUuid(String actorId) {
        if (actorId == null || actorId.equals("system")) return null;
        try {
            return UUID.fromString(actorId);
        } catch (IllegalArgumentException e) {
            // Non-UUID subject (service account, test token): the column is
            // nullable and actorId still rides the audit event verbatim.
            return null;
        }
    }

    // ── Audit helpers ────────────────────────────────────────────────

    private Mono<Void> publishLinkAudit(LinkContext ctx, String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
            PLATFORM_TENANT,
            "PROVIDER_TENANT",
            ctx.providerId() + "::" + ctx.tenantId(),
            ctx.providerName() + " @ " + ctx.tenantName(),   // entityName: friendly text, never a UUID
            action,
            actorId,
            actorEmail,
            null,
            Map.of("providerId", ctx.providerId().toString(),
                   "providerName", ctx.providerName(),
                   "tenantId", ctx.tenantId().toString(),
                   "tenantName", ctx.tenantName()),
            new String[]{"providerId", "tenantId"},
            UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }

    private Mono<Void> publishLineAudit(String providerName, UUID providerId, String line,
                                        String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
            PLATFORM_TENANT,
            "PROVIDER_INSURANCE_LINE",
            providerId + "::" + line,
            providerName + " (" + line + ")",                // entityName: friendly text, never a UUID
            action,
            actorId,
            actorEmail,
            null,
            Map.of("providerId", providerId.toString(),
                   "providerName", providerName,
                   "insuranceLine", line),
            new String[]{"providerId", "insuranceLine"},
            UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }

    /** Resolved (provider, tenant) pair plus both display names. */
    private record LinkContext(UUID providerId, String providerName, UUID tenantId, String tenantName) {}
}
