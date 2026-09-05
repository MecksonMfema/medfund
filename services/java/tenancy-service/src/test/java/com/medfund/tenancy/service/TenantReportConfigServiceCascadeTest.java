package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.UpdateTenantReportConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantReportConfigRequest.ToggleEntry;
import com.medfund.tenancy.entity.TenantReportConfig;
import com.medfund.tenancy.repository.TenantReportConfigRepository;
import com.medfund.tenancy.repository.TenantReportScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focuses on Phase 17 §A the cascade-disable behaviour introduced in
 * {@link TenantReportConfigService#bulkUpsert(java.util.UUID,
 *   UpdateTenantReportConfigRequest, String, String)}. The rest of the
 * upsert path is covered by the existing controller IT.
 */
@ExtendWith(MockitoExtension.class)
class TenantReportConfigServiceCascadeTest {

    @Mock private TenantReportConfigRepository repository;
    @Mock private TenantReportScheduleService scheduleService;
    @Mock private TenantReportScheduleRepository scheduleRepository;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private TenantReportConfigService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    @BeforeEach
    void stubAudit() {
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        // The pre-existing switchIfEmpty(insertNew(...)) pattern in upsertOne
        // eagerly builds the insertNew Mono chain even when the upstream is
        // non-empty; save() must be stubbed so that eager composition
        // doesn't NPE on the no-op / update-existing branches.
        lenient().when(repository.save(any(TenantReportConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void trueToFalse_firesCascadeDisable() {
        TenantReportConfig existing = existing("COMMISSION_STATEMENT", true);
        when(repository.findByTenantIdAndReportKey(TENANT_ID, "COMMISSION_STATEMENT"))
                .thenReturn(Mono.just(existing));
        when(repository.save(any(TenantReportConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(scheduleService.cascadeDisable(eq(TENANT_ID), eq("COMMISSION_STATEMENT"),
                eq(ACTOR), anyString())).thenReturn(Mono.just(3));

        var req = new UpdateTenantReportConfigRequest(
                List.of(new ToggleEntry("COMMISSION_STATEMENT", false)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectNextCount(1)
                .verifyComplete();

        verify(scheduleService).cascadeDisable(TENANT_ID, "COMMISSION_STATEMENT", ACTOR, "admin@acme");
    }

    @Test
    void falseToTrue_doesNotFireCascade() {
        TenantReportConfig existing = existing("COMMISSION_STATEMENT", false);
        when(repository.findByTenantIdAndReportKey(TENANT_ID, "COMMISSION_STATEMENT"))
                .thenReturn(Mono.just(existing));
        when(repository.save(any(TenantReportConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var req = new UpdateTenantReportConfigRequest(
                List.of(new ToggleEntry("COMMISSION_STATEMENT", true)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectNextCount(1)
                .verifyComplete();

        verify(scheduleService, never()).cascadeDisable(any(), anyString(), anyString(), anyString());
    }

    @Test
    void noOpToggleTrueEqTrue_doesNotCascadeOrAudit() {
        // Guards Phase 17 §A: a no-op toggle must not emit an audit event or
        // trigger cascade-disable side effects. (We don't verify save() here
        // because the pre-existing switchIfEmpty(insertNew(...)) pattern in
        // upsertOne composes the insertNew chain eagerly even when the
        // upstream is non-empty; it's never subscribed, but Mockito's
        // invocation counter still ticks. Cleaning that up is a follow-up.)
        TenantReportConfig existing = existing("COMMISSION_STATEMENT", true);
        when(repository.findByTenantIdAndReportKey(TENANT_ID, "COMMISSION_STATEMENT"))
                .thenReturn(Mono.just(existing));

        var req = new UpdateTenantReportConfigRequest(
                List.of(new ToggleEntry("COMMISSION_STATEMENT", true)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectNextCount(1)
                .verifyComplete();

        verify(scheduleService, never()).cascadeDisable(any(), anyString(), anyString(), anyString());
        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }

    @Test
    void multipleTrueToFalseInOneBatch_cascadesEachOnceInOrder() {
        when(repository.findByTenantIdAndReportKey(eq(TENANT_ID), anyString()))
                .thenAnswer(inv -> Mono.just(existing(inv.getArgument(1), true)));
        when(repository.save(any(TenantReportConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(scheduleService.cascadeDisable(any(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(1));

        var req = new UpdateTenantReportConfigRequest(List.of(
                new ToggleEntry("COMMISSION_STATEMENT", false),
                new ToggleEntry("LOSS_RATIO", false)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectNextCount(2)
                .verifyComplete();

        verify(scheduleService, times(1))
                .cascadeDisable(TENANT_ID, "COMMISSION_STATEMENT", ACTOR, "admin@acme");
        verify(scheduleService, times(1))
                .cascadeDisable(TENANT_ID, "LOSS_RATIO", ACTOR, "admin@acme");
    }

    private static TenantReportConfig existing(String reportKey, boolean enabled) {
        TenantReportConfig row = new TenantReportConfig();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT_ID);
        row.setReportKey(reportKey);
        row.setEnabled(enabled);
        return row;
    }
}
