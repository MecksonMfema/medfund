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
        var req = new UpsertDunningConfigRequest(
                7, 30, 90, true, false,
                null, null, null, null);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.empty());
        when(dunningRepo.save(any(DunningConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.upsertDunningConfig(req, "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isEqualTo(DunningConfig.SINGLETON_ID);
                    assertThat(saved.getGraceDays()).isEqualTo(7);
                    assertThat(saved.getSuspensionDays()).isEqualTo(30);
                    assertThat(saved.getDeactivationDays()).isEqualTo(90);
                    assertThat(saved.getAutoSuspend()).isTrue();
                    assertThat(saved.getAutoWriteOff()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void upsertDunning_persistsAllReminderFields() {
        // V044 knobs must round-trip. Guards against a rename or dropped
        // field in the DTO → entity mapping — that failure would silently
        // leave the reminder loop misconfigured.
        var req = new UpsertDunningConfigRequest(
                7, 30, 90, true, false,
                true,  // autoRemind
                7,     // reminderLeadDays
                3,     // reminderIntervalDays
                true); // reminderContinuePastSuspension
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.empty());
        when(dunningRepo.save(any(DunningConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        StepVerifier.create(service.upsertDunningConfig(req, "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getAutoRemind()).isTrue();
                    assertThat(saved.getReminderLeadDays()).isEqualTo(7);
                    assertThat(saved.getReminderIntervalDays()).isEqualTo(3);
                    assertThat(saved.getReminderContinuePastSuspension()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void upsertDunning_partialUpdate_leavesUnsetFieldsAlone() {
        // Only autoRemind set → other reminder fields stay at their
        // previously-persisted values. This is the operator "toggle a
        // single switch without re-typing the whole form" flow.
        DunningConfig existing = new DunningConfig();
        existing.setId(DunningConfig.SINGLETON_ID);
        existing.setGraceDays(7);
        existing.setSuspensionDays(30);
        existing.setDeactivationDays(90);
        existing.setAutoRemind(false);
        existing.setReminderLeadDays(10);
        existing.setReminderIntervalDays(5);
        existing.setReminderContinuePastSuspension(false);
        when(dunningRepo.findById(DunningConfig.SINGLETON_ID)).thenReturn(Mono.just(existing));
        when(dunningRepo.save(any(DunningConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());

        var req = new UpsertDunningConfigRequest(
                null, null, null, null, null,
                true,   // autoRemind — only this one changes
                null, null, null);

        StepVerifier.create(service.upsertDunningConfig(req, "actor", "actor@test"))
                .assertNext(saved -> {
                    assertThat(saved.getAutoRemind()).isTrue();  // flipped
                    assertThat(saved.getReminderLeadDays()).isEqualTo(10);  // preserved
                    assertThat(saved.getReminderIntervalDays()).isEqualTo(5);
                    assertThat(saved.getReminderContinuePastSuspension()).isFalse();
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
