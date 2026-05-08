package com.medfund.contributions.service;

import com.medfund.contributions.dto.UpsertBenefitTypeRequest;
import com.medfund.contributions.dto.UpsertBillingCycleConfigRequest;
import com.medfund.contributions.dto.UpsertDunningConfigRequest;
import com.medfund.contributions.entity.BenefitType;
import com.medfund.contributions.entity.BillingCycleConfig;
import com.medfund.contributions.entity.DunningConfig;
import com.medfund.contributions.repository.BenefitTypeRepository;
import com.medfund.contributions.repository.BillingCycleConfigRepository;
import com.medfund.contributions.repository.DunningConfigRepository;
import com.medfund.contributions.repository.PaymentMethodRepository;
import com.medfund.contributions.repository.TransactionTypeRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingCatalogueServiceTest {

    @Mock private BenefitTypeRepository benefitRepo;
    @Mock private PaymentMethodRepository paymentRepo;
    @Mock private TransactionTypeRepository transactionRepo;
    @Mock private DunningConfigRepository dunningRepo;
    @Mock private BillingCycleConfigRepository cycleRepo;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;

    private BillingCatalogueService service;

    @BeforeEach
    void setup() {
        service = new BillingCatalogueService(
                benefitRepo, paymentRepo, transactionRepo, dunningRepo, cycleRepo,
                r2dbcTemplate, auditPublisher);
    }

    @Test
    void createBenefitType_persistsAndAudits() {
        var req = new UpsertBenefitTypeRequest("WELLNESS", "Wellness", null, 100, true);
        when(r2dbcTemplate.insert(any(BenefitType.class)))
                .thenAnswer(inv -> {
                    BenefitType b = inv.getArgument(0);
                    b.setId(UUID.randomUUID());
                    return Mono.just(b);
                });
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.createBenefitType(req, "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getCode()).isEqualTo("WELLNESS");
                    assertThat(saved.getLabel()).isEqualTo("Wellness");
                    assertThat(saved.getSortOrder()).isEqualTo(100);
                    assertThat(saved.getIsActive()).isTrue();
                })
                .verifyComplete();

        verify(auditPublisher).publish(any(AuditEvent.class));
    }

    @Test
    void createBenefitType_duplicateCode_returnsIllegalArgument() {
        var req = new UpsertBenefitTypeRequest("OUTPATIENT", "Outpatient", null, 0, true);
        when(r2dbcTemplate.insert(any(BenefitType.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("benefit_types_code_key")));

        StepVerifier.create(service.createBenefitType(req, "actor", "actor@test"))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void upsertDunning_initialisesSingleton() {
        var req = new UpsertDunningConfigRequest(7, 30, 90, true, false);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.empty());
        when(dunningRepo.save(any(DunningConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.upsertDunningConfig(req, "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isEqualTo(DunningConfig.SINGLETON_ID);
                    assertThat(saved.getGraceDays()).isEqualTo(7);
                    assertThat(saved.getSuspensionDays()).isEqualTo(30);
                    assertThat(saved.getWriteOffDays()).isEqualTo(90);
                    assertThat(saved.getAutoSuspend()).isTrue();
                    assertThat(saved.getAutoWriteOff()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void upsertCycle_updatesExistingSingleton() {
        BillingCycleConfig existing = new BillingCycleConfig();
        existing.setId(BillingCycleConfig.SINGLETON_ID);
        existing.setFrequency("MONTHLY");
        existing.setDayOfMonth((short) 1);
        existing.setCommitCooldownHours((short) 3);

        var req = new UpsertBillingCycleConfigRequest("QUARTERLY", (short) 15, true, (short) 12);

        when(cycleRepo.findById(BillingCycleConfig.SINGLETON_ID)).thenReturn(Mono.just(existing));
        when(cycleRepo.save(any(BillingCycleConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.upsertBillingCycleConfig(req, "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getFrequency()).isEqualTo("QUARTERLY");
                    assertThat(saved.getDayOfMonth()).isEqualTo((short) 15);
                    assertThat(saved.getAutoGenerate()).isTrue();
                    assertThat(saved.getCommitCooldownHours()).isEqualTo((short) 12);
                })
                .verifyComplete();
    }
}
