package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportCadence;
import com.medfund.tenancy.dto.CreateTenantReportScheduleRequest;
import com.medfund.tenancy.dto.UpdateTenantReportScheduleRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantReportSchedule;
import com.medfund.tenancy.repository.TenantReportScheduleRecipientRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRepository;
import com.medfund.tenancy.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantReportScheduleServiceTest {

    @Mock private TenantReportScheduleRepository repository;
    @Mock private TenantReportScheduleRecipientRepository recipientRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;
    @Captor private ArgumentCaptor<AuditEvent> auditCaptor;

    @InjectMocks private TenantReportScheduleService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    @BeforeEach
    void stubTenantAndAudit() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setSlug("acme");
        lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(t));
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    @Test
    void create_whitelistedKey_persistsRow_emitsAudit() {
        var req = new CreateTenantReportScheduleRequest(
                "COMMISSION_STATEMENT", true, ReportCadence.MONTHLY,
                8, null, 1, null);
        when(r2dbcTemplate.insert(any(TenantReportSchedule.class))).thenAnswer(inv -> {
            TenantReportSchedule saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.create(TENANT_ID, req, ACTOR, "admin@acme"))
                .assertNext(response -> {
                    assertThat(response.tenantId()).isEqualTo(TENANT_ID);
                    assertThat(response.reportKey()).isEqualTo("COMMISSION_STATEMENT");
                    assertThat(response.reportLabel()).isEqualTo("Commission statement");
                    assertThat(response.enabled()).isTrue();
                    assertThat(response.cadence()).isEqualTo("MONTHLY");
                    assertThat(response.dayOfMonth()).isEqualTo(1);
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityName())
                .contains("Commission statement").contains("Monthly").contains("acme");
        assertThat(ev.actorEmail()).isEqualTo("admin@acme");
    }

    @Test
    void create_nonWhitelistedKey_isRejected() {
        var req = new CreateTenantReportScheduleRequest(
                "IPEC_QUARTERLY_RETURN", true, ReportCadence.QUARTERLY,
                8, null, 1, null);

        StepVerifier.create(service.create(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not eligible for scheduling"))
                .verify();

        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }

    @Test
    void create_unknownKey_isRejected() {
        var req = new CreateTenantReportScheduleRequest(
                "NONSENSE_KEY", true, ReportCadence.MONTHLY,
                8, null, 1, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req, ACTOR, "admin@acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown report key");
    }

    @Test
    void create_weeklyWithoutDayOfWeek_isRejected() {
        var req = new CreateTenantReportScheduleRequest(
                "COMMISSION_STATEMENT", true, ReportCadence.WEEKLY,
                8, null, null, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req, ACTOR, "admin@acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dayOfWeek is required");
    }

    @Test
    void create_monthlyWithoutDayOfMonth_isRejected() {
        var req = new CreateTenantReportScheduleRequest(
                "COMMISSION_STATEMENT", true, ReportCadence.MONTHLY,
                8, null, null, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req, ACTOR, "admin@acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dayOfMonth is required");
    }

    @Test
    void create_eventDrivenCadence_isRejected() {
        var req = new CreateTenantReportScheduleRequest(
                "COMMISSION_STATEMENT", true, ReportCadence.EVENT_DRIVEN,
                8, null, 1, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req, ACTOR, "admin@acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EVENT_DRIVEN");
    }

    @Test
    void create_missingActor_rejected() {
        var req = new CreateTenantReportScheduleRequest(
                "COMMISSION_STATEMENT", true, ReportCadence.MONTHLY,
                8, null, 1, null);

        StepVerifier.create(service.create(TENANT_ID, req, "", "admin@acme"))
                .expectErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("actorId and actorEmail are required"))
                .verify();
    }

    @Test
    void update_patchShaped_onlyOverwritesProvidedFields_emitsAuditWithChanges() {
        UUID scheduleId = UUID.randomUUID();
        TenantReportSchedule existing = existingSchedule(scheduleId, "COMMISSION_STATEMENT",
                "MONTHLY", 8, null, 1);
        when(repository.findById(scheduleId)).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantReportSchedule.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(recipientRepository.findByScheduleIdOrderByEmailAsc(scheduleId)).thenReturn(Flux.empty());

        var req = new UpdateTenantReportScheduleRequest(
                null, null, 15, null, null, "USD");

        StepVerifier.create(service.update(TENANT_ID, scheduleId, req, ACTOR, "admin@acme"))
                .assertNext(resp -> {
                    assertThat(resp.hourOfDay()).isEqualTo(15);
                    assertThat(resp.dayOfMonth()).isEqualTo(1);              // unchanged
                    assertThat(resp.cadence()).isEqualTo("MONTHLY");         // unchanged
                    assertThat(resp.reportingCurrency()).isEqualTo("USD");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(ev.changedFields()).contains("hourOfDay", "reportingCurrency");
        assertThat(ev.changedFields()).doesNotContain("cadence", "dayOfMonth");
    }

    @Test
    void update_crossTenant_isRejected() {
        UUID scheduleId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        TenantReportSchedule existing = existingSchedule(scheduleId, "LOSS_RATIO",
                "MONTHLY", 8, null, 1);
        existing.setTenantId(otherTenant);
        when(repository.findById(scheduleId)).thenReturn(Mono.just(existing));

        var req = new UpdateTenantReportScheduleRequest(false, null, null, null, null, null);

        StepVerifier.create(service.update(TENANT_ID, scheduleId, req, ACTOR, "admin@acme"))
                .expectErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("does not belong to tenant"))
                .verify();
    }

    @Test
    void cascadeDisable_flipsEveryEnabledRow_emitsOneAuditPerRow_returnsCount() {
        TenantReportSchedule row1 = existingSchedule(UUID.randomUUID(), "COMMISSION_STATEMENT",
                "MONTHLY", 8, null, 1);
        TenantReportSchedule row2 = existingSchedule(UUID.randomUUID(), "COMMISSION_STATEMENT",
                "MONTHLY", 8, null, 15);
        when(repository.findByTenantIdAndReportKeyAndEnabledIsTrue(TENANT_ID, "COMMISSION_STATEMENT"))
                .thenReturn(Flux.just(row1, row2));
        when(repository.cascadeDisable(eq(TENANT_ID), eq("COMMISSION_STATEMENT"), any(), anyString()))
                .thenReturn(Mono.just(2));

        StepVerifier.create(service.cascadeDisable(TENANT_ID, "COMMISSION_STATEMENT", ACTOR, "admin@acme"))
                .expectNext(2)
                .verifyComplete();

        verify(auditPublisher, org.mockito.Mockito.times(2)).publish(auditCaptor.capture());
        List<AuditEvent> events = auditCaptor.getAllValues();
        assertThat(events).allSatisfy(ev -> {
            assertThat(ev.action()).isEqualTo("CASCADE_DISABLE");
            assertThat(ev.actorEmail()).isEqualTo("admin@acme");
        });
    }

    @Test
    void cascadeDisable_noMatchingRows_isNoOp() {
        when(repository.findByTenantIdAndReportKeyAndEnabledIsTrue(TENANT_ID, "LOSS_RATIO"))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.cascadeDisable(TENANT_ID, "LOSS_RATIO", ACTOR, "admin@acme"))
                .expectNext(0)
                .verifyComplete();

        verify(auditPublisher, never()).publish(any(AuditEvent.class));
        verify(repository, never()).cascadeDisable(any(), anyString(), any(), anyString());
    }

    private TenantReportSchedule existingSchedule(UUID id, String reportKey, String cadence,
                                                  int hourOfDay, Integer dayOfWeek, Integer dayOfMonth) {
        TenantReportSchedule row = new TenantReportSchedule();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setReportKey(reportKey);
        row.setEnabled(true);
        row.setCadence(cadence);
        row.setHourOfDay(hourOfDay);
        row.setDayOfWeek(dayOfWeek);
        row.setDayOfMonth(dayOfMonth);
        row.setCreatedByActorId(UUID.randomUUID());
        row.setCreatedByActorEmail("seed@medfund");
        row.setUpdatedByActorId(UUID.randomUUID());
        row.setUpdatedByActorEmail("seed@medfund");
        return row;
    }
}
