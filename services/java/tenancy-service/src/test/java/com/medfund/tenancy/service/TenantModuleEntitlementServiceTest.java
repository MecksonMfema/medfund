package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.tenancy.entity.TenantModuleEntitlement;
import com.medfund.tenancy.kafka.ModuleEntitlementEventPublisher;
import com.medfund.tenancy.repository.TenantModuleEntitlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantModuleEntitlementServiceTest {

    @Mock private TenantModuleEntitlementRepository repo;
    @Mock private AuditPublisher auditPublisher;
    @Mock private ModuleEntitlementEventPublisher eventPublisher;
    @Mock private SecurityEventPublisher securityEventPublisher;

    @InjectMocks private TenantModuleEntitlementService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final String ACTOR_EMAIL = "admin@acme";

    @BeforeEach
    void stubCollaborators() {
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publish(anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(Mono.empty());
        lenient().when(securityEventPublisher.publishEntitlementChanged(
                anyString(), any(), any(), anyString(), anyBoolean())).thenReturn(Mono.empty());
        lenient().when(repo.save(any(TenantModuleEntitlement.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void setEntitlement_rejectsUnknownModuleBeforeAnyWrite() {
        StepVerifier.create(service.setEntitlement(TENANT_ID, "NOT_A_MODULE", true, ACTOR, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repo, never()).save(any());
        verify(auditPublisher, never()).publish(any());
        verify(eventPublisher, never()).publish(anyString(), anyString(), anyBoolean(), any());
        verify(securityEventPublisher, never())
                .publishEntitlementChanged(anyString(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void setEntitlement_insertsNewRow_audits_broadcasts_andEmitsSecurityEvent() {
        when(repo.findByTenantIdAndModuleKey(TENANT_ID, "KYC")).thenReturn(Mono.empty());

        StepVerifier.create(service.setEntitlement(TENANT_ID, "KYC", true, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> {
                    assertThat(r.moduleKey()).isEqualTo("KYC");
                    assertThat(r.enabled()).isTrue();
                })
                .verifyComplete();

        // Rule 8 — audit with the friendly entityName + non-blank actorEmail.
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(audit.capture());
        AuditEvent ev = audit.getValue();
        assertThat(ev.entityType()).isEqualTo("TENANT_MODULE_ENTITLEMENT");
        assertThat(ev.actorEmail()).isEqualTo(ACTOR_EMAIL);
        assertThat(ev.entityName()).contains("KYC & Verification").contains("entitlement");
        assertThat(ev.changedFields()).contains("enabled");

        // Rule 6 — Kafka broadcast so consumers invalidate.
        verify(eventPublisher, times(1)).publish(eq(TENANT_ID.toString()), eq("KYC"), eq(true), eq(ACTOR_EMAIL));

        // G5 / Rule 9 — an entitlement flip is a privilege-level change.
        verify(securityEventPublisher, times(1))
                .publishEntitlementChanged(eq(TENANT_ID.toString()), eq(ACTOR), eq(ACTOR_EMAIL), eq("KYC"), eq(true));
    }

    @Test
    void setEntitlement_noOpFlip_doesNotWriteAuditBroadcastOrSecurityEvent() {
        TenantModuleEntitlement existing = existing("KYC", true);
        when(repo.findByTenantIdAndModuleKey(TENANT_ID, "KYC")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.setEntitlement(TENANT_ID, "KYC", true, ACTOR, ACTOR_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        verify(repo, never()).save(any());
        verify(auditPublisher, never()).publish(any());
        verify(eventPublisher, never()).publish(anyString(), anyString(), anyBoolean(), any());
        verify(securityEventPublisher, never())
                .publishEntitlementChanged(anyString(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void setEntitlement_flippingExistingRow_emitsUpdateAudit() {
        TenantModuleEntitlement existing = existing("KYC", false);
        when(repo.findByTenantIdAndModuleKey(TENANT_ID, "KYC")).thenReturn(Mono.just(existing));

        StepVerifier.create(service.setEntitlement(TENANT_ID, "KYC", true, ACTOR, ACTOR_EMAIL))
                .assertNext(r -> assertThat(r.enabled()).isTrue())
                .verifyComplete();

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("UPDATE");
        verify(securityEventPublisher, times(1))
                .publishEntitlementChanged(anyString(), any(), any(), eq("KYC"), eq(true));
    }

    private static TenantModuleEntitlement existing(String moduleKey, boolean enabled) {
        TenantModuleEntitlement row = new TenantModuleEntitlement();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT_ID);
        row.setModuleKey(moduleKey);
        row.setEnabled(enabled);
        row.setVersion(0L);
        return row;
    }
}
