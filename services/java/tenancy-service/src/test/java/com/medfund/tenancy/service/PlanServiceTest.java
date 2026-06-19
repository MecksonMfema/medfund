package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.CreatePlanRequest;
import com.medfund.tenancy.entity.Plan;
import com.medfund.tenancy.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @InjectMocks
    private PlanService planService;

    @Test
    void findAllActive_returnsActivePlans() {
        Plan plan1 = createTestPlan();
        Plan plan2 = createTestPlan();
        plan2.setName("Premium");
        plan2.setPrice(new BigDecimal("199.99"));

        when(planRepository.findAllActive()).thenReturn(Flux.just(plan1, plan2));

        StepVerifier.create(planService.findAllActive())
                .expectNext(plan1)
                .expectNext(plan2)
                .verifyComplete();
    }

    @Test
    void findById_existingPlan_returnsPlan() {
        Plan plan = createTestPlan();
        when(planRepository.findById(plan.getId())).thenReturn(Mono.just(plan));

        StepVerifier.create(planService.findById(plan.getId()))
                .assertNext(result -> {
                    assertThat(result.getId()).isEqualTo(plan.getId());
                    assertThat(result.getName()).isEqualTo("Basic");
                    assertThat(result.getPrice()).isEqualByComparingTo(new BigDecimal("99.99"));
                    assertThat(result.getIsActive()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void create_validRequest_createsPlan() {
        var request = new CreatePlanRequest(
                "Basic", 100, 50, 10, null,
                new BigDecimal("99.99"), "USD", "MONTHLY"
        );

        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(planService.create(request, "actor-123", "actor@test.example"))
                .assertNext(plan -> {
                    assertThat(plan.getName()).isEqualTo("Basic");
                    assertThat(plan.getMaxMembers()).isEqualTo(100);
                    assertThat(plan.getPrice()).isEqualByComparingTo(new BigDecimal("99.99"));
                    assertThat(plan.getCurrencyCode()).isEqualTo("USD");
                    assertThat(plan.getBillingCycle()).isEqualTo("MONTHLY");
                    assertThat(plan.getIsActive()).isTrue();
                    assertThat(plan.getId()).isNotNull();
                    assertThat(plan.getCreatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(planRepository).save(any(Plan.class));
        verify(auditPublisher).publish(any(AuditEvent.class));
    }

    @Test
    void deactivate_existingPlan_setsInactiveFalse() {
        Plan plan = createTestPlan();
        when(planRepository.findById(plan.getId())).thenReturn(Mono.just(plan));
        when(planRepository.save(any(Plan.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(planService.deactivate(plan.getId(), "actor-123", "actor@test.example"))
                .assertNext(result -> {
                    assertThat(result.getIsActive()).isFalse();
                    assertThat(result.getName()).isEqualTo("Basic");
                })
                .verifyComplete();

        verify(planRepository).save(any(Plan.class));
        verify(auditPublisher).publish(any(AuditEvent.class));
    }

    private Plan createTestPlan() {
        var plan = new Plan();
        plan.setId(UUID.randomUUID());
        plan.setName("Basic");
        plan.setMaxMembers(100);
        plan.setPrice(new BigDecimal("99.99"));
        plan.setCurrencyCode("USD");
        plan.setBillingCycle("MONTHLY");
        plan.setIsActive(true);
        plan.setCreatedAt(Instant.now());
        return plan;
    }
}
