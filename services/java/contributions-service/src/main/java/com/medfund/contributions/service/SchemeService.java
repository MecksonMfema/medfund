package com.medfund.contributions.service;

import com.medfund.contributions.dto.CreateAgeGroupRequest;
import com.medfund.contributions.dto.UpdateAgeGroupRequest;
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
import com.medfund.contributions.util.PersonCentricLines;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
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

    /** Free-text search over name + scheme_type — feeds the operational EntityPicker. */
    public Flux<Scheme> search(String q, int limit) {
        if (q == null || q.isBlank()) return Flux.empty();
        return schemeRepository.search(q.trim(), Math.min(Math.max(limit, 1), 50));
    }

    @Transactional
    public Mono<Scheme> create(CreateSchemeRequest request, String actorId, String actorEmail) {
        return schemeRepository.existsByName(request.name())
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.<Scheme>error(new DuplicateSchemeException(request.name()));
                }

                // Leave the @Id null — Spring Data R2DBC's repository.save()
                // detects null IDs and runs INSERT (the table has
                // DEFAULT gen_random_uuid()). Setting the ID ourselves makes
                // save() take the UPDATE path and fail with "Row does not
                // exist" because there's no matching row yet.
                var scheme = new Scheme();
                scheme.setName(request.name());
                scheme.setDescription(request.description());
                scheme.setSchemeType(request.schemeTypeOrDefault());
                scheme.setInsuranceLine(resolveInsuranceLine(request.insuranceLine()));
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
                return publishAudit(tenantId, "Scheme", saved.getId().toString(), "CREATE", actorId, actorEmail,
                        null,
                        Map.of("name", saved.getName(), "status", saved.getStatus(),
                               "schemeType", saved.getSchemeType()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Scheme> update(UUID id, UpdateSchemeRequest request, String actorId, String actorEmail) {
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
                if (request.insuranceLine() != null) {
                    scheme.setInsuranceLine(resolveInsuranceLine(request.insuranceLine()));
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
                        return publishAudit(tenantId, "Scheme", saved.getId().toString(), "UPDATE", actorId, actorEmail,
                                oldValue,
                                Map.of("name", saved.getName(), "description",
                                       saved.getDescription() != null ? saved.getDescription() : "",
                                       "schemeType", saved.getSchemeType()))
                            .thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<Scheme> deactivate(UUID id, String actorId, String actorEmail) {
        return setSchemeStatus(id, "inactive", actorId, actorEmail);
    }

    /** Re-enables a previously-deactivated scheme. Counterpart to {@link #deactivate}. */
    @Transactional
    public Mono<Scheme> activate(UUID id, String actorId, String actorEmail) {
        return setSchemeStatus(id, "active", actorId, actorEmail);
    }

    private Mono<Scheme> setSchemeStatus(UUID id, String status, String actorId, String actorEmail) {
        return schemeRepository.findById(id)
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(id)))
            .flatMap(scheme -> {
                String previousStatus = scheme.getStatus();
                scheme.setStatus(status);
                scheme.setUpdatedAt(Instant.now());
                scheme.setUpdatedBy(UUID.fromString(actorId));

                return schemeRepository.save(scheme)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Scheme", saved.getId().toString(), "UPDATE", actorId, actorEmail,
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
    public Mono<SchemeBenefit> createBenefit(CreateSchemeBenefitRequest request, String actorId, String actorEmail) {
        return schemeRepository.findById(request.schemeId())
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(request.schemeId())))
            .flatMap(scheme -> {
                if (!PersonCentricLines.isPersonCentric(scheme.getInsuranceLine())) {
                    return Mono.<SchemeBenefit>error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Scheme benefits do not apply to " + scheme.getInsuranceLine()
                            + " schemes — they are asset-centric and have no member-level benefit limits."));
                }
                String resolvedCurrency = resolveChildCurrency(scheme, request.currencyCode());

                var benefit = new SchemeBenefit();
                // @Id left null — see comment on create(): pre-setting the
                // ID makes save() take the UPDATE path and fail.
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
                return publishAudit(tenantId, "SchemeBenefit", saved.getId().toString(), "CREATE", actorId, actorEmail,
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
    public Mono<SchemeBenefit> updateBenefit(UUID id, UpdateSchemeBenefitRequest request, String actorId, String actorEmail) {
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
                            return publishAudit(tenantId, "SchemeBenefit", saved.getId().toString(), "UPDATE", actorId, actorEmail,
                                    Map.of("name", previousName, "benefitType", previousType),
                                    Map.of("name", saved.getName(), "benefitType", saved.getBenefitType(),
                                           "annualLimit", saved.getAnnualLimit() != null ? saved.getAnnualLimit().toString() : ""))
                                .thenReturn(saved);
                        }));
                }));
    }

    /** Soft-delete a benefit by flipping its status to "inactive". */
    @Transactional
    public Mono<SchemeBenefit> deactivateBenefit(UUID id, String actorId, String actorEmail) {
        return setBenefitStatus(id, "inactive", actorId, actorEmail);
    }

    /** Re-enables a previously-deactivated benefit. Counterpart to {@link #deactivateBenefit}. */
    @Transactional
    public Mono<SchemeBenefit> activateBenefit(UUID id, String actorId, String actorEmail) {
        return setBenefitStatus(id, "active", actorId, actorEmail);
    }

    private Mono<SchemeBenefit> setBenefitStatus(UUID id, String status, String actorId, String actorEmail) {
        return schemeBenefitRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheme benefit not found: " + id)))
            .flatMap(existing -> {
                String previousStatus = existing.getStatus();
                existing.setStatus(status);
                existing.setUpdatedAt(Instant.now());
                return schemeBenefitRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "SchemeBenefit", saved.getId().toString(), "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    // deleteBenefit was removed deliberately. Hard-delete of a benefit can
    // strand references in claims / invoices / tariffs; use deactivateBenefit
    // instead. Schemes and age groups have always followed the same rule —
    // soft-delete via the status column. If a one-off cleanup of an
    // erroneously-created row is ever required, it should be done through
    // a controlled migration, not an end-user API.

    public Flux<AgeGroup> findAgeGroupsBySchemeId(UUID schemeId) {
        return ageGroupRepository.findBySchemeId(schemeId);
    }

    @Transactional
    public Mono<AgeGroup> createAgeGroup(CreateAgeGroupRequest request, String actorId, String actorEmail) {
        return schemeRepository.findById(request.schemeId())
            .switchIfEmpty(Mono.error(new SchemeNotFoundException(request.schemeId())))
            .flatMap(scheme -> {
                if (!PersonCentricLines.isPersonCentric(scheme.getInsuranceLine())) {
                    return Mono.<AgeGroup>error(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Age groups do not apply to " + scheme.getInsuranceLine()
                            + " schemes — they are asset-centric and have no member-age dimension."));
                }
                String resolvedCurrency = resolveChildCurrency(scheme, request.currencyCode());

                var ageGroup = new AgeGroup();
                // @Id left null — see comment on create(): pre-setting the
                // ID makes save() take the UPDATE path and fail.
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
                return publishAudit(tenantId, "AgeGroup", saved.getId().toString(), "CREATE", actorId, actorEmail,
                        null,
                        Map.of("name", saved.getName(), "minAge", saved.getMinAge().toString(),
                               "maxAge", saved.getMaxAge().toString(),
                               "schemeId", saved.getSchemeId().toString()))
                    .thenReturn(saved);
            }));
    }

    public Mono<AgeGroup> findAgeGroupById(UUID id) {
        return ageGroupRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Age group not found: " + id)));
    }

    @Transactional
    public Mono<AgeGroup> updateAgeGroup(UUID id, UpdateAgeGroupRequest request, String actorId, String actorEmail) {
        return ageGroupRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Age group not found: " + id)))
            .flatMap(existing -> schemeRepository.findById(existing.getSchemeId())
                .switchIfEmpty(Mono.error(new SchemeNotFoundException(existing.getSchemeId())))
                .flatMap(scheme -> {
                    String previousName = existing.getName();
                    String resolvedCurrency = resolveChildCurrency(scheme, request.currencyCode());

                    existing.setName(request.name());
                    existing.setMinAge(request.minAge());
                    existing.setMaxAge(request.maxAge());
                    existing.setContributionAmount(request.contributionAmount());
                    existing.setCurrencyCode(resolvedCurrency);

                    return ageGroupRepository.save(existing)
                        .flatMap(saved -> Mono.deferContextual(ctx -> {
                            String tenantId = TenantContext.get(ctx);
                            return publishAudit(tenantId, "AgeGroup", saved.getId().toString(), "UPDATE", actorId, actorEmail,
                                    Map.of("name", previousName),
                                    Map.of("name", saved.getName(),
                                           "minAge", saved.getMinAge().toString(),
                                           "maxAge", saved.getMaxAge().toString(),
                                           "contributionAmount", saved.getContributionAmount().toString()))
                                .thenReturn(saved);
                        }));
                }));
    }

    /** Soft-delete an age group by flipping its status to "inactive". */
    @Transactional
    public Mono<AgeGroup> deactivateAgeGroup(UUID id, String actorId, String actorEmail) {
        return setAgeGroupStatus(id, "inactive", actorId, actorEmail);
    }

    /** Re-enables a previously-deactivated age group. Counterpart to {@link #deactivateAgeGroup}. */
    @Transactional
    public Mono<AgeGroup> activateAgeGroup(UUID id, String actorId, String actorEmail) {
        return setAgeGroupStatus(id, "active", actorId, actorEmail);
    }

    private Mono<AgeGroup> setAgeGroupStatus(UUID id, String status, String actorId, String actorEmail) {
        return ageGroupRepository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Age group not found: " + id)))
            .flatMap(existing -> {
                String previousStatus = existing.getStatus();
                existing.setStatus(status);
                return ageGroupRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "AgeGroup", saved.getId().toString(), "UPDATE", actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

    private static String normalizeCurrency(String code) {
        if (code == null || code.isBlank()) {
            return "USD";
        }
        return code.toUpperCase();
    }

    /**
     * Normalizes an incoming insurance line to upper-case and validates it is
     * one of the eight known catalogue values. A blank/null value falls back
     * to HEALTH — matching the V021 backfill default and the existing
     * `medical_aid` scheme_type default.
     */
    private static String resolveInsuranceLine(String line) {
        if (line == null || line.isBlank()) {
            return "HEALTH";
        }
        String normalized = line.toUpperCase();
        if (!PersonCentricLines.isKnown(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Unknown insurance line '" + line + "'. Must be one of "
                    + PersonCentricLines.ALL_LINES + ".");
        }
        return normalized;
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
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
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
}
