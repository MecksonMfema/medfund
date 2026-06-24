package com.medfund.contributions.service;

import com.medfund.contributions.entity.BadDebt;
import com.medfund.contributions.repository.BadDebtRepository;
import com.medfund.contributions.repository.ContributionRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class BadDebtService {

    private static final Logger log = LoggerFactory.getLogger(BadDebtService.class);

    private final BadDebtRepository badDebtRepository;
    private final ContributionRepository contributionRepository;
    private final AuditPublisher auditPublisher;

    public BadDebtService(BadDebtRepository badDebtRepository,
                          ContributionRepository contributionRepository,
                          AuditPublisher auditPublisher) {
        this.badDebtRepository = badDebtRepository;
        this.contributionRepository = contributionRepository;
        this.auditPublisher = auditPublisher;
    }

    public Flux<BadDebt> findAll() {
        return badDebtRepository.findAllOrderByCreatedAtDesc();
    }

    public Flux<BadDebt> findByMemberId(UUID memberId) {
        return badDebtRepository.findByMemberId(memberId);
    }

    public Flux<BadDebt> findByStatus(String status) {
        return badDebtRepository.findByStatus(status);
    }

    @Transactional
    public Mono<BadDebt> flagAsOverdue(UUID contributionId, String reason, String actorId, String actorEmail) {
        return contributionRepository.findById(contributionId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Contribution not found: " + contributionId)))
            .flatMap(contribution -> {
                var badDebt = new BadDebt();
                badDebt.setContributionId(contributionId);
                badDebt.setMemberId(contribution.getMemberId());
                badDebt.setGroupId(contribution.getGroupId());
                badDebt.setAmount(contribution.getAmount());
                badDebt.setCurrencyCode(contribution.getCurrencyCode());
                badDebt.setStatus("FLAGGED");
                badDebt.setReason(reason);
                badDebt.setFlaggedDate(LocalDate.now());
                badDebt.setCreatedAt(Instant.now());
                badDebt.setUpdatedAt(Instant.now());

                return badDebtRepository.save(badDebt);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "BadDebt", saved.getId().toString(), badDebtName(saved),
                        "CREATE", actorId, actorEmail,
                        null,
                        Map.of("contributionId", saved.getContributionId().toString(),
                               "amount", saved.getAmount().toPlainString(),
                               "currencyCode", saved.getCurrencyCode(),
                               "status", saved.getStatus()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<BadDebt> writeOff(UUID id, String actorId, String actorEmail) {
        return badDebtRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bad debt not found: " + id)))
            .flatMap(bd -> {
                if (!"FLAGGED".equals(bd.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Bad debt " + id + " is not FLAGGED, current status: " + bd.getStatus()));
                }

                String previousStatus = bd.getStatus();
                bd.setStatus("WRITTEN_OFF");
                bd.setWrittenOffBy(UUID.fromString(actorId));
                bd.setWrittenOffDate(LocalDate.now());
                bd.setUpdatedAt(Instant.now());

                return badDebtRepository.save(bd)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "BadDebt", saved.getId().toString(), badDebtName(saved),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus(),
                                       "writtenOffBy", actorId,
                                       "writtenOffDate", saved.getWrittenOffDate().toString()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<BadDebt> markRecovered(UUID id, String actorId, String actorEmail) {
        return badDebtRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bad debt not found: " + id)))
            .flatMap(bd -> {
                String previousStatus = bd.getStatus();
                bd.setStatus("RECOVERED");
                bd.setUpdatedAt(Instant.now());

                return badDebtRepository.save(bd)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "BadDebt", saved.getId().toString(), badDebtName(saved),
                                "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

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
            new String[]{"status"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    /**
     * Bad-debt rows have no number/code — synthesize a label from the linked
     * contribution and amount so the audit trail shows something more useful
     * than the row's UUID.
     */
    private static String badDebtName(BadDebt bd) {
        String contrib = bd.getContributionId() != null ? bd.getContributionId().toString() : "?";
        String amount = bd.getAmount() != null ? bd.getAmount().toPlainString() : "0";
        String currency = bd.getCurrencyCode() != null ? bd.getCurrencyCode() : "";
        return "contribution " + contrib + " " + amount + " " + currency;
    }
}
