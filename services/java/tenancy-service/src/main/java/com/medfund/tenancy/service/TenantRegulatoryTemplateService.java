package com.medfund.tenancy.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.tenancy.dto.AddTenantRegulatoryTemplateRequest;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.entity.TenantRegulatoryTemplate;
import com.medfund.tenancy.repository.TenantRegulatoryTemplateRepository;
import com.medfund.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * CRUD service for {@code public.tenant_regulatory_template} — tenant-side
 * overrides of the bundled regulator XLSX templates loaded by
 * {@code com.medfund.shared.report.regulatory.RegulatoryTemplateService}.
 *
 * <p>Upload validation is strict: the base64 payload must parse cleanly as
 * an {@link XSSFWorkbook} and be ≤2 MB. Anything else surfaces as 400 via
 * {@link IllegalArgumentException} — a corrupt XLSX silently accepted here
 * would break the regulator report generation weeks later at the far end.
 *
 * <p>Every mutation emits an {@link AuditEvent} with a friendly
 * {@code entityName} (regulator / report_key / version) per Rule 8 and the
 * {@code feedback_audit_entity_name} memory. Cross-tenant read/write attempts
 * reject with {@link IllegalArgumentException} per Rule 2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRegulatoryTemplateService {

    private static final String ENTITY_TYPE = "TENANT_REGULATORY_TEMPLATE";
    /** Hard upper bound on template size — 2 MB. Real regulator templates run 50-500 KB. */
    public static final int MAX_XLSX_BYTES = 2 * 1024 * 1024;

    private final TenantRegulatoryTemplateRepository repository;
    private final TenantRepository tenantRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    public Flux<TenantRegulatoryTemplate> list(UUID tenantId) {
        return repository.findByTenantIdOrderByRegulatorAscReportKeyAscEffectiveFromDesc(tenantId);
    }

    public Mono<TenantRegulatoryTemplate> get(UUID tenantId, UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new NoSuchElementException(
                        "Tenant regulatory template not found: " + id)))
                .flatMap(row -> {
                    if (!row.getTenantId().equals(tenantId)) {
                        return Mono.error(new IllegalArgumentException(
                                "Regulatory template does not belong to tenant"));
                    }
                    return Mono.just(row);
                });
    }

    @Transactional
    public Mono<TenantRegulatoryTemplate> add(UUID tenantId,
                                              AddTenantRegulatoryTemplateRequest req,
                                              String actorId, String actorEmail) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(req.xlsxBase64());
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("xlsxBase64 is not valid base64", e));
        }
        if (bytes.length == 0) {
            return Mono.error(new IllegalArgumentException("xlsxBase64 decodes to zero bytes"));
        }
        if (bytes.length > MAX_XLSX_BYTES) {
            return Mono.error(new IllegalArgumentException(
                    "XLSX exceeds " + (MAX_XLSX_BYTES / 1024) + " KB limit (" + bytes.length + " bytes)"));
        }
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) {
                return Mono.error(new IllegalArgumentException("XLSX contains no sheets"));
            }
        } catch (IOException | RuntimeException e) {
            return Mono.error(new IllegalArgumentException("XLSX failed to parse: " + e.getMessage(), e));
        }

        LocalDate effectiveFrom = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();
        String hash = sha256Hex(bytes);

        TenantRegulatoryTemplate row = new TenantRegulatoryTemplate();
        row.setTenantId(tenantId);
        row.setRegulator(req.regulator());
        row.setReportKey(req.reportKey());
        row.setVersionLabel(req.versionLabel());
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(req.effectiveTo());
        row.setXlsxBytes(bytes);
        row.setFileSizeBytes((long) bytes.length);
        row.setContentHash(hash);
        row.setUploadedAt(OffsetDateTime.now());
        row.setActorId(parseUuid(actorId));
        row.setActorEmail(actorEmail);
        row.setNotes(req.notes());
        return r2dbcTemplate.insert(row)
                .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<Void> delete(UUID tenantId, UUID id, String actorId, String actorEmail) {
        return get(tenantId, id)
                .flatMap(existing -> repository.delete(existing)
                        .then(publishAudit(existing, existing, "DELETE", actorId, actorEmail)))
                .then();
    }

    // ── Audit ──────────────────────────────────────────────────────────────────

    private Mono<Void> publishAudit(TenantRegulatoryTemplate current,
                                    TenantRegulatoryTemplate previous,
                                    String action, String actorId, String actorEmail) {
        Map<String, Object> oldMap = previous != null ? toMap(previous) : null;
        Map<String, Object> newMap = "DELETE".equals(action) ? null : toMap(current);
        String[] changed = "UPDATE".equals(action) && oldMap != null && newMap != null
                ? changedFields(oldMap, newMap) : null;
        return tenantRepository.findById(current.getTenantId())
                .map(Tenant::getSlug)
                .defaultIfEmpty("unknown")
                .flatMap(slug -> auditPublisher.publish(AuditEvent.create(
                        current.getTenantId().toString(),
                        ENTITY_TYPE,
                        current.getId().toString(),
                        String.format("RegulatoryTemplate for tenant %s %s / %s v%s",
                                slug, current.getRegulator(), current.getReportKey(), current.getVersionLabel()),
                        action,
                        actorId,
                        actorEmail,
                        oldMap,
                        newMap,
                        changed,
                        UUID.randomUUID().toString())));
    }

    private static Map<String, Object> toMap(TenantRegulatoryTemplate row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("regulator", row.getRegulator());
        m.put("reportKey", row.getReportKey());
        m.put("versionLabel", row.getVersionLabel());
        m.put("effectiveFrom", row.getEffectiveFrom() != null ? row.getEffectiveFrom().toString() : null);
        m.put("effectiveTo", row.getEffectiveTo() != null ? row.getEffectiveTo().toString() : null);
        m.put("fileSizeBytes", row.getFileSizeBytes());
        m.put("contentHash", row.getContentHash());
        m.put("notes", row.getNotes());
        return m;
    }

    private static String[] changedFields(Map<String, Object> oldMap, Map<String, Object> newMap) {
        return newMap.keySet().stream()
                .filter(k -> !java.util.Objects.equals(oldMap.get(k), newMap.get(k)))
                .toArray(String[]::new);
    }

    private static String sha256Hex(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
