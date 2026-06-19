package com.medfund.claims.service;

import com.medfund.claims.dto.UpsertDrugRequest;
import com.medfund.claims.entity.Drug;
import com.medfund.claims.repository.DrugRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class DrugService {

    private final DrugRepository repo;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public DrugService(DrugRepository repo, R2dbcEntityTemplate r2dbcTemplate, AuditPublisher auditPublisher) {
        this.repo = repo;
        this.r2dbcTemplate = r2dbcTemplate;
        this.auditPublisher = auditPublisher;
    }

    public Flux<Drug> findAll(boolean activeOnly) {
        return activeOnly ? repo.findAllActive() : repo.findAllOrdered();
    }

    public Mono<Drug> findById(UUID id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Drug not found: " + id)));
    }

    public Flux<Drug> search(String q) {
        return repo.searchByName(q);
    }

    @Transactional
    public Mono<Drug> create(UpsertDrugRequest request, String actorId, String actorEmail) {
        var drug = new Drug();
        drug.setDrugName(request.drugName());
        drug.setDrugType(request.drugType() != null ? request.drugType() : "ACUTE");
        drug.setUnitOfMeasurement(request.unitOfMeasurement() != null ? request.unitOfMeasurement() : "unit");
        drug.setTariffCode(request.tariffCode());
        drug.setWholesaleCostZwl(request.wholesaleCostZwl());
        drug.setWholesaleCostUsd(request.wholesaleCostUsd());
        drug.setPaymentPercentage(request.paymentPercentage() != null
                ? request.paymentPercentage() : new BigDecimal("100.00"));
        drug.setDoNotPay(Boolean.TRUE.equals(request.doNotPay()));
        drug.setIsActive(request.isActive() != null ? request.isActive() : true);
        return r2dbcTemplate.insert(drug)
                .flatMap(saved -> audit(saved, "CREATE", actorId, actorEmail, null,
                        Map.of("drugName", saved.getDrugName(), "drugType", saved.getDrugType())));
    }

    @Transactional
    public Mono<Drug> update(UUID id, UpsertDrugRequest request, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> {
            if (request.drugName() != null) existing.setDrugName(request.drugName());
            if (request.drugType() != null) existing.setDrugType(request.drugType());
            if (request.unitOfMeasurement() != null) existing.setUnitOfMeasurement(request.unitOfMeasurement());
            existing.setTariffCode(request.tariffCode());
            existing.setWholesaleCostZwl(request.wholesaleCostZwl());
            existing.setWholesaleCostUsd(request.wholesaleCostUsd());
            if (request.paymentPercentage() != null) existing.setPaymentPercentage(request.paymentPercentage());
            if (request.doNotPay() != null) existing.setDoNotPay(request.doNotPay());
            if (request.isActive() != null) existing.setIsActive(request.isActive());
            return repo.save(existing)
                    .flatMap(saved -> audit(saved, "UPDATE", actorId, actorEmail,
                            Map.of("drugName", saved.getDrugName()),
                            Map.of("drugName", saved.getDrugName(), "drugType", saved.getDrugType())));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actorId, String actorEmail) {
        return findById(id).flatMap(existing -> repo.deleteById(id)
                .then(audit(existing, "DELETE", actorId, actorEmail,
                        Map.of("drugName", existing.getDrugName()), null)).then());
    }

    private Mono<Drug> audit(Drug d, String action, String actorId, String actorEmail,
                              Map<String, Object> oldVal, Map<String, Object> newVal) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Drug", d.getId().toString(), d.getDrugName(),
                    action, actorId, actorEmail,
                    oldVal, newVal,
                    new String[]{"drugName", "drugType", "isActive"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event).thenReturn(d);
        });
    }
}
