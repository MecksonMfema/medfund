package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.CreatePlanRequest;
import com.medfund.tenancy.entity.Plan;
import com.medfund.tenancy.repository.PlanRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final AuditPublisher auditPublisher;

    public PlanService(PlanRepository planRepository, AuditPublisher auditPublisher) {
        this.planRepository = planRepository;
        this.auditPublisher = auditPublisher;
    }

    public Flux<Plan> findAllActive() {
        return planRepository.findAllActive();
    }

    public Mono<Plan> findById(UUID id) {
        return planRepository.findById(id);
    }

    public Mono<Plan> create(CreatePlanRequest request, String actorId, String actorEmail) {
        Plan plan = new Plan();
        plan.setId(UUID.randomUUID());
        plan.setName(request.name());
        plan.setMaxMembers(request.maxMembers());
        plan.setMaxProviders(request.maxProviders());
        plan.setMaxStorageGb(request.maxStorageGb());
        plan.setFeatures(request.features() != null ? request.features() : "{}");
        plan.setPrice(request.price());
        plan.setCurrencyCode(request.currencyCode());
        plan.setBillingCycle(request.billingCycle());
        plan.setIsActive(true);
        plan.setCreatedAt(Instant.now());

        return planRepository.save(plan)
                .flatMap(saved -> {
                    var event = AuditEvent.create(
                            "platform", "Plan", saved.getId().toString(), saved.getName(),
                            "CREATE", actorId, actorEmail,
                            null,
                            Map.<String, Object>of("name", saved.getName()),
                            new String[]{"name", "price", "features"},
                            UUID.randomUUID().toString()
                    );
                    return auditPublisher.publish(event).thenReturn(saved);
                });
    }

    public Mono<Plan> deactivate(UUID id, String actorId, String actorEmail) {
        return planRepository.findById(id)
                .flatMap(plan -> {
                    plan.setIsActive(false);
                    return planRepository.save(plan)
                            .flatMap(saved -> {
                                var event = AuditEvent.create(
                                        "platform", "Plan", saved.getId().toString(), saved.getName(),
                                        "UPDATE", actorId, actorEmail,
                                        Map.<String, Object>of("is_active", true),
                                        Map.<String, Object>of("is_active", false),
                                        new String[]{"is_active"},
                                        UUID.randomUUID().toString()
                                );
                                return auditPublisher.publish(event).thenReturn(saved);
                            });
                });
    }
}
