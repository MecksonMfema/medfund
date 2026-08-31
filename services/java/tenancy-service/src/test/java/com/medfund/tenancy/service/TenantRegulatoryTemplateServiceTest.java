package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantRegulatoryTemplateRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantRegulatoryTemplate;
import com.medfund.tenancy.repository.TenantRegulatoryTemplateRepository;
import com.medfund.tenancy.repository.TenantRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantRegulatoryTemplateServiceTest {

    @Mock
    private TenantRegulatoryTemplateRepository repository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private R2dbcEntityTemplate r2dbcTemplate;
    @Mock
    private AuditPublisher auditPublisher;

    @Captor
    private ArgumentCaptor<AuditEvent> auditCaptor;

    @InjectMocks
    private TenantRegulatoryTemplateService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    private static byte[] tinyValidXlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("ok");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @BeforeEach
    void stubTenantSlug() {
        // Every audit publish looks up the tenant slug for the entityName.
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setSlug("acme");
        org.mockito.Mockito.lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Mono.just(t));
        org.mockito.Mockito.lenient().when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    @Test
    void add_validXlsx_persistsAndAuditsCreate() {
        byte[] bytes = tinyValidXlsx();
        var req = new AddTenantRegulatoryTemplateRequest(
                "ipec", "ipec-quarterly-return", "2024-06-01",
                LocalDate.of(2024, 6, 1), null,
                Base64.getEncoder().encodeToString(bytes),
                "portal export");

        when(r2dbcTemplate.insert(any(TenantRegulatoryTemplate.class))).thenAnswer(inv -> {
            TenantRegulatoryTemplate saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .assertNext(row -> {
                    assertThat(row.getRegulator()).isEqualTo("ipec");
                    assertThat(row.getReportKey()).isEqualTo("ipec-quarterly-return");
                    assertThat(row.getVersionLabel()).isEqualTo("2024-06-01");
                    assertThat(row.getXlsxBytes()).isEqualTo(bytes);
                    assertThat(row.getFileSizeBytes()).isEqualTo((long) bytes.length);
                    assertThat(row.getContentHash()).hasSize(64);
                    assertThat(row.getActorEmail()).isEqualTo("admin@acme");
                    assertThat(row.getNotes()).isEqualTo("portal export");
                })
                .verifyComplete();

        verify(auditPublisher).publish(auditCaptor.capture());
        AuditEvent ev = auditCaptor.getValue();
        assertThat(ev.action()).isEqualTo("CREATE");
        assertThat(ev.entityType()).isEqualTo("TENANT_REGULATORY_TEMPLATE");
        // entityName must be friendly text (feedback_audit_entity_name), never UUID.
        assertThat(ev.entityName()).contains("acme").contains("ipec").contains("2024-06-01");
    }

    @Test
    void add_invalidBase64_400() {
        var req = new AddTenantRegulatoryTemplateRequest(
                "ipec", "ipec-quarterly-return", "2024-06-01",
                null, null, "@@@not base64@@@", null);

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("base64"))
                .verify();

        verify(r2dbcTemplate, never()).insert(any(TenantRegulatoryTemplate.class));
    }

    @Test
    void add_nonXlsxPayload_400() {
        // Valid base64 but decodes to plain text — XSSFWorkbook will reject.
        String junk = Base64.getEncoder().encodeToString("not an XLSX at all".getBytes());
        var req = new AddTenantRegulatoryTemplateRequest(
                "ipec", "ipec-quarterly-return", "2024-06-01",
                null, null, junk, null);

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("XLSX"))
                .verify();
    }

    @Test
    void add_oversizedPayload_400() {
        // 3MB of zeros — over the 2MB cap; base64 of zero-bytes still fails XLSX parse first,
        // but the size check runs before the parse so we hit that gate.
        byte[] big = new byte[3 * 1024 * 1024];
        String encoded = Base64.getEncoder().encodeToString(big);
        var req = new AddTenantRegulatoryTemplateRequest(
                "ipec", "ipec-quarterly-return", "2024-06-01",
                null, null, encoded, null);

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("KB limit"))
                .verify();
    }

    @Test
    void add_emptyPayload_400() {
        var req = new AddTenantRegulatoryTemplateRequest(
                "ipec", "ipec-quarterly-return", "2024-06-01",
                null, null, "", null);

        StepVerifier.create(service.add(TENANT_ID, req, ACTOR, "admin@acme"))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException)
                .verify();
    }

    @Test
    void list_returnsRows() {
        TenantRegulatoryTemplate row = new TenantRegulatoryTemplate();
        row.setTenantId(TENANT_ID);
        row.setRegulator("ipec");
        when(repository.findByTenantIdOrderByRegulatorAscReportKeyAscEffectiveFromDesc(TENANT_ID))
                .thenReturn(Flux.just(row));

        StepVerifier.create(service.list(TENANT_ID))
                .expectNext(row)
                .verifyComplete();
    }

    @Test
    void get_crossTenant_rejects() {
        TenantRegulatoryTemplate row = new TenantRegulatoryTemplate();
        row.setId(UUID.randomUUID());
        row.setTenantId(OTHER_TENANT);
        when(repository.findById(row.getId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.get(TENANT_ID, row.getId()))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("does not belong"))
                .verify();
    }

    @Test
    void get_missing_404() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Mono.empty());

        StepVerifier.create(service.get(TENANT_ID, missing))
                .expectError(NoSuchElementException.class)
                .verify();
    }

    @Test
    void delete_ownRow_deletesAndAudits() {
        TenantRegulatoryTemplate row = new TenantRegulatoryTemplate();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT_ID);
        row.setRegulator("ipec");
        row.setReportKey("ipec-quarterly-return");
        row.setVersionLabel("2024-06-01");
        when(repository.findById(row.getId())).thenReturn(Mono.just(row));
        when(repository.delete(row)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(TENANT_ID, row.getId(), ACTOR, "admin@acme"))
                .verifyComplete();

        verify(repository).delete(row);
        verify(auditPublisher).publish(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("DELETE");
    }

    @Test
    void delete_crossTenant_rejects_noAudit() {
        TenantRegulatoryTemplate row = new TenantRegulatoryTemplate();
        row.setId(UUID.randomUUID());
        row.setTenantId(OTHER_TENANT);
        when(repository.findById(row.getId())).thenReturn(Mono.just(row));

        StepVerifier.create(service.delete(TENANT_ID, row.getId(), ACTOR, "admin@acme"))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(repository, never()).delete(any(TenantRegulatoryTemplate.class));
        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }
}
