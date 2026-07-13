package com.medfund.claims.service;

import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.RejectionReasonFilterParams;
import com.medfund.claims.dto.UpsertRejectionReasonRequest;
import com.medfund.claims.entity.RejectionReason;
import com.medfund.claims.repository.RejectionReasonQueryRepository;
import com.medfund.claims.repository.RejectionReasonRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Manages the rejection-reason catalogue (R-codes referenced by the
 * adjudication pipeline). The platform seeds R01..R18 via V014; tenants
 * can extend or deactivate without touching the platform-wide set.
 */
@Service
public class RejectionReasonService {

    private static final Logger log = LoggerFactory.getLogger(RejectionReasonService.class);

    private final RejectionReasonRepository repo;
    private final RejectionReasonQueryRepository queryRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public RejectionReasonService(RejectionReasonRepository repo,
                                   RejectionReasonQueryRepository queryRepository,
                                   R2dbcEntityTemplate r2dbcTemplate,
                                   AuditPublisher auditPublisher) {
        this.repo = repo;
        this.queryRepository = queryRepository;
        this.r2dbcTemplate = r2dbcTemplate;
        this.auditPublisher = auditPublisher;
    }

    public Flux<RejectionReason> findAll() {
        return repo.findAll();
    }

    /**
     * Server-side paginated rejection-reasons list. Feeds
     * /tenant/claims/rejection-reasons. The catalogue is small (~20
     * platform-seeded codes plus tenant extensions) but the uniform
     * pattern makes it consistent with every other list surface.
     */
    public Mono<PageResponse<RejectionReason>> searchPaged(RejectionReasonFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Flux<RejectionReason> findAllActive() {
        return repo.findAllActive();
    }

    public Mono<RejectionReason> findByCode(String code) {
        return repo.findByCode(code)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Rejection reason not found: " + code)));
    }

    @Transactional
    public Mono<RejectionReason> create(UpsertRejectionReasonRequest request,
                                         String actorId, String actorEmail) {
        var reason = new RejectionReason();
        reason.setCode(request.code());
        reason.setDescription(request.description());
        reason.setCategory(request.category());
        reason.setIsActive(request.isActive() != null ? request.isActive() : true);
        return r2dbcTemplate.insert(reason)
                .flatMap(saved -> audit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("code", saved.getCode(), "category",
                                saved.getCategory() != null ? saved.getCategory() : "")));
    }

    @Transactional
    public Mono<RejectionReason> update(UUID id, UpsertRejectionReasonRequest request,
                                         String actorId, String actorEmail) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Rejection reason not found: " + id)))
                .flatMap(existing -> {
                    existing.setDescription(request.description());
                    if (request.category() != null) existing.setCategory(request.category());
                    if (request.isActive() != null) existing.setIsActive(request.isActive());
                    // The code is a stable identifier — once issued it doesn't change,
                    // so we don't honour code edits even if the request includes one.
                    return repo.save(existing)
                            .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                                    Map.of("code", saved.getCode()),
                                    Map.of("description", saved.getDescription(),
                                           "isActive", String.valueOf(saved.getIsActive()))));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Rejection reason not found: " + id)))
                .flatMap(existing -> repo.deleteById(id)
                        .then(audit(existing, "DELETE", actorId, actorEmail,
                                Map.of("code", existing.getCode()), null))
                        .then());
    }

    private Mono<RejectionReason> audit(RejectionReason r, String action,
                                          String actorId, String actorEmail,
                                          Map<String, Object> oldVal, Map<String, Object> newVal) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "RejectionReason", r.getId().toString(),
                    r.getCode(),
                    action, actorId, actorEmail,
                    oldVal, newVal,
                    new String[]{"code", "description", "isActive"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event).thenReturn(r);
        });
    }
}
