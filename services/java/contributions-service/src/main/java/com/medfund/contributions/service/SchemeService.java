package com.medfund.contributions.service;

import com.medfund.contributions.dto.CreateAgeGroupRequest;
import com.medfund.contributions.dto.CreateSchemeBenefitRequest;
import com.medfund.contributions.dto.CreateSchemeRequest;
import com.medfund.contributions.dto.UpdateSchemeBenefitRequest;
import com.medfund.contributions.dto.UpdateSchemeRequest;
import com.medfund.contributions.entity.AgeGroup;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.exception.DuplicateSchemeException;
import com.medfund.contributions.exception.SchemeNotFoundException;
import com.medfund.contributions.repository.AgeGroupRepository;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
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
import java.util.Map;
import java.util.UUID;

@Service
public class SchemeService {

    private static final Logger log = LoggerFactory.getLogger(SchemeService.class);

    private final SchemeRepository schemeRepository;
    private final SchemeBenefitRepository schemeBenefitRepository;
    private final AgeGroupRepository ageGroupRepository;
    private final AuditPublisher auditPublisher;

    public SchemeService(SchemeRepository schemeRepository,
                         SchemeBenefitRepository schemeBenefitRepository,
                         AgeGroupRepository ageGroupRepository,
                         AuditPublisher auditPublisher) {
        this.schemeRepository = schemeRepository;
        this.schemeBenefitRepository = schemeBenefitRepository;
        this.ageGroupRepository = ageGroupRepository;
        this.auditPublisher = auditPublisher;
    }

    public Flux<Scheme> findAll() {
        return schemeRepository.findAllOrderByName();
    }

    public Mono<Scheme> findById(UUID id) {
        return schemeRepository.findById(id)
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(id)));
    }

    public Flux<Scheme> findByStatus(String status) {
        return schemeRepository.findByStatus(status);
    }

    @Transactional
    public Mono<Scheme> create(CreateSchemeRequest request, String actorId) {
        return schemeRepository.existsByName(request.name())
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<Scheme>error(new DuplicateSchemeException(request.name()));
                }

                var scheme = new Scheme();
                scheme.setId(UUID.randomUUID());
                scheme.setName(request.name());
                scheme.setDescription(request.description());
                scheme.setSchemeType(request.schemeTypeOrDefault());
                scheme.setStatus("active");
                scheme.setEffectiveDate(request.effectiveDate());
                scheme.setEndDate(request.endDate());
                scheme.setCurrencyCode(normalizeCurrency(request.currencyCode()));
                scheme.setCreatedAt(Instant.now());
                scheme.setUpdatedAt(Instant.now());
                scheme.setCreatedBy(UUID.fromString(actorId));
                scheme.setUpdatedBy(UUID.fromString(actorId));

                return schemeRepository.save(scheme);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Scheme", saved.getId().toString(), "CREATE", actorId,
                        null,
                        Map.of("name", saved.getName(), "status", saved.getStatus(),
                               "schemeType", saved.getSchemeType()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Scheme> update(UUID id, UpdateSchemeRequest request, String actorId) {
        return schemeRepository.findById(id)
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(id)))
            .flatMap(scheme -> {
                Map<String, Object> oldValue = Map.of(
                    "name", scheme.getName() != null ? scheme.getName() : "",
                    "description", scheme.getDescription() != null ? scheme.getDescription() : "",
                    "schemeType", scheme.getSchemeType() != null ? scheme.getSchemeType() : ""
                );

                if (request.name() != null) {
                    scheme.setName(request.name());
                }
                if (request.description() != null) {
                    scheme.setDescription(request.description());
                }
                if (request.schemeType() != null) {
                    scheme.setSchemeType(request.schemeType());
                }
                if (request.endDate() != null) {
                    scheme.setEndDate(request.endDate());
                }
                if (request.currencyCode() != null && !request.currencyCode().isBlank()) {
                    scheme.setCurrencyCode(normalizeCurrency(request.currencyCode()));
                }
                scheme.setUpdatedAt(Instant.now());
                scheme.setUpdatedBy(UUID.fromString(actorId));

                return schemeRepository.save(scheme)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Scheme", saved.getId().toString(), "UPDATE", actorId,
                                oldValue,
                                Map.of("name", saved.getName(), "description",
                                       saved.getDescription() != null ? saved.getDescription() : "",
                                       "schemeType", saved.getSchemeType()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Scheme> deactivate(UUID id, String actorId) {
        return schemeRepository.findById(id)
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(id)))
            .flatMap(scheme -> {
                String previousStatus = scheme.getStatus();
                scheme.setStatus("inactive");
                scheme.setUpdatedAt(Instant.now());
                scheme.setUpdatedBy(UUID.fromString(actorId));

                return schemeRepository.save(scheme)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Scheme", saved.getId().toString(), "UPDATE", actorId,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    public Flux<SchemeBenefit> findBenefitsBySchemeId(UUID schemeId) {
        return schemeBenefitRepository.findBySchemeId(schemeId);
    }

    @Transactional
    public Mono<SchemeBenefit> createBenefit(CreateSchemeBenefitRequest request, String actorId) {
        return schemeRepository.findById(request.schemeId())
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(request.schemeId())))
            .flatMap(scheme -> {
                String resolvedCurrency = resolveChildCurrency(scheme, request.currencyCode());

                var benefit = new SchemeBenefit();
                benefit.setId(UUID.randomUUID());
                benefit.setSchemeId(request.schemeId());
                benefit.setName(request.name());
                benefit.setBenefitType(request.benefitType());
                benefit.setAnnualLimit(request.annualLimit());
                benefit.setDailyLimit(request.dailyLimit());
                benefit.setEventLimit(request.eventLimit());
                benefit.setCurrencyCode(resolvedCurrency);
                benefit.setWaitingPeriodDays(request.waitingPeriodDays());
                benefit.setDescription(request.description());
                benefit.setCreatedAt(Instant.now());
                benefit.setUpdatedAt(Instant.now());

                return schemeBenefitRepository.save(benefit);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "SchemeBenefit", saved.getId().toString(), "CREATE", actorId,
                        null,
                        Map.of("name", saved.getName(), "benefitType", saved.getBenefitType(),
                               "schemeId", saved.getSchemeId().toString()))
                    .thenReturn(saved);
            }));
    }

    public Mono<SchemeBenefit> findBenefitById(UUID id) {
        return schemeBenefitRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme benefit not found: " + id)));
    }

    @Transactional
    public Mono<SchemeBenefit> updateBenefit(UUID id, UpdateSchemeBenefitRequest request, String actorId) {
        return schemeBenefitRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme benefit not found: " + id)))
            .flatMap(existing -> schemeRepository.findById(existing.getSchemeId())
                .switchIfEmpty(Mono.error(new SchemeNotFoundException(existing.getSchemeId())))
                .flatMap(scheme -> {
                    String previousName = existing.getName();
                    String previousType = existing.getBenefitType();
                    String resolvedCurrency = resolveChildCurrency(scheme, request.currencyCode());

                    existing.setName(request.name());
                    existing.setBenefitType(request.benefitType());
                    existing.setAnnualLimit(request.annualLimit());
                    existing.setDailyLimit(request.dailyLimit());
                    existing.setEventLimit(request.eventLimit());
                    existing.setCurrencyCode(resolvedCurrency);
                    existing.setWaitingPeriodDays(request.waitingPeriodDays());
                    existing.setDescription(request.description());
                    existing.setUpdatedAt(Instant.now());

                    return schemeBenefitRepository.save(existing)
                        .flatMap(saved -> Mono.deferContextual(ctx -> {
                            String tenantId = TenantContext.get(ctx);
                            return publishAudit(tenantId, "SchemeBenefit", saved.getId().toString(), "UPDATE", actorId,
                                    Map.of("name", previousName, "benefitType", previousType),
                                    Map.of("name", saved.getName(), "benefitType", saved.getBenefitType(),
                                           "annualLimit", saved.getAnnualLimit() != null ? saved.getAnnualLimit().toString() : ""))
                                .thenReturn(saved);
                        }));
                }));
    }

    @Transactional
    public Mono<Void> deleteBenefit(UUID id, String actorId) {
        return schemeBenefitRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme benefit not found: " + id)))
            .flatMap(existing -> schemeBenefitRepository.delete(existing)
                .then(Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return publishAudit(tenantId, "SchemeBenefit", existing.getId().toString(), "DELETE", actorId,
                            Map.of("name", existing.getName(), "benefitType", existing.getBenefitType(),
                                   "schemeId", existing.getSchemeId().toString()),
                            null);
                })));
    }

    public Flux<AgeGroup> findAgeGroupsBySchemeId(UUID schemeId) {
        return ageGroupRepository.findBySchemeId(schemeId);
    }

    @Transactional
    public Mono<AgeGroup> createAgeGroup(CreateAgeGroupRequest request, String actorId) {
        return schemeRepository.findById(request.schemeId())
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(request.schemeId())))
            .flatMap(scheme -> {
                String resolvedCurrency = resolveChildCurrency(scheme, request.currencyCode());

                var ageGroup = new AgeGroup();
                ageGroup.setId(UUID.randomUUID());
                ageGroup.setSchemeId(request.schemeId());
                ageGroup.setName(request.name());
                ageGroup.setMinAge(request.minAge());
                ageGroup.setMaxAge(request.maxAge());
                ageGroup.setContributionAmount(request.contributionAmount());
                ageGroup.setCurrencyCode(resolvedCurrency);
                ageGroup.setCreatedAt(Instant.now());

                return ageGroupRepository.save(ageGroup);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "AgeGroup", saved.getId().toString(), "CREATE", actorId,
                        null,
                        Map.of("name", saved.getName(), "minAge", saved.getMinAge().toString(),
                               "maxAge", saved.getMaxAge().toString(),
                               "schemeId", saved.getSchemeId().toString()))
                    .thenReturn(saved);
            }));
    }

    // ---- Private helpers ----

    private static String normalizeCurrency(String code) {
        if (code == null || code.isBlank()) {
            return "USD";
        }
        return code.toUpperCase();
    }

    /**
     * Children of a scheme (benefits, age groups, contributions) inherit the scheme's
     * currency. If a request explicitly sets a different currency, reject — the rule
     * "one currency per scheme" is enforced here so error messages name the offending
     * field. A blank/null request currency is silently inherited from the parent.
     */
    private static String resolveChildCurrency(Scheme scheme, String requested) {
        String schemeCurrency = scheme.getCurrencyCode();
        if (schemeCurrency == null || schemeCurrency.isBlank()) {
            schemeCurrency = "USD";
        }
        if (requested == null || requested.isBlank()) {
            return schemeCurrency;
        }
        if (!schemeCurrency.equalsIgnoreCase(requested)) {
            throw new IllegalArgumentException(
                "currencyCode '" + requested + "' does not match the parent scheme's currency '"
                    + schemeCurrency + "'. Schemes are single-currency.");
        }
        return schemeCurrency;
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId,
                                     String action, String actorId,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            action,
            actorId,
            null,
            oldValue,
            newValue,
            new String[]{},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
