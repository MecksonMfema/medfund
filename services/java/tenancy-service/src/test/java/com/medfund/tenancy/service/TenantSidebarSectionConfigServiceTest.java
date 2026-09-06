package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.sidebar.SidebarSectionKey;
import com.medfund.tenancy.dto.TenantSidebarSectionConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantSidebarSectionConfigRequest;
import com.medfund.tenancy.dto.UpdateTenantSidebarSectionConfigRequest.ToggleEntry;
import com.medfund.tenancy.entity.TenantSidebarSectionConfig;
import com.medfund.tenancy.repository.TenantSidebarSectionConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantSidebarSectionConfigServiceTest {

    @Mock private TenantSidebarSectionConfigRepository repository;
    @Mock private AuditPublisher auditPublisher;

    @InjectMocks private TenantSidebarSectionConfigService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final String ACTOR_EMAIL = "admin@acme";

    @BeforeEach
    void stubAudit() {
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
        // Match the sibling TenantReportConfigServiceCascadeTest pattern: the
        // switchIfEmpty(insertNew(...)) branch composes the insertNew Mono
        // chain eagerly even on the no-op / update-existing branches, so save
        // has to be lenient-stubbed.
        lenient().when(repository.save(any(TenantSidebarSectionConfig.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    void list_returnsEveryEnumValueDefaultEnabledWhenNoRowsExist() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Flux.empty());

        StepVerifier.create(service.list(TENANT_ID).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSameSizeAs(SidebarSectionKey.values());
                    assertThat(rows).allMatch(TenantSidebarSectionConfigResponse::enabled);
                    assertThat(rows).allMatch(r -> r.id() == null);
                })
                .verifyComplete();
    }

    @Test
    void list_overlaysPersistedRows() {
        TenantSidebarSectionConfig disabled = existing("FINANCE_PAYMENT_RUNS", false);
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Flux.just(disabled));

        StepVerifier.create(service.list(TENANT_ID)
                        .filter(r -> "FINANCE_PAYMENT_RUNS".equals(r.sectionKey()))
                        .single())
                .assertNext(row -> {
                    assertThat(row.enabled()).isFalse();
                    assertThat(row.id()).isEqualTo(disabled.getId());
                })
                .verifyComplete();
    }

    @Test
    void bulkUpsert_rejectsUnknownKeyBeforeAnyWrites() {
        var req = new UpdateTenantSidebarSectionConfigRequest(
                List.of(new ToggleEntry("NOT_A_REAL_KEY", false)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, ACTOR_EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void bulkUpsert_insertsNewRowAndEmitsCreateAudit() {
        when(repository.findByTenantIdAndSectionKey(TENANT_ID, "FINANCE_PAYMENT_RUNS"))
                .thenReturn(Mono.empty());

        var req = new UpdateTenantSidebarSectionConfigRequest(
                List.of(new ToggleEntry("FINANCE_PAYMENT_RUNS", false)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, ACTOR_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(captor.capture());
        AuditEvent ev = captor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityType()).isEqualTo("TENANT_SIDEBAR_SECTION_CONFIG");
        assertThat(ev.actorEmail()).isEqualTo(ACTOR_EMAIL);
        assertThat(ev.entityName()).contains("Payment Runs").contains("sidebar toggle");
    }

    @Test
    void bulkUpsert_flippingPersistedRowEmitsUpdateAuditWithChangedField() {
        TenantSidebarSectionConfig existing = existing("FINANCE_PAYMENT_RUNS", true);
        when(repository.findByTenantIdAndSectionKey(TENANT_ID, "FINANCE_PAYMENT_RUNS"))
                .thenReturn(Mono.just(existing));

        var req = new UpdateTenantSidebarSectionConfigRequest(
                List.of(new ToggleEntry("FINANCE_PAYMENT_RUNS", false)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, ACTOR_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(captor.capture());
        AuditEvent ev = captor.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(ev.changedFields()).contains("enabled");
    }

    @Test
    void bulkUpsert_noOpFlipDoesNotWriteOrAudit() {
        TenantSidebarSectionConfig existing = existing("FINANCE_PAYMENT_RUNS", true);
        when(repository.findByTenantIdAndSectionKey(TENANT_ID, "FINANCE_PAYMENT_RUNS"))
                .thenReturn(Mono.just(existing));

        var req = new UpdateTenantSidebarSectionConfigRequest(
                List.of(new ToggleEntry("FINANCE_PAYMENT_RUNS", true)));

        StepVerifier.create(service.bulkUpsert(TENANT_ID, req, ACTOR, ACTOR_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }

    @Test
    void isEnabled_returnsTrueWhenNoRowExists() {
        when(repository.findByTenantIdAndSectionKey(eq(TENANT_ID), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.isEnabled(TENANT_ID, "FINANCE_PAYMENT_RUNS"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isEnabled_returnsFalseWhenRowSaysDisabled() {
        when(repository.findByTenantIdAndSectionKey(TENANT_ID, "FINANCE_PAYMENT_RUNS"))
                .thenReturn(Mono.just(existing("FINANCE_PAYMENT_RUNS", false)));

        StepVerifier.create(service.isEnabled(TENANT_ID, "FINANCE_PAYMENT_RUNS"))
                .expectNext(false)
                .verifyComplete();
    }

    private static TenantSidebarSectionConfig existing(String sectionKey, boolean enabled) {
        TenantSidebarSectionConfig row = new TenantSidebarSectionConfig();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT_ID);
        row.setSectionKey(sectionKey);
        row.setEnabled(enabled);
        return row;
    }
}
