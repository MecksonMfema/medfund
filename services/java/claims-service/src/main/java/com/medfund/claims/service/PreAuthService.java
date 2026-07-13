package com.medfund.claims.service;

import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.PreAuthRequest;
import com.medfund.claims.dto.PreAuthorizationFilterParams;
import com.medfund.claims.dto.PreAuthorizationRow;
import com.medfund.claims.entity.PreAuthorization;
import com.medfund.claims.exception.PreAuthNotFoundException;
import com.medfund.claims.repository.PreAuthorizationQueryRepository;
import com.medfund.claims.repository.PreAuthorizationRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PreAuthService {

    private static final Logger log = LoggerFactory.getLogger(PreAuthService.class);

    private final PreAuthorizationRepository preAuthorizationRepository;
    private final PreAuthorizationQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;
    private final ClaimEventPublisher eventPublisher;

    public PreAuthService(PreAuthorizationRepository preAuthorizationRepository,
                          PreAuthorizationQueryRepository queryRepository,
                          AuditPublisher auditPublisher,
                          ClaimEventPublisher eventPublisher) {
        this.preAuthorizationRepository = preAuthorizationRepository;
        this.queryRepository = queryRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Server-side paginated pre-authorizations list. Feeds
     * /tenant/claims/preauth. Member + provider names joined into every
     * row so the client renders inline without a lookup.
     */
    public Mono<PageResponse<PreAuthorizationRow>> searchPaged(PreAuthorizationFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Flux<PreAuthorization> findByMemberId(UUID memberId) {
        return preAuthorizationRepository.findByMemberId(memberId);
    }

    public Mono<PreAuthorization> findById(UUID id) {
        return preAuthorizationRepository.findById(id)
            .switchIfEmpty(Mono.error(new PreAuthNotFoundException(id)));
    }

    public Mono<PreAuthorization> findByAuthNumber(String authNumber) {
        return preAuthorizationRepository.findByAuthNumber(authNumber);
    }

    public Flux<PreAuthorization> findByStatus(String status) {
        return preAuthorizationRepository.findByStatus(status);
    }

    @Transactional
    public Mono<PreAuthorization> request(PreAuthRequest request, String actorId, String actorEmail) {
        return generateAuthNumber()
            .flatMap(authNumber -> {
                var preAuth = new PreAuthorization();
                preAuth.setAuthNumber(authNumber);
                preAuth.setMemberId(request.memberId());
                preAuth.setDependantId(request.dependantId());
                preAuth.setProviderId(request.providerId());
                preAuth.setSchemeId(request.schemeId());
                preAuth.setTariffCode(request.tariffCode());
                preAuth.setDiagnosisCode(request.diagnosisCode());
                preAuth.setStatus("PENDING");
                preAuth.setRequestedAmount(request.requestedAmount());
                preAuth.setCurrencyCode(request.currencyCode());
                preAuth.setRequestedDate(LocalDate.now());
                preAuth.setNotes(request.notes());
                preAuth.setCreatedAt(Instant.now());
                preAuth.setUpdatedAt(Instant.now());
                preAuth.setCreatedBy(UUID.fromString(actorId));

                return preAuthorizationRepository.save(preAuth);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "PreAuthorization", saved.getId().toString(),
                        saved.getAuthNumber(), "CREATE",
                        actorId, actorEmail,
                        null,
                        Map.of("authNumber", saved.getAuthNumber(), "status", saved.getStatus(),
                               "requestedAmount", saved.getRequestedAmount().toString()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<PreAuthorization> approve(UUID id, BigDecimal approvedAmount, LocalDate expiryDate,
                                           String actorId, String actorEmail) {
        return preAuthorizationRepository.findById(id)
            .switchIfEmpty(Mono.error(new PreAuthNotFoundException(id)))
            .flatMap(preAuth -> {
                String previousStatus = preAuth.getStatus();
                preAuth.setStatus("APPROVED");
                preAuth.setApprovedAmount(approvedAmount);
                preAuth.setDecisionDate(LocalDate.now());
                // Snap expiry to end-of-month (feedback_effective_date_snap)
                // — the auth stays valid through the whole cycle it expires in.
                preAuth.setExpiryDate(com.medfund.shared.validation.DateSnaps.toEndOfMonth(expiryDate));
                preAuth.setDecisionBy(UUID.fromString(actorId));
                preAuth.setUpdatedAt(Instant.now());

                return preAuthorizationRepository.save(preAuth)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "PreAuthorization", saved.getId().toString(),
                                saved.getAuthNumber(), "UPDATE",
                                actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(), "approvedAmount", approvedAmount.toString()))
                            .then(eventPublisher.publishPreAuthDecision(
                                saved.getId().toString(),
                                saved.getAuthNumber(),
                                "APPROVED"))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<PreAuthorization> reject(UUID id, String reason, String actorId, String actorEmail) {
        return preAuthorizationRepository.findById(id)
            .switchIfEmpty(Mono.error(new PreAuthNotFoundException(id)))
            .flatMap(preAuth -> {
                String previousStatus = preAuth.getStatus();
                preAuth.setStatus("REJECTED");
                preAuth.setRejectionReason(reason);
                preAuth.setDecisionDate(LocalDate.now());
                preAuth.setDecisionBy(UUID.fromString(actorId));
                preAuth.setUpdatedAt(Instant.now());

                return preAuthorizationRepository.save(preAuth)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "PreAuthorization", saved.getId().toString(),
                                saved.getAuthNumber(), "UPDATE",
                                actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(), "rejectionReason", reason))
                            .then(eventPublisher.publishPreAuthDecision(
                                saved.getId().toString(),
                                saved.getAuthNumber(),
                                "REJECTED"))
                            .thenReturn(saved);
                    }));
            });
    }

    /**
     * Checks whether a member has a valid (APPROVED, non-expired) pre-authorization
     * for the given tariff code.
     */
    public Mono<Boolean> hasValidPreAuth(UUID memberId, String tariffCode) {
        return preAuthorizationRepository.findByMemberIdAndTariffCodeAndStatus(memberId, tariffCode, "APPROVED")
            .map(preAuth -> preAuth.getExpiryDate() != null && !preAuth.getExpiryDate().isBefore(LocalDate.now()))
            .defaultIfEmpty(false);
    }

    // ---- Private helpers ----

    private Mono<String> generateAuthNumber() {
        String number = "PA-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return preAuthorizationRepository.existsByAuthNumber(number)
            .flatMap(exists -> exists ? generateAuthNumber() : Mono.just(number));
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId, String entityName,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            entityName,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{"status", "approvedAmount", "rejectionReason"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    public Mono<Void> expireApprovedPastDate() {
        return preAuthorizationRepository.findByStatus("APPROVED")
            .filter(pa -> pa.getExpiryDate() != null && pa.getExpiryDate().isBefore(java.time.LocalDate.now()))
            .flatMap(pa -> {
                pa.setStatus("EXPIRED");
                pa.setUpdatedAt(java.time.Instant.now());
                return preAuthorizationRepository.save(pa);
            })
            .then();
    }
}
