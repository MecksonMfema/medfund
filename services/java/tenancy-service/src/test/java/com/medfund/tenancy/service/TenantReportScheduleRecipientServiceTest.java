package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantReportScheduleRecipientRequest;
import com.medfund.tenancy.dto.UpdateTenantReportScheduleRecipientRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantReportSchedule;
import com.medfund.tenancy.entity.TenantReportScheduleRecipient;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRecipientRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantReportScheduleRecipientServiceTest {

    @Mock private TenantReportScheduleRecipientRepository repository;
    @Mock private TenantReportScheduleRepository scheduleRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private R2dbcEntityTemplate r2dbcTemplate;
    @Mock private AuditPublisher auditPublisher;
    @Captor private ArgumentCaptor<AuditEvent> auditCaptor;

    @InjectMocks private TenantReportScheduleRecipientService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID SCHEDULE_ID = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    @BeforeEach
    void stubTenantAndAudit() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setSlug("acme");
        lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(t));
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        TenantReportSchedule schedule = new TenantReportSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setTenantId(TENANT_ID);
        schedule.setReportKey("COMMISSION_STATEMENT");
        schedule.setCadence("MONTHLY");
        lenient().when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Mono.just(schedule));
    }

    @Test
    void add_lowercasesEmail_publishesAudit() {
        var req = new AddTenantReportScheduleRecipientRequest(
                "Compliance@Acme.COM", "Compliance Team", null);
        when(r2dbcTemplate.insert(any(TenantReportScheduleRecipient.class))).thenAnswer(inv -> {
            TenantReportScheduleRecipient saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, SCHEDULE_ID, req, ACTOR, "admin@acme"))
                .assertNext(row -> {
                    assertThat(row.getScheduleId()).isEqualTo(SCHEDULE_ID);
                    assertThat(row.getEmail()).isEqualTo("compliance@acme.com");
                    assertThat(row.getDisplayName()).isEqualTo("Compliance Team");
                    assertThat(row.getIsActive()).isTrue();
                    assertThat(row.getActorEmail()).isEqualTo("admin@acme");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityName())
                .contains("compliance@acme.com").contains("COMMISSION_STATEMENT").contains("acme");
    }

    @Test
    void unsubscribeByToken_flipsIsActiveFalse_emitsUnsubscribeAudit() {
        UUID recipientId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        TenantReportScheduleRecipient existing = new TenantReportScheduleRecipient();
        existing.setId(recipientId);
        existing.setScheduleId(SCHEDULE_ID);
        existing.setEmail("recipient@acme.com");
        existing.setIsActive(true);
        existing.setUnsubscribeToken(token);
        when(repository.findByUnsubscribeToken(token)).thenReturn(Mono.just(existing));
        when(repository.save(any(TenantReportScheduleRecipient.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.unsubscribeByToken(token, "too many emails"))
                .assertNext(saved -> {
                    assertThat(saved.getIsActive()).isFalse();
                    assertThat(saved.getActorId()).isNull();
                    assertThat(saved.getActorEmail()).isEqualTo("system+unsubscribe@medfund");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("UNSUBSCRIBE");
        assertThat(ev.actorId()).isEqualTo("system:unsubscribe");
        assertThat(ev.actorEmail()).isEqualTo("system+unsubscribe@medfund");
    }

    @Test
    void unsubscribeByToken_alreadyInactive_isIdempotentNoAudit() {
        UUID token = UUID.randomUUID();
        TenantReportScheduleRecipient existing = new TenantReportScheduleRecipient();
        existing.setId(UUID.randomUUID());
        existing.setScheduleId(SCHEDULE_ID);
        existing.setEmail("recipient@acme.com");
        existing.setIsActive(false);
        existing.setUnsubscribeToken(token);
        when(repository.findByUnsubscribeToken(token)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.unsubscribeByToken(token, null))
                .assertNext(saved -> assertThat(saved.getIsActive()).isFalse())
                .verifyComplete();

        verify(auditPublisher, org.mockito.Mockito.never()).publish(any(AuditEvent.class));
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void unsubscribeByToken_unknownToken_raisesNoSuchElement() {
        UUID token = UUID.randomUUID();
        when(repository.findByUnsubscribeToken(token)).thenReturn(Mono.empty());

        StepVerifier.create(service.unsubscribeByToken(token, null))
                .expectError(NoSuchElementException.class)
                .verify();
    }

    @Test
    void update_recipientBelongingToDifferentSchedule_isRejected() {
        UUID recipientId = UUID.randomUUID();
        TenantReportScheduleRecipient existing = new TenantReportScheduleRecipient();
        existing.setId(recipientId);
        existing.setScheduleId(UUID.randomUUID());   // different schedule
        existing.setIsActive(true);
        when(repository.findById(recipientId)).thenReturn(Mono.just(existing));

        var req = new UpdateTenantReportScheduleRecipientRequest(null, false);

        StepVerifier.create(service.update(TENANT_ID, SCHEDULE_ID, recipientId, req,
                        ACTOR, "admin@acme"))
                .expectErrorSatisfies(err -> assertThat(err)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("does not belong to schedule"))
                .verify();
    }
}
