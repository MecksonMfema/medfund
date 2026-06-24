package com.medfund.claims.service;

import com.medfund.claims.dto.CreateTariffCodeRequest;
import com.medfund.claims.dto.CreateTariffScheduleRequest;
import com.medfund.claims.entity.TariffCode;
import com.medfund.claims.entity.TariffModifier;
import com.medfund.claims.entity.TariffSchedule;
import com.medfund.claims.exception.TariffNotFoundException;
import com.medfund.claims.repository.TariffCodeRepository;
import com.medfund.claims.repository.TariffModifierRepository;
import com.medfund.claims.repository.TariffScheduleRepository;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TariffService {

    private static final Logger log = LoggerFactory.getLogger(TariffService.class);

    private final TariffScheduleRepository tariffScheduleRepository;
    private final TariffCodeRepository tariffCodeRepository;
    private final TariffModifierRepository tariffModifierRepository;
    private final AuditPublisher auditPublisher;

    public TariffService(TariffScheduleRepository tariffScheduleRepository,
                         TariffCodeRepository tariffCodeRepository,
                         TariffModifierRepository tariffModifierRepository,
                         AuditPublisher auditPublisher) {
        this.tariffScheduleRepository = tariffScheduleRepository;
        this.tariffCodeRepository = tariffCodeRepository;
        this.tariffModifierRepository = tariffModifierRepository;
        this.auditPublisher = auditPublisher;
    }

    public Flux<TariffSchedule> findAllSchedules() {
        return tariffScheduleRepository.findAllOrderByEffectiveDateDesc();
    }

    public Mono<TariffSchedule> findScheduleById(UUID id) {
        return tariffScheduleRepository.findById(id)
            .switchIfEmpty(Mono.error(new TariffNotFoundException("Tariff schedule not found: " + id)));
    }

    @Transactional
    public Mono<TariffSchedule> createSchedule(CreateTariffScheduleRequest request,
                                                 String actorId, String actorEmail) {
        var schedule = new TariffSchedule();
        schedule.setName(request.name());
        schedule.setEffectiveDate(request.effectiveDate());
        schedule.setEndDate(request.endDate());
        schedule.setSource(request.source());
        schedule.setStatus("ACTIVE");
        schedule.setCreatedAt(Instant.now());

        return tariffScheduleRepository.save(schedule)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "TariffSchedule", saved.getId().toString(),
                        saved.getName(), "CREATE",
                        actorId, actorEmail,
                        null,
                        Map.of("name", saved.getName(), "status", saved.getStatus()))
                    .thenReturn(saved);
            }));
    }

    public Flux<TariffCode> findCodesByScheduleId(UUID scheduleId) {
        return tariffCodeRepository.findByScheduleId(scheduleId);
    }

    public Mono<TariffCode> findCodeByCode(String code) {
        return tariffCodeRepository.findByCode(code)
            .switchIfEmpty(Mono.error(new TariffNotFoundException("Tariff code not found: " + code)));
    }

    public Flux<TariffCode> searchCodes(String query) {
        return tariffCodeRepository.searchByCodeOrDescription(query);
    }

    @Transactional
    public Mono<TariffCode> createCode(CreateTariffCodeRequest request,
                                         String actorId, String actorEmail) {
        var tariffCode = new TariffCode();
        tariffCode.setScheduleId(request.scheduleId());
        tariffCode.setCode(request.code());
        tariffCode.setDescription(request.description());
        tariffCode.setCategory(request.category());
        tariffCode.setUnitPrice(request.unitPrice());
        tariffCode.setCurrencyCode(request.currencyCode());
        tariffCode.setRequiresPreAuth(request.requiresPreAuth() != null ? request.requiresPreAuth() : false);
        tariffCode.setCreatedAt(Instant.now());

        return tariffCodeRepository.save(tariffCode)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "TariffCode", saved.getId().toString(),
                        saved.getCode(), "CREATE",
                        actorId, actorEmail,
                        null,
                        Map.of("code", saved.getCode(), "description", saved.getDescription(),
                               "unitPrice", saved.getUnitPrice().toString()))
                    .thenReturn(saved);
            }));
    }

    public Flux<TariffModifier> findAllModifiers() {
        return tariffModifierRepository.findAllActive();
    }

    public Mono<TariffModifier> findModifierByCode(String code) {
        return tariffModifierRepository.findByCode(code)
            .switchIfEmpty(Mono.error(new TariffNotFoundException("Tariff modifier not found: " + code)));
    }

    /**
     * Applies a list of modifier codes to a base price.
     * PERCENTAGE modifiers multiply the price (e.g., adjustmentValue=1.5 means 150%).
     * FIXED modifiers add to the price.
     *
     * @param basePrice     the starting price
     * @param modifierCodes list of modifier code strings to apply
     * @return the adjusted price after all modifiers
     */
    public Mono<BigDecimal> applyModifiers(BigDecimal basePrice, List<String> modifierCodes) {
        if (modifierCodes == null || modifierCodes.isEmpty()) {
            return Mono.just(basePrice);
        }

        return Flux.fromIterable(modifierCodes)
            .flatMap(code -> tariffModifierRepository.findByCode(code))
            .collectList()
            .map(modifiers -> {
                BigDecimal result = basePrice;
                for (TariffModifier modifier : modifiers) {
                    if ("PERCENTAGE".equalsIgnoreCase(modifier.getAdjustmentType())) {
                        result = result.multiply(modifier.getAdjustmentValue()).setScale(2, RoundingMode.HALF_UP);
                    } else if ("FIXED".equalsIgnoreCase(modifier.getAdjustmentType())) {
                        result = result.add(modifier.getAdjustmentValue()).setScale(2, RoundingMode.HALF_UP);
                    }
                }
                return result;
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
            new String[]{"name", "status", "code", "unitPrice"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }

    public Mono<Void> activateSchedulesByDate() {
        return tariffScheduleRepository.findByStatus("draft")
            .filter(s -> s.getEffectiveDate() != null && !s.getEffectiveDate().isAfter(java.time.LocalDate.now()))
            .flatMap(s -> {
                s.setStatus("active");
                return tariffScheduleRepository.save(s);
            })
            .then();
    }

    public Mono<Void> deactivateExpiredSchedules() {
        return tariffScheduleRepository.findByStatus("active")
            .filter(s -> s.getEndDate() != null && s.getEndDate().isBefore(java.time.LocalDate.now()))
            .flatMap(s -> {
                s.setStatus("expired");
                return tariffScheduleRepository.save(s);
            })
            .then();
    }
}
