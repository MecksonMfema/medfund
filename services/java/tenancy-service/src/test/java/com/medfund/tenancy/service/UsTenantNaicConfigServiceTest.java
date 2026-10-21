package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddUsTenantNaicConfigRequest;
import com.medfund.tenancy.dto.UpdateUsTenantNaicConfigRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.UsTenantNaicConfig;
import com.medfund.tenancy.repository.TenantRepository;
import com.medfund.tenancy.repository.UsTenantNaicConfigRepository;
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

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsTenantNaicConfigServiceTest {

    @Mock
    private UsTenantNaicConfigRepository repository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;
    @Mock
    private AuditPublisher auditPublisher;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;

    @InjectMocks
    private UsTenantNaicConfigService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    @BeforeEach
    void stubTenantAndAudit() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setSlug("acme-us");
        lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(t));
        lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    // ── add ─────────────────────────────────────────────────────────────────

    @Test
    void add_populatesRow_upcasesState_defaultsEffectiveFrom_publishesFriendlyEntityName() {
        var req = new AddUsTenantNaicConfigRequest(
                "il", "12345", "0999", "12-3456789",
                null, null, "Onboarding intake");
        when(r2dbcTemplate.insert(any(UsTenantNaicConfig.class))).thenAnswer(inv -> {
            UsTenantNaicConfig saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@insurer-us"))
                .assertNext(row -> {
                    assertThat(row.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(row.getStateDomicile()).isEqualTo("IL");
                    assertThat(row.getNaicCompanyCode()).isEqualTo("12345");
                    assertThat(row.getNaicGroupCode()).isEqualTo("0999");
                    assertThat(row.getFein()).isEqualTo("12-3456789");
                    assertThat(row.getEffectiveFrom()).isEqualTo(LocalDate.now());
                    assertThat(row.getEffectiveTo()).isNull();
                    assertThat(row.getSourceNote()).isEqualTo("Onboarding intake");
                    assertThat(row.getUpdatedByEmail()).isEqualTo("admin@insurer-us");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityName())
                .contains("acme-us")
                .contains("IL")
                .contains("12345")
                .contains("12-3456789");
        assertThat(ev.actorEmail()).isEqualTo("admin@insurer-us");
    }

    @Test
    void add_optionalGroupCodeBlank_storedAsNull() {
        var req = new AddUsTenantNaicConfigRequest(
                "CA", "999", "   ", "987654321",
                LocalDate.of(2026, 1, 1), null, null);
        when(r2dbcTemplate.insert(any(UsTenantNaicConfig.class))).thenAnswer(inv -> {
            UsTenantNaicConfig saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@insurer-us"))
                .assertNext(row -> assertThat(row.getNaicGroupCode()).isNull())
                .verifyComplete();
    }

    @Test
    void add_rejectsBlankState() {
        var req = new AddUsTenantNaicConfigRequest(
                "  ", "12345", null, "12-3456789", null, null, null);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@insurer-us"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateDomicile");
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void add_rejectsBlankCompanyCode() {
        var req = new AddUsTenantNaicConfigRequest(
                "IL", "", null, "12-3456789", null, null, null);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@insurer-us"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("naicCompanyCode");
        verify(r2dbcTemplate, never()).insert(any());
    }

    @Test
    void add_rejectsBlankFein() {
        var req = new AddUsTenantNaicConfigRequest(
                "IL", "12345", null, null, null, null, null);
        assertThatThrownBy(() -> service.add(TENANT_ID, req, ACTOR, "admin@insurer-us"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fein");
        verify(r2dbcTemplate, never()).insert(any());
    }

    // ── update ──────────────────────────────────────────────────────────────

    @Test
    void update_appliesOnlyNonNullFields_publishesChangedFieldsAudit() {
        UUID rowId = UUID.randomUUID();
        UsTenantNaicConfig existing = existingRow(rowId, "IL", "12345", "0999", "12-3456789");
        when(repository.findById(rowId)).thenReturn(Mono.just(existing));
        when(repository.save(any(UsTenantNaicConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var req = new UpdateUsTenantNaicConfigRequest(
                "ca", null, null, null,
                LocalDate.of(2027, 12, 31), "Rebranded intake");

        StepVerifier.create(service.update(TENANT_ID, rowId, req, ACTOR, "admin@insurer-us"))
                .assertNext(saved -> {
                    assertThat(saved.getStateDomicile()).isEqualTo("CA");     // upcased
                    assertThat(saved.getNaicCompanyCode()).isEqualTo("12345"); // unchanged
                    assertThat(saved.getNaicGroupCode()).isEqualTo("0999");   // unchanged
                    assertThat(saved.getFein()).isEqualTo("12-3456789");      // unchanged
                    assertThat(saved.getEffectiveTo()).isEqualTo(LocalDate.of(2027, 12, 31));
                    assertThat(saved.getSourceNote()).isEqualTo("Rebranded intake");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("UPDATE");
        assertThat(ev.changedFields())
                .contains("stateDomicile", "effectiveTo", "sourceNote")
                .doesNotContain("naicCompanyCode", "naicGroupCode", "fein");
    }

    @Test
    void update_blankGroupCode_clearsToNull() {
        UUID rowId = UUID.randomUUID();
        UsTenantNaicConfig existing = existingRow(rowId, "IL", "12345", "0999", "12-3456789");
        when(repository.findById(rowId)).thenReturn(Mono.just(existing));
        when(repository.save(any(UsTenantNaicConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        var req = new UpdateUsTenantNaicConfigRequest(
                null, null, "  ", null, null, null);

        StepVerifier.create(service.update(TENANT_ID, rowId, req, ACTOR, "admin@insurer-us"))
                .assertNext(saved -> assertThat(saved.getNaicGroupCode()).isNull())
                .verifyComplete();
    }

    @Test
    void update_rejects_whenRowBelongsToDifferentTenant() {
        UUID rowId = UUID.randomUUID();
        UsTenantNaicConfig existing = existingRow(rowId, "IL", "12345", null, "12-3456789");
        existing.setTenantId(OTHER_TENANT);
        when(repository.findById(rowId)).thenReturn(Mono.just(existing));

        var req = new UpdateUsTenantNaicConfigRequest(
                null, null, null, null, null, null);

        StepVerifier.create(service.update(TENANT_ID, rowId, req, ACTOR, "admin@insurer-us"))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void update_missingRow_yieldsNoSuchElement() {
        UUID rowId = UUID.randomUUID();
        when(repository.findById(rowId)).thenReturn(Mono.empty());

        var req = new UpdateUsTenantNaicConfigRequest(
                "IL", null, null, null, null, null);

        StepVerifier.create(service.update(TENANT_ID, rowId, req, ACTOR, "admin@insurer-us"))
                .expectError(NoSuchElementException.class)
                .verify();
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    void delete_removesRow_publishesDeleteAudit() {
        UUID rowId = UUID.randomUUID();
        UsTenantNaicConfig existing = existingRow(rowId, "IL", "12345", null, "12-3456789");
        when(repository.findById(rowId)).thenReturn(Mono.just(existing));
        when(repository.delete(existing)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(TENANT_ID, rowId, ACTOR, "admin@insurer-us"))
                .verifyComplete();

        verify(repository).delete(existing);
        verify(auditPublisher).publish(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("DELETE");
    }

    @Test
    void delete_rejects_whenRowBelongsToDifferentTenant() {
        UUID rowId = UUID.randomUUID();
        UsTenantNaicConfig existing = existingRow(rowId, "IL", "12345", null, "12-3456789");
        existing.setTenantId(OTHER_TENANT);
        when(repository.findById(rowId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.delete(TENANT_ID, rowId, ACTOR, "admin@insurer-us"))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(repository, never()).delete(any());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private UsTenantNaicConfig existingRow(UUID id, String state, String code, String group, String fein) {
        UsTenantNaicConfig row = new UsTenantNaicConfig();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setStateDomicile(state);
        row.setNaicCompanyCode(code);
        row.setNaicGroupCode(group);
        row.setFein(fein);
        row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        row.setEffectiveTo(null);
        row.setSourceNote("Initial intake");
        return row;
    }
}
