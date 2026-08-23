package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.dto.UpdateRateCardRequest;
import com.medfund.finance.producer.entity.CommissionRateCard;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionRateCardServiceTest {

    @Mock CommissionRateCardRepository repository;
    @Mock AuditPublisher auditPublisher;
    @InjectMocks CommissionRateCardService service;

    @Test
    void create_snapsEffectiveFromToFirstOfMonth_andEffectiveToToLastOfMonth() {
        var req = new CreateRateCardRequest("Std HEALTH", "HEALTH", "DIRECT",
                new BigDecimal("10.00"), 90,
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 9, 10));
        when(repository.save(any())).thenAnswer(inv -> {
            CommissionRateCard c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(req, "sys", "admin@test.example")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .assertNext(r -> {
                    assertThat(r.effectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
                    assertThat(r.effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
                })
                .verifyComplete();
    }

    @Test
    void create_openEndedPeriod_leavesEffectiveToNull() {
        var req = new CreateRateCardRequest("Open HEALTH", "HEALTH", null,
                new BigDecimal("5.00"), null,
                LocalDate.of(2026, 3, 15), null);
        when(repository.save(any())).thenAnswer(inv -> {
            CommissionRateCard c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.create(req, "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")))
                .assertNext(r -> assertThat(r.effectiveTo()).isNull())
                .verifyComplete();
    }

    @Test
    void create_effectiveToBeforeFrom_rejects() {
        var req = new CreateRateCardRequest("Bad", "HEALTH", null,
                new BigDecimal("5.00"), null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 30));
        assertThatThrownBy(() -> service.create(req, "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "tenant-a")).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_publishesAudit_withNameAsEntityName() {
        var req = new CreateRateCardRequest("Health Std", "HEALTH", null,
                new BigDecimal("8.5"), 30, LocalDate.of(2026, 1, 1), null);
        when(repository.save(any())).thenAnswer(inv -> {
            CommissionRateCard c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        service.create(req, "sys", "admin@t").contextWrite(ctx -> ctx.put("TENANT_ID", "T")).block();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(cap.capture());
        assertThat(cap.getValue().entityType()).isEqualTo("CommissionRateCard");
        assertThat(cap.getValue().entityName()).isEqualTo("Health Std");
        assertThat(cap.getValue().action()).isEqualTo("CREATE");
    }

    @Test
    void update_missing_errors() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());
        UpdateRateCardRequest req = new UpdateRateCardRequest("x", "HEALTH", null,
                new BigDecimal("1"), null, LocalDate.now(), null, true);

        StepVerifier.create(service.update(id, req, "sys", "a@b"))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    void deactivate_snapsEffectiveToToLastDayOfMonth_andEmitsAudit() {
        CommissionRateCard existing = seed("Health Std");
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));
        when(repository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(
                service.deactivate(existing.getId(), "sys", "admin@t")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .assertNext(r -> {
                    assertThat(r.active()).isFalse();
                    assertThat(r.effectiveTo())
                            .isEqualTo(LocalDate.now().with(TemporalAdjusters.lastDayOfMonth()));
                })
                .verifyComplete();
    }

    @Test
    void deactivate_alreadyInactive_returnsExistingWithoutAudit() {
        CommissionRateCard existing = seed("Health Std");
        existing.setActive(false);
        when(repository.findById(existing.getId())).thenReturn(Mono.just(existing));

        StepVerifier.create(
                service.deactivate(existing.getId(), "sys", "a@b")
                        .contextWrite(ctx -> ctx.put("TENANT_ID", "T")))
                .assertNext(r -> assertThat(r.active()).isFalse())
                .verifyComplete();
    }

    @Test
    void findApplicable_delegates() {
        CommissionRateCard c = seed("Health Std");
        when(repository.findApplicable("HEALTH", "DIRECT", LocalDate.of(2026, 6, 1)))
                .thenReturn(Mono.just(c));

        StepVerifier.create(service.findApplicable("HEALTH", "DIRECT", LocalDate.of(2026, 6, 1)))
                .expectNextCount(1)
                .verifyComplete();
    }

    private CommissionRateCard seed(String name) {
        CommissionRateCard c = new CommissionRateCard();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setInsuranceLine("HEALTH");
        c.setBaseRatePct(new BigDecimal("10.00"));
        c.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        c.setActive(true);
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }
}
