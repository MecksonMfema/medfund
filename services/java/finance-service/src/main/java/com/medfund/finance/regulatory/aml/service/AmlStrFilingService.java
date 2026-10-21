package com.medfund.finance.regulatory.aml.service;

import com.medfund.finance.regulatory.aml.AmlFilingBlobStore;
import com.medfund.finance.regulatory.aml.AmlFilingIdentityReader;
import com.medfund.finance.regulatory.aml.AmlStrFilingXlsxService;
import com.medfund.finance.regulatory.aml.AmlStrFilingXlsxService.AmlStrFilingRenderResult;
import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import com.medfund.shared.tenant.TenantMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the Phase 26 per-STR filing XLSX pipeline: (1) resolve the
 * tenant's country + reporting identity, (2) render the country-specific
 * template, and (3) if MinIO is wired, upload + return the {@code s3://}
 * reference the caller writes into {@code suspicious_transaction_alert.filed_xlsx_ref}.
 *
 * <p>Two callers:
 * <ol>
 *   <li>{@link AmlAlertService#file(UUID, com.medfund.finance.regulatory.aml.dto.FileAmlAlertRequest, String, String)}
 *       — auto-generates the XLSX + stores the ref on the REVIEWED→FILED
 *       transition. Best-effort: upload failure logs + swallows so the
 *       transition still commits (matches the Kafka fan-out posture).</li>
 *   <li>{@code AmlStrReportController#exportPerStrXlsx} — re-renders
 *       on-demand for the "download XLSX" link on already-FILED alerts.
 *       Idempotent since the shape is a pure function of the alert row.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmlStrFilingService {

    private final AmlStrFilingXlsxService xlsxService;
    private final AmlFilingIdentityReader identityReader;
    private final TenantMetadataReader tenantMetadataReader;
    private final Optional<AmlFilingBlobStore> blobStore;

    /**
     * Render the per-STR XLSX for {@code alert} in {@code tenantId}'s
     * country. Returns the rendered bytes + template provenance —
     * callers decide whether to also upload (see {@link #renderAndStore}).
     */
    public Mono<AmlStrFilingRenderResult> render(UUID tenantId, SuspiciousTransactionAlert alert) {
        if (tenantId == null) {
            return Mono.error(new IllegalArgumentException("tenantId required"));
        }
        if (alert == null) {
            return Mono.error(new IllegalArgumentException("alert required"));
        }
        return Mono.zip(
                        tenantMetadataReader.load(tenantId),
                        identityReader.load(tenantId))
                .flatMap(t -> xlsxService.render(
                        tenantId,
                        alert,
                        t.getT2().reportingEntityName(),
                        t.getT2().regulatorReference(),
                        t.getT1().countryCode()));
    }

    /**
     * Render + upload to MinIO (if the blob store is present). Returns
     * the render result paired with the {@code s3://} ref (empty when
     * MinIO isn't wired). Blob-upload failures propagate — callers on
     * the workflow-transition path wrap this in an
     * {@code onErrorResume} so the FILED transition still commits.
     */
    public Mono<StoreResult> renderAndStore(UUID tenantId, SuspiciousTransactionAlert alert) {
        return render(tenantId, alert).map(rendered -> {
            String ref = blobStore
                    .map(store -> store.upload(tenantId, alert.getId(), rendered.bytes()))
                    .orElse(null);
            if (ref == null) {
                log.info("[aml-str-filing] MinIO blob store not wired — filedXlsxRef stays null "
                        + "for alert {} tenant {} (rendered {} bytes)",
                        alert.getId(), tenantId, rendered.bytes().length);
            }
            return new StoreResult(rendered, Optional.ofNullable(ref));
        });
    }

    public record StoreResult(AmlStrFilingRenderResult rendered, Optional<String> filedXlsxRef) {}
}
