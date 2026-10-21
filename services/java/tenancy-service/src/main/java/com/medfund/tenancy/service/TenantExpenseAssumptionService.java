package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.insurance.InsuranceLine;
import com.medfund.tenancy.dto.AddTenantExpenseAssumptionRequest;
import com.medfund.tenancy.dto.UpdateTenantExpenseAssumptionRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantExpenseAssumption;
import com.medfund.tenancy.repository.TenantExpenseAssumptionRepository;
import com.medfund.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_expense_assumption} — per-tenant,
 * per-line, per-expense-type unit costs feeding the GMM fulfilment cash
 * flow projection (Phase 15 §12). Multi-row config keyed by (insurance
 * line, expense type, currency, effective_from).
 *
 * <p>Amounts are stored per-policy in the assumption's own currency; the
 * compute path converts to the reporting currency via
 * {@code exchange_rates}, keeping Rule 1 (never mix currencies in
 * arithmetic) upheld at the boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantExpenseAssumptionService {

    private static final String ENTITY_TYPE = "TENANT_EXPENSE_ASSUMPTION";
    private static final Set<String> ALLOWED_TYPES =
            Set.of("ACQUISITION", "MAINTENANCE", "CLAIMS_HANDLING", "OVERHEAD", "OTHER");

    private final TenantExpenseAssumptionRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantExpenseAssumption> list(UUID tenantId) {
        return repository.findByTenantIdOrderByInsuranceLineAscExpenseTypeAscEffectiveFromDesc(tenantId);
    }

    @Transactional
    public Mono<TenantExpenseAssumption> add(UUID tenantId,
                                             AddTenantExpenseAssumptionRequest req,
                                             String actorId,
                                             String actorEmail) {
        String line = normaliseLine(req.insuranceLine());
        String type = normaliseType(req.expenseType());
        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();

        TenantExpenseAssumption row = new TenantExpenseAssumption();
        row.setTenantId(tenantId);
        row.setInsuranceLine(line);
        row.setExpenseType(type);
        row.setAmountPerPolicy(req.amountPerPolicy());
        row.setCurrency(req.currency().toUpperCase());
        row.setSourceNote(req.sourceNote());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setUpdatedBy(parseUuid(actorId));
        row.setUpdatedByEmail(actorEmail);
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantExpenseAssumption> update(UUID tenantId, UUID id,
                                                UpdateTenantExpenseAssumptionRequest req,
                                                String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Expense assumption not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantExpenseAssumption>error(new IllegalArgumentException(
                                "Expense assumption does not belong to tenant"));
                    }
                    TenantExpenseAssumption snapshot = copy(existing);
                    existing.setAmountPerPolicy(req.amountPerPolicy());
                    existing.setSourceNote(req.sourceNote());
                    existing.setEffectiveTo(req.effectiveTo());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setUpdatedBy(parseUuid(actorId));
                    existing.setUpdatedByEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit(saved, snapshot, "UPDATE", actorId, actorEmail)
                                    .thenReturn(saved));
                });
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID id, String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Expense assumption not found: " + id)))
                .flatMap(existing -> {
                    if (!existing.getTenantId().equals(tenantId)) {
                        return Mono.<TenantExpenseAssumption>error(new IllegalArgumentException(
                                "Expense assumption does not belong to tenant"));
                    }
                    return repository.delete(existing)
                            .then(publishAudit(existing, copy(existing), "DELETE", actorId, actorEmail))
                            .thenReturn(existing);
                })
                .then();
    }

    private static String normaliseLine(String raw) {
        if (raw == null) throw new IllegalArgumentException("insuranceLine is required");
        String n = raw.trim().toUpperCase();
        if (!InsuranceLine.isKnown(n)) {
            throw new IllegalArgumentException("Unknown insurance line: " + raw);
        }
        return n;
    }

    private static String normaliseType(String raw) {
        if (raw == null) throw new IllegalArgumentException("expenseType is required");
        String n = raw.trim().toUpperCase();
        if (!ALLOWED_TYPES.contains(n)) {
            throw new IllegalArgumentException(
                    "expenseType must be one of " + ALLOWED_TYPES + ", got: " + raw);
        }
        return n;
    }

    private TenantExpenseAssumption copy(TenantExpenseAssumption src) {
        TenantExpenseAssumption c = new TenantExpenseAssumption();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setInsuranceLine(src.getInsuranceLine());
        c.setExpenseType(src.getExpenseType());
        c.setAmountPerPolicy(src.getAmountPerPolicy());
        c.setCurrency(src.getCurrency());
        c.setSourceNote(src.getSourceNote());
        c.setEffectiveFrom(src.getEffectiveFrom());
        c.setEffectiveTo(src.getEffectiveTo());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        c.setUpdatedBy(src.getUpdatedBy());
        c.setUpdatedByEmail(src.getUpdatedByEmail());
        return c;
    }

    private Mono<Void> publishAudit(TenantExpenseAssumption current,
                                    TenantExpenseAssumption previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null
                ? changedFields(oldMap, newMap) : null;

        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("ExpenseAssumption for tenant %s %s/%s %s",
                                slug, current.getInsuranceLine(), current.getExpenseType(),
                                current.getCurrency()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private Map<String, Object> toMap(TenantExpenseAssumption row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insuranceLine", row.getInsuranceLine());
        m.put("expenseType", row.getExpenseType());
        m.put("amountPerPolicy", row.getAmountPerPolicy() != null
                ? row.getAmountPerPolicy().toPlainString() : null);
        m.put("currency", row.getCurrency());
        m.put("sourceNote", row.getSourceNote());
        m.put("effectiveFrom", row.getEffectiveFrom() != null ? row.getEffectiveFrom().toString() : null);
        m.put("effectiveTo", row.getEffectiveTo() != null ? row.getEffectiveTo().toString() : null);
        return m;
    }

    private String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
