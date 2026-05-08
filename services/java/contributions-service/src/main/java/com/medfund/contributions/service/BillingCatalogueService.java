package com.medfund.contributions.service;

import com.medfund.contributions.dto.UpsertBenefitTypeRequest;
import com.medfund.contributions.dto.UpsertBillingCycleConfigRequest;
import com.medfund.contributions.dto.UpsertDunningConfigRequest;
import com.medfund.contributions.dto.UpsertPaymentMethodRequest;
import com.medfund.contributions.dto.UpsertTransactionTypeRequest;
import com.medfund.contributions.entity.BenefitType;
import com.medfund.contributions.entity.BillingCycleConfig;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.entity.PaymentMethod;
import com.medfund.contributions.entity.TransactionType;
import com.medfund.contributions.repository.BenefitTypeRepository;
import com.medfund.contributions.repository.BillingCycleConfigRepository;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.repository.PaymentMethodRepository;
import com.medfund.contributions.repository.TransactionTypeRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD over the tenant-configurable billing catalogues (benefit types, payment
 * methods, transaction types) plus the two singleton config rows (dunning,
 * billing cycle). Every mutation publishes an audit event so admins can trace
 * who changed which catalogue entry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingCatalogueService {

    private final BenefitTypeRepository benefitTypeRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final DunningConfigRepository dunningConfigRepository;
    private final BillingCycleConfigRepository billingCycleConfigRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    // ── Benefit types ─────────────────────────────────────────────────────────

    public Flux<BenefitType> listBenefitTypes(boolean activeOnly) {
        return activeOnly ? benefitTypeRepository.findAllActiveOrdered()
                          : benefitTypeRepository.findAllOrdered();
    }

    @Transactional
    public Mono<BenefitType> createBenefitType(UpsertBenefitTypeRequest req, String actorId, String actorEmail) {
        BenefitType b = new BenefitType();
        b.setCode(req.code());
        b.setLabel(req.label());
        b.setDescription(req.description());
        b.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        b.setIsActive(req.isActive() != null ? req.isActive() : true);
        b.setCreatedAt(Instant.now());
        b.setUpdatedAt(Instant.now());
        return r2dbcTemplate.insert(b)
                .onErrorMap(DuplicateKeyException.class,
                        ex -> new IllegalArgumentException("Benefit type code already exists: " + req.code()))
                .flatMap(saved -> publishCatalogueAudit("BenefitType", saved.getId(), saved.getCode(),
                        "CREATE", null, benefitTypeMap(saved), actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<BenefitType> updateBenefitType(UUID id, UpsertBenefitTypeRequest req, String actorId, String actorEmail) {
        return benefitTypeRepository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Benefit type not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = benefitTypeMap(existing);
                    existing.setCode(req.code());
                    existing.setLabel(req.label());
                    existing.setDescription(req.description());
                    if (req.sortOrder() != null)  existing.setSortOrder(req.sortOrder());
                    if (req.isActive() != null)   existing.setIsActive(req.isActive());
                    existing.setUpdatedAt(Instant.now());
                    return benefitTypeRepository.save(existing)
                            .flatMap(saved -> publishCatalogueAudit("BenefitType", saved.getId(), saved.getCode(),
                                    "UPDATE", oldMap, benefitTypeMap(saved), actorId, actorEmail).thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> deleteBenefitType(UUID id, String actorId, String actorEmail) {
        return benefitTypeRepository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Benefit type not found: " + id)))
                .flatMap(existing -> benefitTypeRepository.delete(existing)
                        .then(publishCatalogueAudit("BenefitType", existing.getId(), existing.getCode(),
                                "DELETE", benefitTypeMap(existing), null, actorId, actorEmail)));
    }

    // ── Payment methods ───────────────────────────────────────────────────────

    public Flux<PaymentMethod> listPaymentMethods(boolean activeOnly) {
        return activeOnly ? paymentMethodRepository.findAllActiveOrdered()
                          : paymentMethodRepository.findAllOrdered();
    }

    @Transactional
    public Mono<PaymentMethod> createPaymentMethod(UpsertPaymentMethodRequest req, String actorId, String actorEmail) {
        PaymentMethod p = new PaymentMethod();
        p.setCode(req.code());
        p.setLabel(req.label());
        p.setDescription(req.description());
        p.setRequiresReference(req.requiresReference() != null ? req.requiresReference() : false);
        p.setIsActive(req.isActive() != null ? req.isActive() : true);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return r2dbcTemplate.insert(p)
                .onErrorMap(DuplicateKeyException.class,
                        ex -> new IllegalArgumentException("Payment method code already exists: " + req.code()))
                .flatMap(saved -> publishCatalogueAudit("PaymentMethod", saved.getId(), saved.getCode(),
                        "CREATE", null, paymentMethodMap(saved), actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<PaymentMethod> updatePaymentMethod(UUID id, UpsertPaymentMethodRequest req, String actorId, String actorEmail) {
        return paymentMethodRepository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Payment method not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = paymentMethodMap(existing);
                    existing.setCode(req.code());
                    existing.setLabel(req.label());
                    existing.setDescription(req.description());
                    if (req.requiresReference() != null) existing.setRequiresReference(req.requiresReference());
                    if (req.isActive() != null)          existing.setIsActive(req.isActive());
                    existing.setUpdatedAt(Instant.now());
                    return paymentMethodRepository.save(existing)
                            .flatMap(saved -> publishCatalogueAudit("PaymentMethod", saved.getId(), saved.getCode(),
                                    "UPDATE", oldMap, paymentMethodMap(saved), actorId, actorEmail).thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> deletePaymentMethod(UUID id, String actorId, String actorEmail) {
        return paymentMethodRepository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Payment method not found: " + id)))
                .flatMap(existing -> paymentMethodRepository.delete(existing)
                        .then(publishCatalogueAudit("PaymentMethod", existing.getId(), existing.getCode(),
                                "DELETE", paymentMethodMap(existing), null, actorId, actorEmail)));
    }

    // ── Transaction types ─────────────────────────────────────────────────────

    public Flux<TransactionType> listTransactionTypes(boolean activeOnly) {
        return activeOnly ? transactionTypeRepository.findAllActiveOrdered()
                          : transactionTypeRepository.findAllOrdered();
    }

    @Transactional
    public Mono<TransactionType> createTransactionType(UpsertTransactionTypeRequest req, String actorId, String actorEmail) {
        TransactionType t = new TransactionType();
        t.setCode(req.code());
        t.setLabel(req.label());
        t.setDescription(req.description());
        t.setSign(req.sign());
        t.setRequiresApproval(req.requiresApproval() != null ? req.requiresApproval() : false);
        t.setIsActive(req.isActive() != null ? req.isActive() : true);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return r2dbcTemplate.insert(t)
                .onErrorMap(DuplicateKeyException.class,
                        ex -> new IllegalArgumentException("Transaction type code already exists: " + req.code()))
                .flatMap(saved -> publishCatalogueAudit("TransactionType", saved.getId(), saved.getCode(),
                        "CREATE", null, transactionTypeMap(saved), actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TransactionType> updateTransactionType(UUID id, UpsertTransactionTypeRequest req, String actorId, String actorEmail) {
        return transactionTypeRepository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Transaction type not found: " + id)))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = transactionTypeMap(existing);
                    existing.setCode(req.code());
                    existing.setLabel(req.label());
                    existing.setDescription(req.description());
                    existing.setSign(req.sign());
                    if (req.requiresApproval() != null) existing.setRequiresApproval(req.requiresApproval());
                    if (req.isActive() != null)         existing.setIsActive(req.isActive());
                    existing.setUpdatedAt(Instant.now());
                    return transactionTypeRepository.save(existing)
                            .flatMap(saved -> publishCatalogueAudit("TransactionType", saved.getId(), saved.getCode(),
                                    "UPDATE", oldMap, transactionTypeMap(saved), actorId, actorEmail).thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> deleteTransactionType(UUID id, String actorId, String actorEmail) {
        return transactionTypeRepository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException("Transaction type not found: " + id)))
                .flatMap(existing -> transactionTypeRepository.delete(existing)
                        .then(publishCatalogueAudit("TransactionType", existing.getId(), existing.getCode(),
                                "DELETE", transactionTypeMap(existing), null, actorId, actorEmail)));
    }

    // ── Dunning config ────────────────────────────────────────────────────────

    public Mono<DunningConfig> getDunningConfig() {
        return dunningConfigRepository.findById(DunningConfig.SINGLETON_ID)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "dunning_config singleton row missing — run V008 migration")));
    }

    @Transactional
    public Mono<DunningConfig> upsertDunningConfig(UpsertDunningConfigRequest req, String actorId, String actorEmail) {
        return dunningConfigRepository.findById(DunningConfig.SINGLETON_ID)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    DunningConfig fresh = new DunningConfig();
                    fresh.setId(DunningConfig.SINGLETON_ID);
                    return fresh;
                }))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = existing.getId() != null ? dunningMap(existing) : null;
                    existing.setGraceDays(req.graceDays());
                    existing.setSuspensionDays(req.suspensionDays());
                    existing.setWriteOffDays(req.writeOffDays());
                    if (req.autoSuspend() != null)   existing.setAutoSuspend(req.autoSuspend());
                    if (req.autoWriteOff() != null)  existing.setAutoWriteOff(req.autoWriteOff());
                    existing.setUpdatedAt(Instant.now());
                    if (actorId != null) {
                        try { existing.setUpdatedBy(UUID.fromString(actorId)); } catch (IllegalArgumentException ignored) {}
                    }
                    return dunningConfigRepository.save(existing)
                            .flatMap(saved -> publishCatalogueAudit("DunningConfig", saved.getId(), "dunning",
                                    "UPDATE", oldMap, dunningMap(saved), actorId, actorEmail).thenReturn(saved));
                });
    }

    // ── Billing cycle config ──────────────────────────────────────────────────

    public Mono<BillingCycleConfig> getBillingCycleConfig() {
        return billingCycleConfigRepository.findById(BillingCycleConfig.SINGLETON_ID)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "billing_cycle_config singleton row missing — run V008 migration")));
    }

    @Transactional
    public Mono<BillingCycleConfig> upsertBillingCycleConfig(UpsertBillingCycleConfigRequest req, String actorId, String actorEmail) {
        return billingCycleConfigRepository.findById(BillingCycleConfig.SINGLETON_ID)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    BillingCycleConfig fresh = new BillingCycleConfig();
                    fresh.setId(BillingCycleConfig.SINGLETON_ID);
                    return fresh;
                }))
                .flatMap(existing -> {
                    Map<String, Object> oldMap = existing.getFrequency() != null ? cycleMap(existing) : null;
                    existing.setFrequency(req.frequency());
                    if (req.dayOfMonth() != null)          existing.setDayOfMonth(req.dayOfMonth());
                    if (req.autoGenerate() != null)        existing.setAutoGenerate(req.autoGenerate());
                    if (req.commitCooldownHours() != null) existing.setCommitCooldownHours(req.commitCooldownHours());
                    existing.setUpdatedAt(Instant.now());
                    if (actorId != null) {
                        try { existing.setUpdatedBy(UUID.fromString(actorId)); } catch (IllegalArgumentException ignored) {}
                    }
                    return billingCycleConfigRepository.save(existing)
                            .flatMap(saved -> publishCatalogueAudit("BillingCycleConfig", saved.getId(), "cycle",
                                    "UPDATE", oldMap, cycleMap(saved), actorId, actorEmail).thenReturn(saved));
                });
    }

    // ── Audit helpers ─────────────────────────────────────────────────────────

    private Mono<Void> publishCatalogueAudit(String entityType, UUID entityId, String entityName,
                                             String action, Map<String, Object> oldMap, Map<String, Object> newMap,
                                             String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    entityType,
                    entityId != null ? entityId.toString() : null,
                    entityName,
                    action,
                    actorId,
                    actorEmail,
                    oldMap,
                    newMap,
                    null,
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private Map<String, Object> benefitTypeMap(BenefitType b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", b.getCode());
        m.put("label", b.getLabel());
        m.put("description", b.getDescription());
        m.put("sortOrder", b.getSortOrder());
        m.put("isActive", b.getIsActive());
        return m;
    }

    private Map<String, Object> paymentMethodMap(PaymentMethod p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("label", p.getLabel());
        m.put("description", p.getDescription());
        m.put("requiresReference", p.getRequiresReference());
        m.put("isActive", p.getIsActive());
        return m;
    }

    private Map<String, Object> transactionTypeMap(TransactionType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.getCode());
        m.put("label", t.getLabel());
        m.put("description", t.getDescription());
        m.put("sign", t.getSign());
        m.put("requiresApproval", t.getRequiresApproval());
        m.put("isActive", t.getIsActive());
        return m;
    }

    private Map<String, Object> dunningMap(DunningConfig d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("graceDays", d.getGraceDays());
        m.put("suspensionDays", d.getSuspensionDays());
        m.put("writeOffDays", d.getWriteOffDays());
        m.put("autoSuspend", d.getAutoSuspend());
        m.put("autoWriteOff", d.getAutoWriteOff());
        return m;
    }

    private Map<String, Object> cycleMap(BillingCycleConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frequency", c.getFrequency());
        m.put("dayOfMonth", c.getDayOfMonth());
        m.put("autoGenerate", c.getAutoGenerate());
        m.put("commitCooldownHours", c.getCommitCooldownHours());
        return m;
    }
}
