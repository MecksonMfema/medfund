package com.medfund.contributions.service;

import com.medfund.contributions.dto.CreateBenefitCostShareRequest;
import com.medfund.contributions.dto.CreateBenefitCostShareTierRequest;
import com.medfund.contributions.dto.CreateSchemeCostShareRequest;
import com.medfund.contributions.entity.BenefitCostShare;
import com.medfund.contributions.entity.BenefitCostShareTier;
import com.medfund.contributions.entity.SchemeCostShare;
import com.medfund.contributions.repository.BenefitCostShareRepository;
import com.medfund.contributions.repository.BenefitCostShareTierRepository;
import com.medfund.contributions.repository.SchemeCostShareRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for temporal cost-share configuration (Phase 1). Every mutation is a
 * pure insert — there is no in-place edit; effective ranges resolve the
 * currently-applicable row via {@code findEffective}. See
 * {@link SchemeCostShareRepository} for the read semantics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemeCostShareService {

    private final SchemeCostShareRepository schemeCostShareRepository;
    private final BenefitCostShareRepository benefitCostShareRepository;
    private final BenefitCostShareTierRepository benefitCostShareTierRepository;
    private final AuditPublisher auditPublisher;

    // ── Scheme-level ────────────────────────────────────────────────────────

    public Mono<SchemeCostShare> findEffectiveScheme(UUID schemeId, int policyYear, LocalDate asOf) {
        return schemeCostShareRepository.findEffective(schemeId, policyYear, asOf);
    }

    public Flux<SchemeCostShare> findSchemeHistory(UUID schemeId, int policyYear) {
        return schemeCostShareRepository.findHistory(schemeId, policyYear);
    }

    @Transactional
    public Mono<SchemeCostShare> createScheme(UUID schemeId, CreateSchemeCostShareRequest request,
                                              String actorId, String actorEmail) {
        var row = new SchemeCostShare();
        row.setSchemeId(schemeId);
        row.setPolicyYear(request.policyYear());
        row.setDeductible(request.deductible());
        row.setOutOfPocketMax(request.outOfPocketMax());
        row.setDeductibleScope(request.deductibleScope());
        row.setOopScope(request.oopScope());
        row.setShortfallPolicy(request.shortfallPolicy());
        row.setCurrencyCode(request.currencyCode().toUpperCase());
        row.setEffectiveFrom(request.effectiveFrom());
        row.setEffectiveTo(request.effectiveTo());
        UUID actorUuid = parseUuidOrRandom(actorId);
        row.setCreatedBy(actorUuid);
        row.setUpdatedBy(actorUuid);
        return schemeCostShareRepository.save(row)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    // Friendly entityName per feedback_audit_entity_name — never the UUID.
                    String entityName = "Scheme " + saved.getSchemeId() + " cost-share " + saved.getPolicyYear();
                    Map<String, Object> newValue = new HashMap<>();
                    newValue.put("policyYear", saved.getPolicyYear());
                    newValue.put("deductible", asPlainString(saved.getDeductible()));
                    newValue.put("outOfPocketMax", asPlainString(saved.getOutOfPocketMax()));
                    newValue.put("deductibleScope", saved.getDeductibleScope());
                    newValue.put("oopScope", saved.getOopScope());
                    newValue.put("shortfallPolicy", saved.getShortfallPolicy());
                    newValue.put("currencyCode", saved.getCurrencyCode());
                    newValue.put("effectiveFrom", String.valueOf(saved.getEffectiveFrom()));
                    return publishAudit(tenantId, "SchemeCostShare", saved.getId().toString(), entityName,
                            "CREATE", actorId, actorEmail, null, newValue)
                            .thenReturn(saved);
                }));
    }

    // ── Benefit-level ───────────────────────────────────────────────────────

    public Mono<BenefitCostShare> findEffectiveBenefit(UUID benefitId, LocalDate asOf) {
        return benefitCostShareRepository.findEffective(benefitId, asOf);
    }

    public Flux<BenefitCostShare> findBenefitHistory(UUID benefitId) {
        return benefitCostShareRepository.findHistory(benefitId);
    }

    @Transactional
    public Mono<BenefitCostShare> createBenefit(UUID benefitId, CreateBenefitCostShareRequest request,
                                                String actorId, String actorEmail) {
        var row = new BenefitCostShare();
        row.setSchemeBenefitId(benefitId);
        row.setCopayType(request.copayType());
        row.setCopayAmount(request.copayAmount());
        row.setCopayPercentage(request.copayPercentage());
        row.setCopayMax(request.copayMax());
        row.setCoinsuranceRate(request.coinsuranceRate());
        if (request.appliesToDeductible() != null) row.setAppliesToDeductible(request.appliesToDeductible());
        if (request.appliesToOopMax() != null) row.setAppliesToOopMax(request.appliesToOopMax());
        row.setBasis(request.basis() != null ? request.basis() : "per_visit");
        row.setEffectiveFrom(request.effectiveFrom());
        row.setEffectiveTo(request.effectiveTo());
        UUID actorUuid = parseUuidOrRandom(actorId);
        row.setCreatedBy(actorUuid);
        row.setUpdatedBy(actorUuid);
        return benefitCostShareRepository.save(row)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    String entityName = "Benefit " + saved.getSchemeBenefitId() + " cost-share";
                    Map<String, Object> newValue = new HashMap<>();
                    newValue.put("copayType", saved.getCopayType());
                    newValue.put("copayAmount", asPlainString(saved.getCopayAmount()));
                    newValue.put("copayPercentage", asPlainString(saved.getCopayPercentage()));
                    newValue.put("coinsuranceRate", asPlainString(saved.getCoinsuranceRate()));
                    newValue.put("appliesToDeductible", saved.getAppliesToDeductible());
                    newValue.put("appliesToOopMax", saved.getAppliesToOopMax());
                    newValue.put("basis", saved.getBasis());
                    newValue.put("effectiveFrom", String.valueOf(saved.getEffectiveFrom()));
                    return publishAudit(tenantId, "BenefitCostShare", saved.getId().toString(), entityName,
                            "CREATE", actorId, actorEmail, null, newValue)
                            .thenReturn(saved);
                }));
    }

    // ── Tier ────────────────────────────────────────────────────────────────

    public Flux<BenefitCostShareTier> findTiers(UUID benefitCostShareId) {
        return benefitCostShareTierRepository.findByBenefitCostShareId(benefitCostShareId);
    }

    @Transactional
    public Mono<BenefitCostShareTier> createTier(UUID benefitCostShareId, CreateBenefitCostShareTierRequest request,
                                                 String actorId, String actorEmail) {
        var row = new BenefitCostShareTier();
        row.setBenefitCostShareId(benefitCostShareId);
        row.setTierName(request.tierName());
        row.setCopayAmount(request.copayAmount());
        row.setCopayPercentage(request.copayPercentage());
        row.setCopayMax(request.copayMax());
        return benefitCostShareTierRepository.save(row)
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    String entityName = "Benefit cost-share " + saved.getBenefitCostShareId()
                            + " tier " + saved.getTierName();
                    Map<String, Object> newValue = new HashMap<>();
                    newValue.put("tierName", saved.getTierName());
                    newValue.put("copayAmount", asPlainString(saved.getCopayAmount()));
                    newValue.put("copayPercentage", asPlainString(saved.getCopayPercentage()));
                    newValue.put("copayMax", asPlainString(saved.getCopayMax()));
                    return publishAudit(tenantId, "BenefitCostShareTier", saved.getId().toString(), entityName,
                            "CREATE", actorId, actorEmail, null, newValue)
                            .thenReturn(saved);
                }));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

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
                new String[]{},
                UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    private static UUID parseUuidOrRandom(String s) {
        if (s == null || s.isBlank()) return UUID.randomUUID();
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return UUID.randomUUID(); }
    }

    private static String asPlainString(java.math.BigDecimal v) {
        return v != null ? v.toPlainString() : "";
    }
}
