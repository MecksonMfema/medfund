package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.client.TenantConfigClient;
import com.medfund.finance.dto.AdvancePaymentDtos.CreateAdvancePaymentRequest;
import com.medfund.finance.dto.AdvancePaymentDtos.ReverseAdvancePaymentRequest;
import com.medfund.finance.dto.AdvancePaymentFilterParams;
import com.medfund.finance.dto.AdvancePaymentRow;
import com.medfund.finance.dto.PageResponse;
import com.medfund.finance.entity.AdvancePayment;
import com.medfund.finance.entity.AdvancePaymentApplication;
import com.medfund.finance.repository.AdvancePaymentApplicationRepository;
import com.medfund.finance.repository.AdvancePaymentQueryRepository;
import com.medfund.finance.repository.AdvancePaymentRepository;
import com.medfund.finance.util.Actors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvancePaymentService {

    private final AdvancePaymentRepository repository;
    private final AdvancePaymentApplicationRepository applicationRepository;
    private final AdvancePaymentQueryRepository queryRepository;
    private final AuditPublisher auditPublisher;
    private final FinanceEventPublisher eventPublisher;
    private final TenantConfigClient tenantConfigClient;
    private final FxConverter fxConverter;
    private final ProviderBalanceService providerBalanceService;

    public Flux<AdvancePayment> findAll() {
        return repository.findAllOrdered();
    }

    /**
     * Server-side paginated advance-payments list. Provider + member
     * names joined so the operational table renders inline.
     */
    public Mono<PageResponse<AdvancePaymentRow>> searchPaged(AdvancePaymentFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return queryRepository.search(params, size, offset)
                .collectList()
                .zipWith(queryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    public Flux<AdvancePayment> findByProvider(UUID providerId) {
        return repository.findByProviderId(providerId);
    }

    public Flux<AdvancePayment> findByMember(UUID memberId) {
        return repository.findByMemberId(memberId);
    }

    public Mono<AdvancePayment> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Advance payment not found: " + id)));
    }

    public Flux<AdvancePaymentApplication> listApplications(UUID advanceId) {
        return applicationRepository.findByAdvancePaymentId(advanceId);
    }

    @Transactional
    public Mono<AdvancePayment> create(CreateAdvancePaymentRequest request, String actor, String actorEmail) {
        if (request.providerId() == null && request.memberId() == null) {
            return Mono.error(new IllegalArgumentException("Either providerId or memberId is required"));
        }
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenant(TenantContext.get(ctx));
            return tenantConfigClient.getAdvancePaymentThreshold(tenantId)
                .flatMap(threshold -> convertToThresholdCurrency(request.amount(),
                        request.currencyCode(), threshold, tenantId)
                    .map(convertedAmount -> {
                        boolean autoApprove = convertedAmount.compareTo(threshold.amount()) <= 0;
                        var entity = new AdvancePayment();
                        entity.setProviderId(request.providerId());
                        entity.setMemberId(request.memberId());
                        entity.setAmount(request.amount());
                        entity.setCurrencyCode(request.currencyCode());
                        entity.setPaymentMethod(request.paymentMethod());
                        entity.setReference(request.reference());
                        entity.setComment(request.comment());
                        entity.setRecordedAt(Instant.now());
                        entity.setRecordedBy(Actors.parseId(actor));
                        entity.setType("ADVANCE");
                        entity.setStatus(autoApprove ? "approved" : "pending");
                        if (autoApprove) {
                            entity.setApprovedBy(Actors.parseId(actor));
                            entity.setApprovedAt(Instant.now());
                        }
                        return entity;
                    }))
                .flatMap(repository::save)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actor, actorEmail).thenReturn(saved))
                .flatMap(saved -> {
                    if ("approved".equals(saved.getStatus())) {
                        return applyProviderBalanceDelta(saved, saved.getAmount(), actor, actorEmail)
                            .then(eventPublisher.publishAdvanceApproved(saved))
                            .thenReturn(saved);
                    }
                    return Mono.just(saved);
                });
        });
    }

    @Transactional
    public Mono<AdvancePayment> approve(UUID id, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Advance payment not found: " + id)))
            .flatMap(ap -> {
                if (!"pending".equals(ap.getStatus())) {
                    return Mono.error(new IllegalStateException(
                        "Cannot approve advance in status " + ap.getStatus()));
                }
                UUID approver = Actors.parseId(actor);
                if (approver != null && Objects.equals(ap.getRecordedBy(), approver)) {
                    return Mono.error(new IllegalStateException(
                        "Advance payment must be approved by an operator different from the recorder"));
                }
                Map<String, Object> before = Map.of("status", ap.getStatus());
                ap.setStatus("approved");
                ap.setApprovedBy(approver);
                ap.setApprovedAt(Instant.now());
                return repository.save(ap)
                    .flatMap(saved -> publishAudit(saved, before, "APPROVE", actor, actorEmail).thenReturn(saved))
                    .flatMap(saved -> applyProviderBalanceDelta(saved, saved.getAmount(), actor, actorEmail)
                        .then(eventPublisher.publishAdvanceApproved(saved))
                        .thenReturn(saved));
            });
    }

    @Transactional
    public Mono<AdvancePayment> reverse(UUID id, ReverseAdvancePaymentRequest req,
                                        String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Advance payment not found: " + id)))
            .flatMap(original -> {
                if (!("approved".equals(original.getStatus()) || "applied".equals(original.getStatus()))) {
                    return Mono.error(new IllegalStateException(
                        "Cannot reverse advance in status " + original.getStatus()));
                }
                if ("REVERSAL".equals(original.getType())) {
                    return Mono.error(new IllegalStateException(
                        "Cannot reverse a compensating REVERSAL row"));
                }
                Instant now = Instant.now();
                UUID actorId = Actors.parseId(actor);

                Map<String, Object> beforeOriginal = Map.of("status", original.getStatus());
                original.setStatus("reversed");

                AdvancePayment compensating = new AdvancePayment();
                compensating.setType("REVERSAL");
                compensating.setStatus("approved");
                compensating.setReversesAdvanceId(original.getId());
                compensating.setProviderId(original.getProviderId());
                compensating.setMemberId(original.getMemberId());
                compensating.setAmount(original.getAmount());
                compensating.setCurrencyCode(original.getCurrencyCode());
                compensating.setPaymentMethod(original.getPaymentMethod());
                compensating.setReference("REV-" + safeRef(original.getReference()));
                compensating.setComment(req.reason());
                compensating.setRecordedAt(now);
                compensating.setRecordedBy(actorId);
                compensating.setApprovedAt(now);
                compensating.setApprovedBy(actorId);

                return repository.save(original)
                    .flatMap(savedOriginal -> repository.save(compensating)
                        .flatMap(savedCompensating -> publishAudit(savedOriginal, beforeOriginal, "UPDATE",
                                actor, actorEmail)
                            .then(publishAudit(savedCompensating, null, "REVERSE", actor, actorEmail))
                            .then(applyProviderBalanceDelta(savedOriginal,
                                    savedOriginal.getAmount().negate(), actor, actorEmail))
                            .then(eventPublisher.publishAdvanceReversed(savedOriginal, savedCompensating))
                            .thenReturn(savedCompensating)));
            });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Mono<BigDecimal> convertToThresholdCurrency(BigDecimal amount, String amountCurrency,
                                                        TenantConfigClient.AdvancePaymentThreshold threshold,
                                                        UUID tenantId) {
        if (amountCurrency.equalsIgnoreCase(threshold.currencyCode())) {
            return Mono.just(amount);
        }
        return fxConverter.convert(amount, amountCurrency, threshold.currencyCode(),
                LocalDate.now(), tenantId);
    }

    /**
     * Reflect the cash movement on the provider's finance-side balance.
     * Provider advances count as {@code total_paid} (real cash out) and
     * therefore reduce {@code outstanding_balance} through the service's
     * recompute. Member advances intentionally do nothing here — billing-side
     * member balance is a separate ledger, and there is no finance-side
     * {@code member_balances} table today. See deviation log 2026-08-09.
     */
    private Mono<Void> applyProviderBalanceDelta(AdvancePayment advance, BigDecimal paidDelta,
                                                  String actor, String actorEmail) {
        if (advance.getProviderId() == null) {
            return Mono.empty();
        }
        return providerBalanceService.updateBalance(
                advance.getProviderId(),
                advance.getCurrencyCode(),
                null, null, paidDelta,
                actor, actorEmail)
            .then();
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    private String safeRef(String ref) {
        return ref == null ? advanceIdShortLabel() : ref;
    }

    private String advanceIdShortLabel() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Mono<Void> publishAudit(AdvancePayment a, Map<String, Object> before, String action,
                                     String actor, String actorEmail) {
        Map<String, Object> after = new HashMap<>();
        after.put("amount", a.getAmount().toPlainString());
        after.put("currencyCode", a.getCurrencyCode());
        after.put("providerId", a.getProviderId() != null ? a.getProviderId().toString() : null);
        after.put("memberId", a.getMemberId() != null ? a.getMemberId().toString() : null);
        after.put("reference", a.getReference());
        after.put("type", a.getType());
        after.put("status", a.getStatus());
        after.put("reversesAdvanceId", a.getReversesAdvanceId() != null ? a.getReversesAdvanceId().toString() : null);
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "AdvancePayment",
                a.getId() != null ? a.getId().toString() : "unknown",
                a.getReference() != null ? a.getReference() : "advance",
                action,
                actor != null ? actor : "system",
                actorEmail,
                before,
                after,
                new String[]{"status"},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
